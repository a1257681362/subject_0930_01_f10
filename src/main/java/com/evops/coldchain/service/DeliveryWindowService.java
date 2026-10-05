package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.WindowCalendarRequest;
import com.evops.coldchain.dto.WindowChangeRequest;
import com.evops.coldchain.dto.WindowCreateRequest;
import com.evops.coldchain.entity.DeliveryWindow;
import com.evops.coldchain.entity.DeliveryWindowCalendar;
import com.evops.coldchain.entity.DeliveryWindowVersion;
import com.evops.coldchain.entity.DeliveryWindowWeekday;
import com.evops.coldchain.mapper.DeliveryWindowCalendarMapper;
import com.evops.coldchain.mapper.DeliveryWindowMapper;
import com.evops.coldchain.mapper.DeliveryWindowVersionMapper;
import com.evops.coldchain.mapper.DeliveryWindowWeekdayMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收货机构送达时窗服务（V10）。
 *
 * 两条相互独立、互不改写的规则线：
 * 1. 工作日可接收区间按版本维护——建档落 V1；调整只能追加新版本（旧版本置 SUPERSEDED，
 *    版本行与区间行原值永不修改），与药品档案/路线版本同一“只追加”口径。
 * 2. 节假日与临时闭馆独立成表保存——新增/作废都不产生工作日新版本；工作日版本调整也
 *    绝不改动任何节假日/闭馆行。闭馆规则只允许作废留痕，不提供原地修改/删除。
 *
 * 可接收区间解析 {@link #resolveReceivable} 把一个原始推算到达时刻对齐到“最近可接收时刻”：
 * 命中节假日/临时闭馆或落在当日区间之外时，顺延到下一个可接收工作日的首个区间起点，
 * 并把采用的版本号、命中的闭馆规则ID与顺延明细一并返回（供改期单整单快照）。
 */
@Service
public class DeliveryWindowService {

    /** 顺延保护上限（天）：超过仍找不到可接收工作日，说明时窗配置缺失，直接报错而不是死循环 */
    private static final int MAX_ROLL_DAYS = 366;

    @Resource
    private DeliveryWindowMapper windowMapper;
    @Resource
    private DeliveryWindowVersionMapper versionMapper;
    @Resource
    private DeliveryWindowWeekdayMapper weekdayMapper;
    @Resource
    private DeliveryWindowCalendarMapper calendarMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 建档 / 版本化调整 ----------------

    /** 建档：一个收货机构一行，同时发布工作日区间 V1（不含节假日，节假日走独立接口） */
    @Transactional
    public DeliveryWindow create(WindowCreateRequest req) {
        String org = req.getReceiverOrg().trim();
        DeliveryWindow existing = findByReceiverOrg(org);
        if (existing != null) {
            throw new BusinessException("收货机构 " + org + " 已存在送达时窗 " + existing.getWindowNo()
                    + "，工作日区间调整请走“规则调整（生成新版本）”");
        }
        List<ParsedDay> days = parseAndValidate(req.getWeekdays());

        DeliveryWindow window = new DeliveryWindow();
        window.setWindowNo("SW" + sequenceMapper.nextWindowNo());
        window.setReceiverOrg(org);
        window.setReceiverName(trimToNull(req.getReceiverName()));
        window.setContactName(trimToNull(req.getContactName()));
        window.setContactPhone(trimToNull(req.getContactPhone()));
        window.setCurrentVersion(1);
        window.setStatus(CcConstants.ACTIVE);
        window.setRemark(trimToNull(req.getRemark()));
        windowMapper.insert(window);

        publishVersion(window, 1, null, days);

        opLogService.log(CcConstants.ENT_DELIVERY_WINDOW, window.getId(), null, CcConstants.ACT_WINDOW_CREATE,
                String.format("收货机构 %s 送达时窗建档 %s：发布工作日可接收区间 V1（%d 个工作日有接收安排）",
                        org, window.getWindowNo(), days.size()));
        return window;
    }

    /**
     * 工作日区间规则调整：只能生成新版本。
     * 旧版本（含其区间行）原值封存不动；本方法不触碰任何节假日/临时闭馆行——
     * 节假日规则结论独立保存，调整工作日区间不能改动节假日旧结论。
     */
    @Transactional
    public DeliveryWindowVersion change(Long windowId, WindowChangeRequest req) {
        requireManagePermission();
        DeliveryWindow window = requireWindow(windowId);
        if (CcConstants.REVOKED.equals(window.getStatus())) {
            throw new BusinessException("送达时窗已撤回，不能调整: " + window.getWindowNo());
        }
        String reason = trimToNull(req.getChangeReason());
        if (reason == null) {
            throw new BusinessException("工作日区间规则调整必须填写调整原因");
        }
        // 机构主档字段允许随调整更新（机构联系信息不是规则结论，版本序列只承载工作日区间）
        if (trimToNull(req.getReceiverName()) != null) {
            window.setReceiverName(trimToNull(req.getReceiverName()));
        }
        if (trimToNull(req.getContactName()) != null) {
            window.setContactName(trimToNull(req.getContactName()));
        }
        if (trimToNull(req.getContactPhone()) != null) {
            window.setContactPhone(trimToNull(req.getContactPhone()));
        }
        List<ParsedDay> days = parseAndValidate(req.getWeekdays());

        DeliveryWindowVersion current = requireVersion(window.getId(), window.getCurrentVersion());
        current.setStatus(CcConstants.SUPERSEDED);
        versionMapper.updateById(current);

        int newVersionNo = window.getCurrentVersion() + 1;
        DeliveryWindowVersion next = publishVersion(window, newVersionNo, reason, days);
        window.setCurrentVersion(newVersionNo);
        windowMapper.updateById(window);

        opLogService.log(CcConstants.ENT_DELIVERY_WINDOW, window.getId(), null, CcConstants.ACT_WINDOW_CHANGE,
                String.format("收货机构 %s 工作日可接收区间调整 V%d（原因：%s）：旧 V%d 已封存，"
                                + "节假日与临时闭馆规则独立保存、本次未做任何改动",
                        window.getReceiverOrg(), newVersionNo, reason, current.getVersionNo()));
        return next;
    }

    private DeliveryWindowVersion publishVersion(DeliveryWindow window, int versionNo,
                                                 String changeReason, List<ParsedDay> days) {
        LocalDateTime now = LocalDateTime.now();
        DeliveryWindowVersion version = new DeliveryWindowVersion();
        version.setWindowId(window.getId());
        version.setVersionNo(versionNo);
        version.setChangeReason(changeReason);
        version.setPublishTime(now);
        version.setStatus(CcConstants.EFFECTIVE);
        versionMapper.insert(version);

        for (ParsedDay day : days) {
            int seq = 1;
            for (ParsedSlot slot : day.slots) {
                DeliveryWindowWeekday row = new DeliveryWindowWeekday();
                row.setVersionId(version.getId());
                row.setWeekday(day.weekday);
                row.setSlotSeq(seq++);
                row.setOpenTime(slot.open);
                row.setCloseTime(slot.close);
                row.setRemark(slot.remark);
                weekdayMapper.insert(row);
            }
        }
        return version;
    }

    // ---------------- 节假日 / 临时闭馆：独立保存，只追加，作废留痕 ----------------

    /** 新增节假日/临时闭馆规则：独立落一行，不产生工作日新版本 */
    @Transactional
    public DeliveryWindowCalendar addCalendarRule(Long windowId, WindowCalendarRequest req) {
        requireManagePermission();
        DeliveryWindow window = requireWindow(windowId);
        String type = normalizeCalendarType(req.getRuleType());
        if (req.getStartDate() == null || req.getEndDate() == null
                || req.getEndDate().isBefore(req.getStartDate())) {
            throw new BusinessException("闭馆日期区间不合法：截止日期不能早于开始日期");
        }
        String reason = req.getReason().trim();

        DeliveryWindowCalendar rule = new DeliveryWindowCalendar();
        rule.setWindowId(window.getId());
        rule.setRuleType(type);
        rule.setStartDate(req.getStartDate());
        rule.setEndDate(req.getEndDate());
        rule.setReason(reason);
        rule.setStatus(CcConstants.EFFECTIVE);
        calendarMapper.insert(rule);

        opLogService.log(CcConstants.ENT_DELIVERY_WINDOW, window.getId(), null, CcConstants.ACT_HOLIDAY_ADD,
                String.format("收货机构 %s 独立新增%s规则：%s ~ %s %s（不影响工作日区间版本，当前仍为 V%d）",
                        window.getReceiverOrg(),
                        CcConstants.CAL_TEMP_CLOSED.equals(type) ? "临时闭馆" : "节假日",
                        req.getStartDate(), req.getEndDate(), reason, window.getCurrentVersion()));
        return rule;
    }

    /** 作废节假日/临时闭馆规则：只置 REVOKED + 原因/时间留痕，不删行、不改原值 */
    @Transactional
    public DeliveryWindowCalendar voidCalendarRule(Long ruleId, String voidReason) {
        DeliveryWindowCalendar rule = calendarMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("节假日/临时闭馆规则不存在: " + ruleId);
        }
        if (CcConstants.REVOKED.equals(rule.getStatus())) {
            throw new BusinessException("规则已作废，不能重复作废");
        }
        String reason = voidReason == null ? null : voidReason.trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException("作废节假日/临时闭馆规则必须填写作废原因");
        }
        rule.setStatus(CcConstants.REVOKED);
        rule.setVoidReason(reason);
        rule.setVoidTime(LocalDateTime.now());
        calendarMapper.updateById(rule);

        DeliveryWindow window = requireWindow(rule.getWindowId());
        opLogService.log(CcConstants.ENT_DELIVERY_WINDOW, window.getId(), null, CcConstants.ACT_HOLIDAY_VOID,
                String.format("收货机构 %s %s规则[%s(%s~%s) %s]作废留痕：%s；原行保留，已被改期单引用的旧结论不变",
                        window.getReceiverOrg(),
                        CcConstants.CAL_TEMP_CLOSED.equals(rule.getRuleType()) ? "临时闭馆" : "节假日",
                        rule.getId(), rule.getStartDate(), rule.getEndDate(), rule.getReason(), reason));
        return rule;
    }

    // ---------------- 可接收区间解析 ----------------

    /**
     * 把原始推算到达时刻对齐为收货机构“最近可接收时刻”。
     * 命中闭馆日/当日无区间/已过截止时刻则逐日顺延，取可接收日的首个可用区间起点；
     * 落在某一区间 [open, close) 内（含起点）则原时刻即可接收；早于当日首段起点则对齐到该起点。
     *
     * @param windowId 收货机构时窗ID
     * @param raw      原始推算到达时刻（=计划发运 + 在途时长）
     */
    @Transactional(readOnly = true)
    public ReceivableResolution resolveReceivable(Long windowId, LocalDateTime raw) {
        DeliveryWindow window = requireWindow(windowId);
        DeliveryWindowVersion version = requireVersion(window.getId(), window.getCurrentVersion());
        Map<Integer, List<DeliveryWindowWeekday>> byWeekday = loadWeekdaySlots(version.getId());
        if (byWeekday.isEmpty()) {
            throw new BusinessException(String.format(
                    "收货机构 %s 的工作日区间 V%d 未配置任何可接收工作日，无法计算可接收时刻",
                    window.getReceiverOrg(), version.getVersionNo()));
        }
        List<DeliveryWindowCalendar> closures = listEffectiveCalendar(window.getId());

        LocalDateTime candidate = raw;
        List<Long> hitRuleIds = new ArrayList<>();
        List<String> hitReasons = new ArrayList<>();
        for (int day = 0; day <= MAX_ROLL_DAYS; day++) {
            LocalDate date = candidate.toLocalDate();
            DeliveryWindowCalendar closure = coveringClosure(closures, date);
            if (closure != null) {
                if (!hitRuleIds.contains(closure.getId())) {
                    hitRuleIds.add(closure.getId());
                    hitReasons.add(String.format("%s(%s~%s) %s",
                            CcConstants.CAL_TEMP_CLOSED.equals(closure.getRuleType()) ? "临时闭馆" : "节假日",
                            closure.getStartDate(), closure.getEndDate(), closure.getReason()));
                }
                candidate = date.plusDays(1).atStartOfDay();
                continue;
            }
            List<DeliveryWindowWeekday> slots = byWeekday.get(date.getDayOfWeek().getValue());
            if (slots == null || slots.isEmpty()) {
                candidate = date.plusDays(1).atStartOfDay();
                continue;
            }
            LocalTime t = candidate.toLocalTime();
            // 当天 00:00 由闭馆/无工作日顺延而来：直接取首段起点
            if (t.equals(LocalTime.MIDNIGHT)) {
                DeliveryWindowWeekday first = slots.get(0);
                candidate = date.atTime(first.getOpenTime());
                return build(window, version, raw, candidate, hitRuleIds, hitReasons);
            }
            for (DeliveryWindowWeekday slot : slots) {
                if (t.isBefore(slot.getOpenTime())) {
                    candidate = date.atTime(slot.getOpenTime());
                    return build(window, version, raw, candidate, hitRuleIds, hitReasons);
                }
                if (t.compareTo(slot.getOpenTime()) >= 0 && t.isBefore(slot.getCloseTime())) {
                    return build(window, version, raw, candidate, hitRuleIds, hitReasons);
                }
                // t >= close：尝试当日下一段
            }
            candidate = date.plusDays(1).atStartOfDay();
        }
        throw new BusinessException(String.format(
                "自 %s 起顺延 %d 天仍未找到收货机构 %s 的可接收工作日，请检查工作日区间/闭馆规则配置",
                raw, MAX_ROLL_DAYS, window.getReceiverOrg()));
    }

    private ReceivableResolution build(DeliveryWindow window, DeliveryWindowVersion version,
                                       LocalDateTime raw, LocalDateTime receivable,
                                       List<Long> hitRuleIds, List<String> hitReasons) {
        String detail;
        if (receivable.equals(raw) && hitRuleIds.isEmpty()) {
            detail = String.format("原始推算到达 %s 落在可接收区间内，无需顺延（采用 %s 工作日区间 V%d）",
                    raw, window.getReceiverOrg(), version.getVersionNo());
        } else {
            detail = String.format("原始推算到达 %s，按收货机构 %s 工作日区间 V%d 对齐到可接收时刻 %s",
                    raw, window.getReceiverOrg(), version.getVersionNo(), receivable);
            if (!hitReasons.isEmpty()) {
                detail += "；途中命中：" + String.join("、", hitReasons);
            }
        }
        return new ReceivableResolution(receivable, version.getId(), version.getVersionNo(),
                new ArrayList<>(hitRuleIds), detail);
    }

    /** 可接收区间解析结论（改期单按此整单快照，之后规则变更/作废不影响本结论） */
    public static class ReceivableResolution {
        private final LocalDateTime receivableTime;
        private final Long windowVersionId;
        private final Integer windowVersionNo;
        private final List<Long> matchedCalendarRuleIds;
        private final String calcDetail;

        public ReceivableResolution(LocalDateTime receivableTime, Long windowVersionId, Integer windowVersionNo,
                                    List<Long> matchedCalendarRuleIds, String calcDetail) {
            this.receivableTime = receivableTime;
            this.windowVersionId = windowVersionId;
            this.windowVersionNo = windowVersionNo;
            this.matchedCalendarRuleIds = matchedCalendarRuleIds;
            this.calcDetail = calcDetail;
        }

        public LocalDateTime getReceivableTime() { return receivableTime; }
        public Long getWindowVersionId() { return windowVersionId; }
        public Integer getWindowVersionNo() { return windowVersionNo; }
        public List<Long> getMatchedCalendarRuleIds() { return matchedCalendarRuleIds; }
        public String getCalcDetail() { return calcDetail; }
    }

    // ---------------- 查询 ----------------

    public DeliveryWindow requireWindow(Long windowId) {
        DeliveryWindow window = windowMapper.selectById(windowId);
        if (window == null) {
            throw new BusinessException("收货机构送达时窗不存在: " + windowId);
        }
        return window;
    }

    public DeliveryWindow findByReceiverOrg(String receiverOrg) {
        if (receiverOrg == null) {
            return null;
        }
        return windowMapper.selectOne(new QueryWrapper<DeliveryWindow>()
                .eq("receiver_org", receiverOrg.trim()).last("LIMIT 1"));
    }

    /** 全部收货机构时窗主档（台账查看） */
    public List<DeliveryWindow> listAll() {
        return windowMapper.selectList(new QueryWrapper<DeliveryWindow>().orderByAsc("id"));
    }

    public DeliveryWindowVersion requireVersion(Long windowId, int versionNo) {
        DeliveryWindowVersion version = versionMapper.selectOne(new QueryWrapper<DeliveryWindowVersion>()
                .eq("window_id", windowId).eq("version_no", versionNo));
        if (version == null) {
            throw new BusinessException(String.format("送达时窗版本不存在: windowId=%d, version=%d",
                    windowId, versionNo));
        }
        return version;
    }

    public List<DeliveryWindowVersion> listVersions(Long windowId) {
        return versionMapper.selectList(new QueryWrapper<DeliveryWindowVersion>()
                .eq("window_id", windowId).orderByAsc("version_no"));
    }

    public List<DeliveryWindowWeekday> listWeekdays(Long versionId) {
        return weekdayMapper.selectList(new QueryWrapper<DeliveryWindowWeekday>()
                .eq("version_id", versionId).orderByAsc("weekday").orderByAsc("slot_seq"));
    }

    public List<DeliveryWindowCalendar> listCalendar(Long windowId) {
        return calendarMapper.selectList(new QueryWrapper<DeliveryWindowCalendar>()
                .eq("window_id", windowId).orderByAsc("start_date").orderByAsc("id"));
    }

    public List<DeliveryWindowCalendar> listEffectiveCalendar(Long windowId) {
        return calendarMapper.selectList(new QueryWrapper<DeliveryWindowCalendar>()
                .eq("window_id", windowId).eq("status", CcConstants.EFFECTIVE)
                .orderByAsc("start_date"));
    }

    public DeliveryWindowCalendar requireCalendarRule(Long ruleId) {
        DeliveryWindowCalendar rule = calendarMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("节假日/临时闭馆规则不存在: " + ruleId);
        }
        return rule;
    }

    // ---------------- 内部工具 ----------------

    /**
     * 工作日版本调整 / 日历新增与 versions、calendar 两条“GET 只读 + POST 写”同路径绑定，
     * Shiro 链不区分 HTTP 方法，故写操作在服务层按维护权限点二次校验（与抽检立项同做法）。
     * 未登录或无权限点统一以业务异常拒绝（越权写不放行）。
     */
    private void requireManagePermission() {
        if (!org.apache.shiro.SecurityUtils.getSubject().isPermitted(CcConstants.PERM_WINDOW_MANAGE)) {
            throw new BusinessException("Forbidden：当前账号无收货机构送达时窗维护权限（"
                    + CcConstants.PERM_WINDOW_MANAGE + "）");
        }
    }

    private Map<Integer, List<DeliveryWindowWeekday>> loadWeekdaySlots(Long versionId) {
        List<DeliveryWindowWeekday> rows = weekdayMapper.selectList(new QueryWrapper<DeliveryWindowWeekday>()
                .eq("version_id", versionId).orderByAsc("weekday").orderByAsc("slot_seq"));
        Map<Integer, List<DeliveryWindowWeekday>> map = new LinkedHashMap<>();
        for (DeliveryWindowWeekday row : rows) {
            map.computeIfAbsent(row.getWeekday(), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private DeliveryWindowCalendar coveringClosure(List<DeliveryWindowCalendar> closures, LocalDate date) {
        for (DeliveryWindowCalendar rule : closures) {
            if (!date.isBefore(rule.getStartDate()) && !date.isAfter(rule.getEndDate())) {
                return rule;
            }
        }
        return null;
    }

    private String normalizeCalendarType(String type) {
        if (type == null) {
            throw new BusinessException("规则类型不能为空：HOLIDAY 节假日 / TEMP_CLOSED 临时闭馆");
        }
        String t = type.trim().toUpperCase();
        if (CcConstants.CAL_HOLIDAY.equals(t) || CcConstants.CAL_TEMP_CLOSED.equals(t)) {
            return t;
        }
        throw new BusinessException("不支持的规则类型: " + type + "（仅 HOLIDAY / TEMP_CLOSED）");
    }

    /** 解析并校验工作日区间：星期合法、时刻合法 open<close、同一工作日区间不得重叠 */
    private List<ParsedDay> parseAndValidate(List<WindowCreateRequest.WeekdaySlot> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new BusinessException("工作日可接收区间不能为空");
        }
        Map<Integer, List<ParsedSlot>> byDay = new LinkedHashMap<>();
        for (WindowCreateRequest.WeekdaySlot day : weekdays) {
            if (day.getWeekday() == null || day.getWeekday() < 1 || day.getWeekday() > 7) {
                throw new BusinessException("星期取值必须在 1(周一)~7(周日) 之间");
            }
            if (day.getSlots() == null || day.getSlots().isEmpty()) {
                throw new BusinessException("星期 " + day.getWeekday() + " 至少配置一个可接收区间");
            }
            List<ParsedSlot> parsed = new ArrayList<>();
            for (WindowCreateRequest.Slot s : day.getSlots()) {
                LocalTime open;
                LocalTime close;
                try {
                    open = LocalTime.parse(s.getOpenTime().trim());
                    close = LocalTime.parse(s.getCloseTime().trim());
                } catch (Exception e) {
                    throw new BusinessException(String.format(
                            "星期 %d 区间时刻格式不合法（应为 HH:mm，如 09:00）：%s~%s",
                            day.getWeekday(), s.getOpenTime(), s.getCloseTime()));
                }
                if (!open.isBefore(close)) {
                    throw new BusinessException(String.format(
                            "星期 %d 区间开始时刻必须早于截止时刻：%s~%s", day.getWeekday(), open, close));
                }
                parsed.add(new ParsedSlot(open, close, trimToNull(s.getRemark())));
            }
            parsed.sort((a, b) -> a.open.compareTo(b.open));
            for (int i = 1; i < parsed.size(); i++) {
                if (parsed.get(i).open.isBefore(parsed.get(i - 1).close)) {
                    throw new BusinessException(String.format(
                            "星期 %d 的可接收区间存在重叠：%s~%s 与 %s~%s",
                            day.getWeekday(), parsed.get(i - 1).open, parsed.get(i - 1).close,
                            parsed.get(i).open, parsed.get(i).close));
                }
            }
            if (byDay.containsKey(day.getWeekday())) {
                throw new BusinessException("星期 " + day.getWeekday() + " 的区间被重复提交，请合并为同一组");
            }
            byDay.put(day.getWeekday(), parsed);
        }
        List<ParsedDay> result = new ArrayList<>();
        for (Map.Entry<Integer, List<ParsedSlot>> e : byDay.entrySet()) {
            result.add(new ParsedDay(e.getKey(), e.getValue()));
        }
        result.sort((a, b) -> Integer.compare(a.weekday, b.weekday));
        return result;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static class ParsedDay {
        final int weekday;
        final List<ParsedSlot> slots;

        ParsedDay(int weekday, List<ParsedSlot> slots) {
            this.weekday = weekday;
            this.slots = slots;
        }
    }

    private static class ParsedSlot {
        final LocalTime open;
        final LocalTime close;
        final String remark;

        ParsedSlot(LocalTime open, LocalTime close, String remark) {
            this.open = open;
            this.close = close;
            this.remark = remark;
        }
    }
}
