package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.BatchLoadRequest;
import com.evops.coldchain.dto.RescheduleApplyRequest;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.RescheduleNotify;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.BatchAlertZoneSnapMapper;
import com.evops.coldchain.mapper.BatchRescheduleMapper;
import com.evops.coldchain.mapper.RescheduleNotifyMapper;
import com.evops.coldchain.mapper.RescheduleTodoMapper;
import com.evops.coldchain.mapper.RouteSegmentSnapMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运输批次改期服务（V10）。
 *
 * 不可违背的口径：
 * 1. 改期只重算并更新预计到达时间（estimated_arrive_time）；原承诺送达时间
 *    （promised_arrive_time，派车时固化）在申请/审批/驳回任何路径下都不改写，
 *    改期单只对其做整单快照。
 * 2. 已进入装车状态（load_time 非空）的批次改期必须审批：申请停留 PENDING 不放行，
 *    审批同意后才重算生效；未装车批次改期 DIRECT 直接生效；驳回不生效、不产生通知与待办。
 * 3. 生效改期列出并固化受影响清单：受影响温控区间（取发运时路线温区分段快照与批次预警温区，
 *    绝不取路线当前版本）、承运通知（逐次追加、可重试）、待办责任人（承运/调度/收货逐项办结）。
 * 4. 重算采用改期当刻收货机构最新生效工作日区间版本，命中的节假日/闭馆规则ID随单快照；
 *    此后时窗版本再调整或节假日规则作废，本单旧结论不变、永久可追。
 *
 * 仅依赖 Mapper 与 {@link DeliveryWindowService}，不依赖 TransportBatchService，避免循环引用。
 */
@Service
public class BatchRescheduleService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private BatchRescheduleMapper rescheduleMapper;
    @Resource
    private RescheduleNotifyMapper notifyMapper;
    @Resource
    private RescheduleTodoMapper todoMapper;
    @Resource
    private RouteSegmentSnapMapper segmentSnapMapper;
    @Resource
    private BatchAlertZoneSnapMapper alertSnapMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private DeliveryWindowService deliveryWindowService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 装车 ----------------

    /** 装车登记：登记后批次“已进入装车状态”，此后改期必须审批；装车事实只进不退 */
    @Transactional
    public TransportBatch load(BatchLoadRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能登记装车: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能登记装车: " + batch.getBatchNo());
        }
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "仅已派车未发运的批次允许登记装车，批次 %s 当前状态=%s",
                    batch.getBatchNo(), batch.getStatus()));
        }
        if (batch.getLoadTime() != null) {
            throw new BusinessException(String.format("批次 %s 已于 %s 装车，装车事实只进不退、不可重复登记",
                    batch.getBatchNo(), batch.getLoadTime()));
        }
        LocalDateTime loadTime = req.getLoadTime() == null ? LocalDateTime.now() : req.getLoadTime();
        batch.setLoadTime(loadTime);
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_BATCH_LOAD,
                String.format("批次 %s 登记装车（%s）：已进入装车状态，此后改期必须经审批同意才生效；"
                        + "原承诺送达时间=%s 不因任何改期改写",
                        batch.getBatchNo(), loadTime, batch.getPromisedArriveTime()));
        return batch;
    }

    // ---------------- 改期预览（不落库） ----------------

    /** 改期影响预览：重算预计到达 + 受影响温控区间，供申请/审批前核对；不写任何数据 */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long batchId, LocalDateTime newPlanShipTime) {
        TransportBatch batch = requireBatch(batchId);
        requireWindowBound(batch);
        if (newPlanShipTime == null) {
            throw new BusinessException("新的计划发运时间不能为空");
        }
        DeliveryWindowService.ReceivableResolution resolution = recalc(batch, newPlanShipTime);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNo", batch.getBatchNo());
        result.put("loaded", batch.getLoadTime() != null);
        result.put("loadTime", batch.getLoadTime());
        result.put("promisedArriveTime", batch.getPromisedArriveTime());
        result.put("currentEstimatedArriveTime", batch.getEstimatedArriveTime());
        result.put("newPlanShipTime", newPlanShipTime);
        result.put("newEstimatedArriveTime", resolution.getReceivableTime());
        result.put("windowVersionNo", resolution.getWindowVersionNo());
        result.put("matchedHolidayRuleIds", resolution.getMatchedCalendarRuleIds());
        result.put("calcDetail", resolution.getCalcDetail());
        result.put("approvalRequired", batch.getLoadTime() != null);
        result.put("affectedTempZones", affectedTempZones(batch));
        return result;
    }

    // ---------------- 改期申请 ----------------

    @Transactional
    public Map<String, Object> apply(RescheduleApplyRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能改期: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，送达时窗已完成，不能再改期: " + batch.getBatchNo());
        }
        requireWindowBound(batch);
        if (req.getNewPlanShipTime() == null) {
            throw new BusinessException("新的计划发运时间不能为空");
        }
        String reason = req.getReason() == null ? null : req.getReason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException("改期必须填写改期原因");
        }
        BatchReschedule pending = latestByBatch(batch.getId());
        if (pending != null && CcConstants.RS_PENDING.equals(pending.getApprovalStatus())) {
            throw new BusinessException(String.format(
                    "批次 %s 的改期单 %s 仍停留待审批（PENDING），不能重复发起改期",
                    batch.getBatchNo(), pending.getRescheduleNo()));
        }

        boolean loaded = batch.getLoadTime() != null;
        DeliveryWindowService.ReceivableResolution resolution = recalc(batch, req.getNewPlanShipTime());
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());

        BatchReschedule rs = new BatchReschedule();
        rs.setRescheduleNo("GQ" + sequenceMapper.nextRescheduleNo());
        rs.setBatchId(batch.getId());
        rs.setLoaded(loaded);
        rs.setReason(reason);
        rs.setOldPlanShipTime(order == null ? null : order.getPlanShipTime());
        rs.setNewPlanShipTime(req.getNewPlanShipTime());
        rs.setOldEstimatedArriveTime(batch.getEstimatedArriveTime());
        rs.setNewEstimatedArriveTime(resolution.getReceivableTime());
        rs.setPromisedArriveTimeSnap(batch.getPromisedArriveTime());
        rs.setWindowVersionId(resolution.getWindowVersionId());
        rs.setWindowVersionNo(resolution.getWindowVersionNo());
        rs.setHolidayRuleIds(resolution.getMatchedCalendarRuleIds().isEmpty() ? null
                : resolution.getMatchedCalendarRuleIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        rs.setCalcDetail(resolution.getCalcDetail());
        rs.setRequestBy(UserContext.currentId());
        rs.setRequestName(UserContext.currentName());
        rs.setRequestTime(LocalDateTime.now());

        if (loaded) {
            // 已装车：必须审批——落 PENDING，不重算落库、不发通知、不生待办
            rs.setApprovalStatus(CcConstants.RS_PENDING);
            rescheduleMapper.insert(rs);
            opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), batch.getId(),
                    CcConstants.ACT_RESCHEDULE_APPLY,
                    String.format("批次 %s 已装车（%s），改期单 %s 提交后停留待审批：新计划发运=%s，"
                                    + "重算预计到达=%s（工作日区间 V%s，命中节假日/闭馆规则=%s）；"
                                    + "审批同意前预计到达仍为 %s，原承诺送达时间=%s 不改写。原因：%s",
                            batch.getBatchNo(), batch.getLoadTime(), rs.getRescheduleNo(),
                            rs.getNewPlanShipTime(), rs.getNewEstimatedArriveTime(), rs.getWindowVersionNo(),
                            rs.getHolidayRuleIds() == null ? "无" : rs.getHolidayRuleIds(),
                            rs.getOldEstimatedArriveTime(), rs.getPromisedArriveTimeSnap(), reason));
            return applyResult(rs, batch, new ArrayList<>(), new ArrayList<>());
        }

        // 未装车：直接生效
        rs.setApprovalStatus(CcConstants.RS_DIRECT);
        rs.setAppliedTime(rs.getRequestTime());
        rescheduleMapper.insert(rs);
        List<RescheduleNotify> notifies = takeEffect(batch, rs);
        List<RescheduleTodo> todos = todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                .eq("reschedule_id", rs.getId()).orderByAsc("todo_seq"));
        return applyResult(rs, batch, notifies, todos);
    }

    // ---------------- 审批 ----------------

    /** 同意已装车批次改期：此刻才重算落库（预计到达）并发通知、生待办；承诺时间仍不动 */
    @Transactional
    public Map<String, Object> approve(Long rescheduleId, String reviewNote) {
        BatchReschedule rs = requirePending(rescheduleId);
        TransportBatch batch = requireBatch(rs.getBatchId());
        rs.setApprovalStatus(CcConstants.RS_APPROVED);
        rs.setReviewBy(UserContext.currentId());
        rs.setReviewName(UserContext.currentName());
        rs.setReviewTime(LocalDateTime.now());
        rs.setReviewNote(reviewNote.trim());
        rs.setAppliedTime(LocalDateTime.now());
        rescheduleMapper.updateById(rs);

        List<RescheduleNotify> notifies = takeEffect(batch, rs);
        List<RescheduleTodo> todos = todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                .eq("reschedule_id", rs.getId()).orderByAsc("todo_seq"));

        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), batch.getId(),
                CcConstants.ACT_RESCHEDULE_APPROVE,
                String.format("改期单 %s（批次 %s）审批同意并生效：预计到达 %s -> %s；"
                                + "原承诺送达时间=%s 保持不变；已发承运通知 %d 条、生成待办 %d 项。意见：%s",
                        rs.getRescheduleNo(), batch.getBatchNo(), rs.getOldEstimatedArriveTime(),
                        rs.getNewEstimatedArriveTime(), rs.getPromisedArriveTimeSnap(),
                        notifies.size(), todos.size(), reviewNote.trim()));
        return applyResult(rs, batch, notifies, todos);
    }

    /** 驳回：改期不生效，预计到达/承诺均不变，不发通知、不生待办 */
    @Transactional
    public BatchReschedule reject(Long rescheduleId, String reviewNote) {
        BatchReschedule rs = requirePending(rescheduleId);
        rs.setApprovalStatus(CcConstants.RS_REJECTED);
        rs.setReviewBy(UserContext.currentId());
        rs.setReviewName(UserContext.currentName());
        rs.setReviewTime(LocalDateTime.now());
        rs.setReviewNote(reviewNote.trim());
        rescheduleMapper.updateById(rs);

        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), rs.getBatchId(),
                CcConstants.ACT_RESCHEDULE_REJECT,
                String.format("改期单 %s（批次ID=%d）驳回，改期不生效：预计到达仍为 %s，"
                                + "原承诺送达时间=%s 不变，不发承运通知、不生待办。意见：%s",
                        rs.getRescheduleNo(), rs.getBatchId(), rs.getOldEstimatedArriveTime(),
                        rs.getPromisedArriveTimeSnap(), reviewNote.trim()));
        return rs;
    }

    /**
     * 生效落库：只更新预计到达这一列；然后生成承运通知与三方待办。
     * 承诺时间字段在本方法及整个类中没有任何写入路径。
     */
    private List<RescheduleNotify> takeEffect(TransportBatch batch, BatchReschedule rs) {
        batch.setEstimatedArriveTime(rs.getNewEstimatedArriveTime());
        batchMapper.updateById(batch);

        List<RescheduleNotify> notifies = new ArrayList<>();
        notifies.add(notifyCarrier(batch, rs, false, null));

        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        String carrierName = vehicle == null ? batch.getResponsibleOrg()
                : (vehicle.getCarrierName() == null ? vehicle.getPlateNo() : vehicle.getCarrierName());
        String driverName = vehicle == null || vehicle.getDriverName() == null
                ? (vehicle == null ? "驾驶员" : vehicle.getPlateNo()) : vehicle.getDriverName();

        addTodo(rs, 1, CcConstants.ROLE_CARRIER, driverName,
                String.format("按新预计到达 %s 调整在途温控/行车计划，全程保持温区 %s~%s℃，到货前与收货方确认接收时窗",
                        rs.getNewEstimatedArriveTime(), batch.getTempMinSnap(), batch.getTempMaxSnap()));
        addTodo(rs, 2, CcConstants.ROLE_DISPATCHER, "调度岗",
                String.format("更新批次 %s 调度计划并跟踪在途状态：计划发运=%s，预计到达 %s -> %s（承诺时间不变）",
                        batch.getBatchNo(), rs.getNewPlanShipTime(),
                        rs.getOldEstimatedArriveTime(), rs.getNewEstimatedArriveTime()));
        addTodo(rs, 3, CcConstants.ROLE_RECEIVER, batch.getReceiverOrg(),
                String.format("按新预计到达 %s 预留收货月台与验收人力；原承诺送达时间 %s 仍为核对基准",
                        rs.getNewEstimatedArriveTime(), rs.getPromisedArriveTimeSnap()));
        return notifies;
    }

    // ---------------- 承运通知 / 重试 ----------------

    private RescheduleNotify notifyCarrier(TransportBatch batch, BatchReschedule rs,
                                           boolean retry, String retryReason) {
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        String targetName = vehicle == null ? batch.getResponsibleOrg()
                : (vehicle.getDriverName() == null
                        ? (vehicle.getCarrierName() == null ? vehicle.getPlateNo() : vehicle.getCarrierName())
                        : vehicle.getDriverName() + "/" + (vehicle.getCarrierName() == null
                                ? vehicle.getPlateNo() : vehicle.getCarrierName()));
        String targetPhone = vehicle == null ? null : vehicle.getDriverPhone();
        int attempt = nextAttemptNo(rs.getId());
        String content = String.format(
                "批次 %s 改期%s：新计划发运=%s，预计到达 %s -> %s（收货机构 %s 按工作日区间 V%s 对齐%s）；"
                        + "原承诺送达时间 %s 不变，请据此安排在途温控与到货衔接。改期原因：%s%s",
                batch.getBatchNo(), retry ? "通知重试" : "通知",
                rs.getNewPlanShipTime(), rs.getOldEstimatedArriveTime(), rs.getNewEstimatedArriveTime(),
                batch.getReceiverOrg(), rs.getWindowVersionNo(),
                rs.getHolidayRuleIds() == null ? "" : "，命中节假日/闭馆规则 " + rs.getHolidayRuleIds(),
                rs.getPromisedArriveTimeSnap(), rs.getReason(),
                retryReason == null ? "" : "；重试原因：" + retryReason);

        RescheduleNotify row = new RescheduleNotify();
        row.setRescheduleId(rs.getId());
        row.setBatchId(batch.getId());
        row.setAttemptNo(attempt);
        row.setTargetName(targetName);
        row.setTargetPhone(targetPhone);
        // 演示环境站内信必达（写库即送达）；通知行逐次追加，失败/重试历史均可追溯
        row.setChannel(CcConstants.NOTIFY_CHANNEL_SITE);
        row.setSendStatus(CcConstants.NOTIFY_SUCCESS);
        row.setContent(content);
        row.setNotifyTime(LocalDateTime.now());
        notifyMapper.insert(row);

        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), batch.getId(),
                CcConstants.ACT_RESCHEDULE_NOTIFY,
                String.format("改期单 %s 第%d次承运通知：对象=%s，结果=SUCCESS，新旧预计到达=%s -> %s，承诺时间=%s 不变%s",
                        rs.getRescheduleNo(), attempt, targetName,
                        rs.getOldEstimatedArriveTime(), rs.getNewEstimatedArriveTime(),
                        rs.getPromisedArriveTimeSnap(), retry ? "（手动重试）" : ""));
        return row;
    }

    /** 手动再次通知承运方（新增通知行，attemptNo 递增；历史通知行不覆盖） */
    @Transactional
    public RescheduleNotify retryNotify(Long rescheduleId, String retryReason) {
        BatchReschedule rs = requireEffective(rescheduleId);
        TransportBatch batch = requireBatch(rs.getBatchId());
        String reason = retryReason == null ? null : retryReason.trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException("再次通知承运方必须填写原因");
        }
        return notifyCarrier(batch, rs, true, reason);
    }

    private int nextAttemptNo(Long rescheduleId) {
        Long n = notifyMapper.selectCount(new QueryWrapper<RescheduleNotify>()
                .eq("reschedule_id", rescheduleId));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    // ---------------- 待办办结 ----------------

    @Transactional
    public RescheduleTodo completeTodo(Long todoId, String doneNote) {
        RescheduleTodo todo = todoMapper.selectById(todoId);
        if (todo == null) {
            throw new BusinessException("改期待办不存在: " + todoId);
        }
        if (CcConstants.TODO_DONE.equals(todo.getStatus())) {
            throw new BusinessException("待办已办结，完成事实只进不退、不能重复办结");
        }
        todo.setStatus(CcConstants.TODO_DONE);
        todo.setDoneBy(UserContext.currentId());
        todo.setDoneName(UserContext.currentName());
        todo.setDoneTime(LocalDateTime.now());
        todo.setDoneNote(doneNote == null ? null : doneNote.trim());
        todoMapper.updateById(todo);

        opLogService.log(CcConstants.ENT_RESCHEDULE, todo.getRescheduleId(), todo.getBatchId(),
                CcConstants.ACT_RESCHEDULE_TODO_DONE,
                String.format("改期待办#%d 办结：责任岗位=%s，责任人=%s，事项=%s%s",
                        todo.getId(), todo.getOwnerRole(), todo.getOwnerName(), todo.getActionDesc(),
                        todo.getDoneNote() == null ? "" : "；办理说明：" + todo.getDoneNote()));
        return todo;
    }

    // ---------------- 受影响温控区间（一律取发运时快照） ----------------

    /**
     * 受影响温控区间：路线温区分段取发运时固化快照（t_cc_route_segment_snap），
     * 再加上批次预警温区（派车时类别模板快照命中的温区代码）；不读取路线/模板当前版本。
     */
    public List<Map<String, Object>> affectedTempZones(TransportBatch batch) {
        List<Map<String, Object>> zones = new ArrayList<>();
        if (batch.getRouteVersionId() != null) {
            List<RouteSegmentSnap> snaps = segmentSnapMapper.selectList(new QueryWrapper<RouteSegmentSnap>()
                    .eq("batch_id", batch.getId()).orderByAsc("segment_seq"));
            for (RouteSegmentSnap s : snaps) {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("source", "ROUTE_SEGMENT_SNAP");
                z.put("segmentSeq", s.getSegmentSeq());
                z.put("fromKm", s.getFromKm());
                z.put("toKm", s.getToKm());
                z.put("tempMin", s.getTempMin());
                z.put("tempMax", s.getTempMax());
                z.put("bufferMinutes", s.getBufferMinutes());
                z.put("ownerName", s.getOwnerName());
                z.put("remark", "发运时固化的路线温区分段快照，改期后在途温控仍按此执行");
                zones.add(z);
            }
        }
        if (batch.getAlertZoneCode() != null) {
            List<BatchAlertZoneSnap> alertSnaps = alertSnapMapper.selectList(new QueryWrapper<BatchAlertZoneSnap>()
                    .eq("batch_id", batch.getId()).eq("zone_code", batch.getAlertZoneCode()));
            for (BatchAlertZoneSnap a : alertSnaps) {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("source", "ALERT_ZONE_SNAP");
                z.put("zoneCode", a.getZoneCode());
                z.put("zoneName", a.getZoneName());
                z.put("tempMin", a.getTempMin());
                z.put("tempMax", a.getTempMax());
                z.put("tolerateMinutes", a.getTolerateMinutes());
                z.put("remark", "派车时类别温控预警模板快照温区，改期后连续超限判定仍按此执行");
                zones.add(z);
            }
            if (alertSnaps.isEmpty()) {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("source", "ALERT_ZONE_SNAP");
                z.put("zoneCode", batch.getAlertZoneCode());
                z.put("remark", "批次预警温区代码（详细阈值以派车时温区快照为准）");
                zones.add(z);
            }
        }
        if (zones.isEmpty()) {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("source", "DRUG_SNAP");
            z.put("tempMin", batch.getTempMinSnap());
            z.put("tempMax", batch.getTempMaxSnap());
            z.put("remark", "批次无路线分段/预警温区快照，全程按派车时药品档案温区快照执行");
            zones.add(z);
        }
        return zones;
    }

    // ---------------- 发运卡口 / 查询 ----------------

    /**
     * 发运卡口：批次存在待审批（PENDING）的装车后改期时不得发运——
     * 审批只完成一半必须停留待处理，同意（重算生效）或驳回（不生效）后才可继续。
     */
    public void requireRescheduleClearForShip(Long batchId) {
        BatchReschedule latest = latestByBatch(batchId);
        if (latest != null && CcConstants.RS_PENDING.equals(latest.getApprovalStatus())) {
            throw new BusinessException(String.format(
                    "批次（ID=%d）已装车改期单 %s 仍停留待审批（PENDING），审批同意/驳回前不得发运",
                    batchId, latest.getRescheduleNo()));
        }
    }

    public TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    public BatchReschedule requireReschedule(Long id) {
        BatchReschedule rs = rescheduleMapper.selectById(id);
        if (rs == null) {
            throw new BusinessException("改期单不存在: " + id);
        }
        return rs;
    }

    public BatchReschedule latestByBatch(Long batchId) {
        List<BatchReschedule> rows = rescheduleMapper.selectList(new QueryWrapper<BatchReschedule>()
                .eq("batch_id", batchId).orderByDesc("id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BatchReschedule> listByBatch(Long batchId) {
        return rescheduleMapper.selectList(new QueryWrapper<BatchReschedule>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    public List<RescheduleNotify> listNotifications(Long rescheduleId) {
        return notifyMapper.selectList(new QueryWrapper<RescheduleNotify>()
                .eq("reschedule_id", rescheduleId).orderByAsc("attempt_no"));
    }

    public List<RescheduleTodo> listTodos(Long rescheduleId) {
        return todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                .eq("reschedule_id", rescheduleId).orderByAsc("todo_seq"));
    }

    public List<RescheduleTodo> listOpenTodos() {
        return todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                .eq("status", CcConstants.TODO_OPEN).orderByAsc("due_time"));
    }

    // ---------------- 内部 ----------------

    /** 重算：新计划发运 + 派车固化的在途时长，再按收货机构当前生效时窗版本对齐可接收时刻 */
    private DeliveryWindowService.ReceivableResolution recalc(TransportBatch batch, LocalDateTime newPlanShipTime) {
        if (batch.getTransitMinutes() == null) {
            throw new BusinessException(String.format(
                    "批次 %s 缺少派车时固化的在途时长（transitMinutes），无法重算预计到达", batch.getBatchNo()));
        }
        LocalDateTime raw = newPlanShipTime.plusMinutes(batch.getTransitMinutes());
        return deliveryWindowService.resolveReceivable(batch.getDeliveryWindowId(), raw);
    }

    private void requireWindowBound(TransportBatch batch) {
        if (batch.getDeliveryWindowId() == null || batch.getPromisedArriveTime() == null) {
            throw new BusinessException(String.format(
                    "批次 %s 派车时收货机构[%s]尚未维护送达时窗（或未给在途时长），未固化原承诺送达时间，不能改期",
                    batch.getBatchNo(), batch.getReceiverOrg()));
        }
    }

    private BatchReschedule requirePending(Long id) {
        BatchReschedule rs = requireReschedule(id);
        if (!CcConstants.RS_PENDING.equals(rs.getApprovalStatus())) {
            throw new BusinessException(String.format(
                    "改期单 %s 当前状态=%s，仅待审批（PENDING）的已装车改期可审查，不能重复审批",
                    rs.getRescheduleNo(), rs.getApprovalStatus()));
        }
        return rs;
    }

    private BatchReschedule requireEffective(Long id) {
        BatchReschedule rs = requireReschedule(id);
        if (!CcConstants.RS_APPROVED.equals(rs.getApprovalStatus())
                && !CcConstants.RS_DIRECT.equals(rs.getApprovalStatus())) {
            throw new BusinessException(String.format(
                    "改期单 %s 当前状态=%s，仅已生效改期可发送/重试承运通知",
                    rs.getRescheduleNo(), rs.getApprovalStatus()));
        }
        return rs;
    }

    private void addTodo(BatchReschedule rs, int seq, String role, String ownerName, String action) {
        RescheduleTodo todo = new RescheduleTodo();
        todo.setRescheduleId(rs.getId());
        todo.setBatchId(rs.getBatchId());
        todo.setTodoSeq(seq);
        todo.setOwnerRole(role);
        todo.setOwnerName(ownerName);
        todo.setActionDesc(action);
        todo.setDueTime(rs.getNewEstimatedArriveTime());
        todo.setStatus(CcConstants.TODO_OPEN);
        todoMapper.insert(todo);
    }

    private Map<String, Object> applyResult(BatchReschedule rs, TransportBatch batch,
                                            List<RescheduleNotify> notifies, List<RescheduleTodo> todos) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reschedule", rs);
        result.put("promisedArriveTimeUnchanged", true);
        result.put("promisedArriveTime", batch.getPromisedArriveTime());
        result.put("estimatedArriveTime", batch.getEstimatedArriveTime());
        result.put("affectedTempZones", affectedTempZones(batch));
        result.put("carrierNotifications", notifies);
        result.put("todos", todos);
        return result;
    }
}
