package com.evops.coldchain;

import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.DeliveryWindow;
import com.evops.coldchain.entity.DeliveryWindowCalendar;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.BatchRescheduleMapper;
import com.evops.coldchain.mapper.DeliveryWindowCalendarMapper;
import com.evops.coldchain.mapper.DeliveryWindowMapper;
import com.evops.coldchain.mapper.DeliveryWindowVersionMapper;
import com.evops.coldchain.mapper.DeliveryWindowWeekdayMapper;
import com.evops.coldchain.service.BatchRescheduleService;
import com.evops.coldchain.service.DeliveryWindowService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送达时窗管理（V10）验收测试。
 *
 * 覆盖：
 * 1. 工作日可接收区间建档（V1）与派车固化原承诺送达时间；规则调整只能生成新版本（V2），
 *    旧版本行原值不变；节假日与临时闭馆独立保存（新增不升版本、作废只留痕）。
 * 2. 改期重算预计到达：遇节假日/非工作日顺延至下一可接收工作日首个区间；原承诺时间不改写。
 * 3. 未装车批次改期 DIRECT 直接生效；已装车批次改期必须审批（PENDING 不放行、拦截发运），
 *    审批同意后才生效，驳回不生效；生效时列出受影响温控区间、承运通知、待办责任人。
 * 4. 送达时窗完成（批次签收）后只能新版本规则，节假日旧结论不可改；签收后不得改期。
 * 5. Shiro 权限点：无维护/申请/审批权限 403。
 *
 * 内置账号：dispatcher/dispatcher123（时窗维护、装车、改期申请）、
 * inspector/inspector123（装车后改期审批）、receiver/receiver123（无改期申请权，用于 403）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeliveryWindowRescheduleIntegrationTest {

    private static final String BASE = "/api/cold-chain";
    private static final String DISPATCHER = "dispatcher";
    private static final String DISPW = "dispatcher123";
    private static final String INSPECTOR = "inspector";
    private static final String INSPW = "inspector123";
    private static final String RECEIVER = "receiver";
    private static final String RECPW = "receiver123";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private DeliveryWindowMapper windowMapper;
    @Autowired private DeliveryWindowVersionMapper versionMapper;
    @Autowired private DeliveryWindowWeekdayMapper weekdayMapper;
    @Autowired private DeliveryWindowCalendarMapper calendarMapper;
    @Autowired private BatchRescheduleMapper rescheduleMapper;
    @Autowired private DeliveryWindowService deliveryWindowService;
    @Autowired private BatchRescheduleService rescheduleService;
    @Autowired private TransportBatchService batchService;
    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private TestSubjects subjects;

    // ====================================================================
    // 1) 工作日区间版本化：建档 V1 -> 调整只出 V2，旧版本与节假日规则互不动
    // ====================================================================
    @Test
    void weekdayRules_versionOnly_holidaysIndependent() {
        String org = "V10版本医院-" + System.nanoTime();
        long windowId = createWindowHttp(org, "09:00", "17:00");

        // V1：7 个版本行？实际只有传入的工作日行；这里传周一~周五共 5 行
        assertEquals(1, windowMapper.selectById(windowId).getCurrentVersion());
        long v1Id = versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.DeliveryWindowVersion>()
                        .eq("window_id", windowId).eq("version_no", 1)).get(0).getId();
        int v1Rows = weekdayMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.DeliveryWindowWeekday>()
                        .eq("version_id", v1Id)).size();
        assertEquals(5, v1Rows);

        // 独立新增一条节假日：不升版本
        Map<String, Object> holiday = new LinkedHashMap<>();
        holiday.put("ruleType", "HOLIDAY");
        holiday.put("startDate", "2026-10-01");
        holiday.put("endDate", "2026-10-07");
        holiday.put("reason", "国庆放假");
        long holidayId = post(DISPATCHER, DISPW, "/delivery-windows/" + windowId + "/calendar", holiday)
                .path("data").path("id").asLong();
        assertEquals(1, windowMapper.selectById(windowId).getCurrentVersion(), "新增节假日不得产生新版本");

        // 独立新增临时闭馆：仍不升版本
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("ruleType", "TEMP_CLOSED");
        closed.put("startDate", "2026-11-11");
        closed.put("endDate", "2026-11-11");
        closed.put("reason", "库房盘点闭馆");
        post(DISPATCHER, DISPW, "/delivery-windows/" + windowId + "/calendar", closed);
        assertEquals(1, windowMapper.selectById(windowId).getCurrentVersion());

        // 规则调整：只能生成 V2（改成 10:00-16:00）
        Map<String, Object> change = windowBody(org, "10:00", "16:00");
        change.put("changeReason", "收货组作息调整");
        post(DISPATCHER, DISPW, "/delivery-windows/" + windowId + "/versions", change);
        DeliveryWindow after = windowMapper.selectById(windowId);
        assertEquals(2, after.getCurrentVersion());
        // V1 旧版本置 SUPERSEDED，其 5 行工作日区间原值保留
        assertEquals("SUPERSEDED", versionMapper.selectById(v1Id).getStatus());
        assertEquals(5, weekdayMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.DeliveryWindowWeekday>()
                        .eq("version_id", v1Id)).size());
        // 关键：工作日版本调整绝不动节假日/闭馆规则——两条仍 EFFECTIVE、原值原样
        DeliveryWindowCalendar holidayRow = calendarMapper.selectById(holidayId);
        assertEquals("EFFECTIVE", holidayRow.getStatus());
        assertEquals("国庆放假", holidayRow.getReason());
        assertEquals(2, calendarMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DeliveryWindowCalendar>()
                        .eq("window_id", windowId).eq("status", "EFFECTIVE")).size());

        // 节假日规则只能作废留痕，不能原地改（无更新接口），作废后原行仍在
        Map<String, Object> voidBody = new LinkedHashMap<>();
        voidBody.put("voidReason", "放假安排取消");
        post(DISPATCHER, DISPW, "/delivery-windows/calendar/" + holidayId + "/void", voidBody);
        DeliveryWindowCalendar voided = calendarMapper.selectById(holidayId);
        assertEquals("REVOKED", voided.getStatus());
        assertEquals("国庆放假", voided.getReason(), "作废不改原原因/日期值");
        assertNotNull(voided.getVoidTime());
    }

    // ====================================================================
    // 2) 派车固化承诺；改期遇节假日/闭馆/非工作时间顺延，只改预计到达不改承诺
    // ====================================================================
    @Test
    void dispatchFreezesPromise_rescheduleRollsOverHoliday_onlyEstimateChanges() {
        String org = "V10顺延医院-" + System.nanoTime();
        long windowId = createWindowService(org, "09:00", "11:30");

        // 周六（2026-10-03 是周六）凌晨加在途后必然落在非接收时段 -> 顺延到下周一 09:00
        LocalDateTime planShip = LocalDateTime.of(2026, 10, 3, 6, 0); // 周六
        TransportBatch batch = dispatchBatch(org, planShip, 120, false);
        assertEquals(DayOfWeek.SATURDAY, planShip.getDayOfWeek());
        // 原始推算 08:00 周六不可接收 -> 顺延至周一 2026-10-05 09:00
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), batch.getPromisedArriveTime());
        assertEquals(batch.getPromisedArriveTime(), batch.getEstimatedArriveTime());
        assertEquals(1, batch.getWindowVersionNo());

        // 节假日命中顺延：新增 10-05~10-06 放假，改期到周六发运 -> 应顺延到 10-07 周三 09:00
        addCalendarService(windowId, "HOLIDAY", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), "临时放假");
        LocalDateTime newShip = LocalDateTime.of(2026, 10, 3, 8, 0); // 周六，+120 分钟=10:00 周六
        Map<String, Object> preview = rescheduleService.preview(batch.getId(), newShip);
        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), preview.get("newEstimatedArriveTime"));
        assertFalse((Boolean) preview.get("approvalRequired"), "未装车无需审批");
        @SuppressWarnings("unchecked")
        List<Long> hitRules = (List<Long>) preview.get("matchedHolidayRuleIds");
        assertEquals(1, hitRules.size());

        // 未装车改期 DIRECT 直接生效
        com.evops.coldchain.dto.RescheduleApplyRequest req = new com.evops.coldchain.dto.RescheduleApplyRequest();
        req.setBatchId(batch.getId());
        req.setNewPlanShipTime(newShip);
        req.setReason("道路管制提前发车");
        Map<String, Object> result = rescheduleService.apply(req);
        BatchReschedule rs = (BatchReschedule) result.get("reschedule");
        assertEquals("DIRECT", rs.getApprovalStatus());

        // 预计到达已重算，承诺时间原样不动
        TransportBatch reloaded = batchService.requireBatch(batch.getId());
        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), reloaded.getEstimatedArriveTime());
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), reloaded.getPromisedArriveTime(),
                "原承诺送达时间不得被改期改写");
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), rs.getPromisedArriveTimeSnap());

        // 生效即列出受影响温控区间、承运通知、待办责任人
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zones = (List<Map<String, Object>>) result.get("affectedTempZones");
        assertFalse(zones.isEmpty());
        @SuppressWarnings("unchecked")
        List<com.evops.coldchain.entity.RescheduleNotify> notifies =
                (List<com.evops.coldchain.entity.RescheduleNotify>) result.get("carrierNotifications");
        assertEquals(1, notifies.size());
        assertEquals("SUCCESS", notifies.get(0).getSendStatus());
        @SuppressWarnings("unchecked")
        List<RescheduleTodo> todos = (List<RescheduleTodo>) result.get("todos");
        assertEquals(3, todos.size(), "承运/调度/收货三个责任岗位各一条待办");

        // 待办可逐项办结（事实只进不退）
        rescheduleService.completeTodo(todos.get(0).getId(), "驾驶员已调整行车计划");
        assertEquals("DONE", rescheduleService.listTodos(rs.getId()).get(0).getStatus());
        assertThrows(BusinessException.class,
                () -> rescheduleService.completeTodo(todos.get(0).getId(), "重复办结"));

        // 承运通知可再次发送：attemptNo 递增，历史行不覆盖
        com.evops.coldchain.entity.RescheduleNotify second =
                rescheduleService.retryNotify(rs.getId(), "驾驶员未确认");
        assertEquals(2, second.getAttemptNo());
        assertEquals(2, rescheduleService.listNotifications(rs.getId()).size());

        // 命中闭馆规则的改期单把规则ID快照：事后作废该规则，改期单旧结论不变
        DeliveryWindowCalendar rule = calendarMapper.selectById(hitRules.get(0));
        deliveryWindowService.voidCalendarRule(rule.getId(), "放假取消");
        BatchReschedule rsSnap = rescheduleMapper.selectById(rs.getId());
        assertEquals(String.valueOf(rule.getId()), rsSnap.getHolidayRuleIds());
        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), rsSnap.getNewEstimatedArriveTime());
    }

    // ====================================================================
    // 3) 已装车批次改期必须审批：PENDING 拦截发运；同意生效/驳回不生效
    // ====================================================================
    @Test
    void loadedBatch_rescheduleRequiresApproval_pendingBlocksShip() {
        String org = "V10装车医院-" + System.nanoTime();
        createWindowService(org, "09:00", "17:00");
        LocalDateTime planShip = LocalDateTime.of(2026, 10, 5, 8, 0); // 周一 +60=09:00 落在区间
        TransportBatch batch = dispatchBatch(org, planShip, 60, true);
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), batch.getPromisedArriveTime());

        // 登记装车（服务直调，dispatcher 主体）
        subjects.runAs(2L, DISPATCHER, () -> {
            com.evops.coldchain.dto.BatchLoadRequest load = new com.evops.coldchain.dto.BatchLoadRequest();
            load.setBatchId(batch.getId());
            rescheduleService.load(load);
        });
        assertNotNull(batchService.requireBatch(batch.getId()).getLoadTime());

        LocalDateTime newShip = LocalDateTime.of(2026, 10, 6, 20, 0); // 周二晚 +60=21:00 -> 次日 09:00
        com.evops.coldchain.dto.RescheduleApplyRequest req = new com.evops.coldchain.dto.RescheduleApplyRequest();
        req.setBatchId(batch.getId());
        req.setNewPlanShipTime(newShip);
        req.setReason("货源延迟，装车后改期");

        final BatchReschedule[] holder = new BatchReschedule[1];
        subjects.runAs(2L, DISPATCHER, () -> {
            Map<String, Object> r = rescheduleService.apply(req);
            holder[0] = (BatchReschedule) r.get("reschedule");
        });
        BatchReschedule rs = holder[0];
        long rsId = rs.getId();
        assertEquals("PENDING", rs.getApprovalStatus());
        assertTrue(rs.getLoaded());

        // PENDING 期间预计到达/承诺均不变，且没有通知、没有待办
        TransportBatch waiting = batchService.requireBatch(batch.getId());
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), waiting.getEstimatedArriveTime());
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), waiting.getPromisedArriveTime());
        assertTrue(rescheduleService.listNotifications(rsId).isEmpty());
        assertTrue(rescheduleService.listTodos(rsId).isEmpty());
        // 重复申请被拒
        assertThrows(BusinessException.class, () -> subjects.runAs(2L, DISPATCHER,
                () -> rescheduleService.apply(req)));
        // 待审批拦截发运
        assertThrows(BusinessException.class, () -> batchService.ship(batch.getId()));

        // 审批驳回：不生效，预计到达仍不变，无通知待办，可重新申请
        subjects.runAs(7L, INSPECTOR,
                () -> rescheduleService.reject(rsId, "驳回：先按原计划执行"));
        assertEquals("REJECTED", rescheduleMapper.selectById(rsId).getApprovalStatus());
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0),
                batchService.requireBatch(batch.getId()).getEstimatedArriveTime());
        assertTrue(rescheduleService.listNotifications(rsId).isEmpty());

        // 重新申请 -> 审批同意 -> 重算生效（次日 09:00）
        req.setReason("货源延迟，补正后重新申请");
        final BatchReschedule[] second = new BatchReschedule[1];
        subjects.runAs(2L, DISPATCHER, () -> second[0] = (BatchReschedule)
                rescheduleService.apply(req).get("reschedule"));
        assertEquals("PENDING", second[0].getApprovalStatus());
        subjects.runAs(7L, INSPECTOR,
                () -> rescheduleService.approve(second[0].getId(), "同意改期，注意在途温控"));
        TransportBatch done = batchService.requireBatch(batch.getId());
        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), done.getEstimatedArriveTime(),
                "周二21点+60分不可接收，顺延至周三(10-07)09:00");
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), done.getPromisedArriveTime(),
                "审批同意也只改预计到达，承诺时间不变");
        assertEquals(1, rescheduleService.listNotifications(second[0].getId()).size());
        assertEquals(3, rescheduleService.listTodos(second[0].getId()).size());
        // 已生效改期单不可重复审批
        assertThrows(BusinessException.class, () -> subjects.runAs(7L, INSPECTOR,
                () -> rescheduleService.approve(second[0].getId(), "再次同意")));
        // 审批通过后发运不再被改期卡口拦截（放行进入后续既有发运校验）
        // （probe 在 dispatchBatch 已绑定，可直接发运成功）
        TransportBatch shipped = batchService.ship(batch.getId());
        assertEquals(CcConstants.BATCH_SHIPPED, shipped.getStatus());
    }

    // ====================================================================
    // 4) 送达时窗完成（签收）后：规则调整只能新版本；节假日旧结论不可改；批次不得再改期
    // ====================================================================
    @Test
    void afterDeliveryCompleted_holidayOldConclusionsUntouchable_noMoreReschedule() {
        String org = "V10完成医院-" + System.nanoTime();
        long windowId = createWindowService(org, "09:00", "17:00");
        LocalDateTime planShip = LocalDateTime.of(2026, 10, 5, 8, 0);
        TransportBatch batch = dispatchBatch(org, planShip, 60, true);

        // 改期一次并生效（未装车 DIRECT），固化命中规则
        LocalDateTime newShip = LocalDateTime.of(2026, 10, 6, 20, 0);
        addCalendarService(windowId, "HOLIDAY", LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7),
                "消杀临时闭馆");
        com.evops.coldchain.dto.RescheduleApplyRequest req = new com.evops.coldchain.dto.RescheduleApplyRequest();
        req.setBatchId(batch.getId());
        req.setNewPlanShipTime(newShip);
        req.setReason("配送计划微调");
        BatchReschedule rs = (BatchReschedule) subjects.callAs(2L, DISPATCHER,
                () -> rescheduleService.apply(req).get("reschedule"));
        LocalDateTime estimatedAfter = rs.getNewEstimatedArriveTime();
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), estimatedAfter,
                "周二晚+60=周三08:00 前? 实际：10-06 20:00+60=21:00 -> 10-07 闭馆 -> 10-08 09:00");

        // 发运 -> 签收看快照闭环（probe 已在建样绑定；无交接/异常卡口）
        batchService.ship(batch.getId());
        SignRequest sign = new SignRequest();
        sign.setBatchId(batch.getId());
        sign.setSignBy("李收货");
        sign.setReceivedApprovalNo(batch.getApprovalNoSnap());
        sign.setReceivedPackageSpec(batch.getPackageSpecSnap());
        sign.setConfirmQty(batch.getShipQty());
        assertEquals(CcConstants.BATCH_SIGNED, batchService.sign(sign).getStatus());

        // 送达时窗完成后：批次不得再改期
        req.setReason("签收后试图改期");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rescheduleService.apply(req));
        assertTrue(ex.getMessage().contains("送达时窗已完成") || ex.getMessage().contains("签收闭环"));

        // 规则调整只能生成新版本（V2），不能动 V1；节假日规则只有作废留痕一条路
        DeliveryWindow window = windowMapper.selectById(windowId);
        assertEquals(1, window.getCurrentVersion());
        com.evops.coldchain.dto.WindowChangeRequest change = new com.evops.coldchain.dto.WindowChangeRequest();
        change.setReceiverOrg(org);
        change.setWeekdays(weekdaySlots("08:30", "12:00"));
        change.setChangeReason("完成后作息调整");
        subjects.runAs(2L, DISPATCHER, () -> deliveryWindowService.change(windowId, change));
        assertEquals(2, windowMapper.selectById(windowId).getCurrentVersion());

        // 历史改期单旧结论仍指向 V1 与当时的预计到达，不被新版本改写
        BatchReschedule historyRs = rescheduleMapper.selectById(rs.getId());
        assertEquals(1, historyRs.getWindowVersionNo());
        assertEquals(estimatedAfter, historyRs.getNewEstimatedArriveTime());
        // 已固化承诺同样不变
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0),
                batchService.requireBatch(batch.getId()).getPromisedArriveTime());
    }

    // ====================================================================
    // 5) Shiro 权限点：无时窗维护权/改期申请权/审批权 -> 403
    // ====================================================================
    @Test
    void shiroPermissions_denyWrongRoles() {
        String org = "V10权限医院-" + System.nanoTime();
        long windowId = createWindowHttp(org, "09:00", "17:00");
        LocalDateTime planShip = LocalDateTime.of(2026, 10, 5, 8, 0);
        TransportBatch batch = dispatchBatch(org, planShip, 60, false);

        // receiver 无时窗维护权：同路径 GET 只读放行、POST 调整在服务层二次校验被拒
        assertTrue(get(RECEIVER, RECPW, "/delivery-windows/" + windowId + "/versions").isArray(),
                "工作日版本链只读应对所有登录用户开放");
        Map<String, Object> change = windowBody(org, "10:00", "16:00");
        change.put("changeReason", "越权调整");
        JsonNode denied = post(RECEIVER, RECPW, "/delivery-windows/" + windowId + "/versions", change);
        assertFalse(denied.path("success").asBoolean());
        assertTrue(denied.path("message").asText().contains("Forbidden"), denied.toString());
        // receiver 无改期申请权（专用 POST 路径在 Shiro 链上按权限点拦截）
        Map<String, Object> applyBody = new LinkedHashMap<>();
        applyBody.put("batchId", batch.getId());
        applyBody.put("newPlanShipTime", "2026-10-06T20:00:00");
        applyBody.put("reason", "越权改期");
        assertEquals(403, raw(RECEIVER, RECPW, HttpMethod.POST, "/batches/reschedules", applyBody).status);

        // 先装车，再由 dispatcher 发起 PENDING 改期
        subjects.runAs(2L, DISPATCHER, () -> {
            com.evops.coldchain.dto.BatchLoadRequest load = new com.evops.coldchain.dto.BatchLoadRequest();
            load.setBatchId(batch.getId());
            rescheduleService.load(load);
        });
        JsonNode applyResp = post(DISPATCHER, DISPW, "/batches/reschedules", applyBody);
        long rsId = applyResp.path("data").path("reschedule").path("id").asLong();
        assertEquals("PENDING", applyResp.path("data").path("reschedule").path("approvalStatus").asText());
        // PENDING 期间重复申请被业务拒绝（success=false，消息提示停留待审批）
        JsonNode again = post(DISPATCHER, DISPW, "/batches/reschedules", applyBody);
        assertFalse(again.path("success").asBoolean());
        assertTrue(again.path("message").asText().contains("停留待审批"));

        // dispatcher 无审批权：403
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("reviewNote", "调度自批");
        assertEquals(403, raw(DISPATCHER, DISPW, HttpMethod.POST,
                "/batches/reschedules/" + rsId + "/approve", review).status);
        // inspector 审批同意成功
        review.put("reviewNote", "监管同意");
        assertTrue(post(INSPECTOR, INSPW, "/batches/reschedules/" + rsId + "/approve", review)
                .path("success").asBoolean());
    }

    // ---------------- 建样与 HTTP 辅助 ----------------

    private long createWindowHttp(String org, String open, String close) {
        return post(DISPATCHER, DISPW, "/delivery-windows/create", windowBody(org, open, close))
                .path("data").path("id").asLong();
    }

    private long createWindowService(String org, String open, String close) {
        com.evops.coldchain.dto.WindowCreateRequest req = new com.evops.coldchain.dto.WindowCreateRequest();
        req.setReceiverOrg(org);
        req.setReceiverName("V10医院");
        req.setWeekdays(weekdaySlots(open, close));
        return subjects.callAs(2L, DISPATCHER, () -> deliveryWindowService.create(req).getId());
    }

    private List<com.evops.coldchain.dto.WindowCreateRequest.WeekdaySlot> weekdaySlots(String open, String close) {
        List<com.evops.coldchain.dto.WindowCreateRequest.WeekdaySlot> days = new ArrayList<>();
        for (int w = 1; w <= 5; w++) {
            com.evops.coldchain.dto.WindowCreateRequest.WeekdaySlot day =
                    new com.evops.coldchain.dto.WindowCreateRequest.WeekdaySlot();
            day.setWeekday(w);
            com.evops.coldchain.dto.WindowCreateRequest.Slot slot =
                    new com.evops.coldchain.dto.WindowCreateRequest.Slot();
            slot.setOpenTime(open);
            slot.setCloseTime(close);
            day.setSlots(java.util.Collections.singletonList(slot));
            days.add(day);
        }
        return days;
    }

    private void addCalendarService(long windowId, String type, LocalDate start, LocalDate end, String reason) {
        subjects.runAs(2L, DISPATCHER, () -> {
            com.evops.coldchain.dto.WindowCalendarRequest c = new com.evops.coldchain.dto.WindowCalendarRequest();
            c.setRuleType(type);
            c.setStartDate(start);
            c.setEndDate(end);
            c.setReason(reason);
            deliveryWindowService.addCalendarRule(windowId, c);
        });
    }

    /**
     * 建药品/发货单（带计划发运）/车辆（withProbe 时建有效探头并绑定）/派车（带在途时长）。
     * 派车即按收货机构时窗固化原承诺送达时间。
     */
    private TransportBatch dispatchBatch(String org, LocalDateTime planShip, int transitMinutes,
                                         boolean withProbe) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V10疫苗-" + nano);
        dc.setApprovalNo("国药准字V10-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg(org);
        oc.setShipQty(10);
        oc.setPlanShipTime(planShip);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测10·" + Math.abs(nano % 1000000));
        vr.setCarrierName("V10冰链物流");
        vr.setDriverName("赵冷链");
        vr.setDriverPhone("13600001010");
        Long vehicleId = vehicleService.create(vr).getId();

        if (withProbe) {
            ProbeCreateRequest pc = new ProbeCreateRequest();
            pc.setSerialNo("SN-V10-" + nano);
            pc.setModel("TP-300");
            pc.setRangeMin(new BigDecimal("-40.00"));
            pc.setRangeMax(new BigDecimal("60.00"));
            pc.setCalibrateOrg("华东计量院");
            pc.setValidUntil(LocalDateTime.now().plusYears(1));
            pc.setCertNo("CERT-V10-" + nano);
            pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
            pc.setCertFileName(nano + ".txt");
            pc.setCertFileContent("V10证书-" + nano);
            long probeId = probeService.createProbe(pc).getId();
            ProbeBindRequest pb = new ProbeBindRequest();
            pb.setRequestId("v10bind-" + nano);
            pb.setVehicleId(vehicleId);
            pb.setProbeId(probeId);
            probeService.bindIdempotent(pb);
        }

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        dr.setTransitMinutes(transitMinutes);
        return batchService.dispatch(dr);
    }

    private static Map<String, Object> windowBody(String org, String open, String close) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiverOrg", org);
        body.put("receiverName", "V10医院");
        List<Map<String, Object>> days = new ArrayList<>();
        for (int w = 1; w <= 5; w++) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("weekday", w);
            day.put("slots", java.util.Collections.singletonList(
                    mapOf("openTime", open, "closeTime", close)));
            days.add(day);
        }
        body.put("weekdays", days);
        return body;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode post(String user, String pw, String path, Object body) {
        HttpResp r = raw(user, pw, HttpMethod.POST, path, body);
        if (r.status != 200) {
            throw new AssertionError("期望 200，实际 " + r.status + "：" + r.body);
        }
        return readTree(r.body);
    }

    private JsonNode get(String user, String pw, String path) {
        HttpResp r = raw(user, pw, HttpMethod.GET, path, null);
        if (r.status != 200) {
            throw new AssertionError("GET " + path + " 期望 200，实际 " + r.status + "：" + r.body);
        }
        return readTree(r.body).path("data");
    }

    private HttpResp raw(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = http.withBasicAuth(user, pw)
                    .exchange("http://localhost:" + port + BASE + path, method, entity, String.class);
            return new HttpResp(resp.getStatusCodeValue(), resp.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return new HttpResp(e.getRawStatusCode(), e.getResponseBodyAsString());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static class HttpResp {
        final int status;
        final String body;

        HttpResp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
