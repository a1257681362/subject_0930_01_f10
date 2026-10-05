package com.evops.coldchain.controller;

import com.evops.coldchain.dto.BatchLoadRequest;
import com.evops.coldchain.dto.RescheduleApplyRequest;
import com.evops.coldchain.dto.RescheduleNotifyRetryRequest;
import com.evops.coldchain.dto.RescheduleReviewRequest;
import com.evops.coldchain.dto.RescheduleTodoDoneRequest;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.RescheduleNotify;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.service.BatchRescheduleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运输批次装车与改期（V10）。
 *
 * - 装车后批次“已进入装车状态”，改期必须审批：申请停留 PENDING，同意后才重算预计到达；
 * - 改期只重算预计到达，原承诺送达时间任何路径都不改写；
 * - 生效改期返回/可查受影响温控区间、承运通知与待办责任人；通知可重试、待办逐项办结。
 */
@RestController
@RequestMapping("/api/cold-chain/batches")
public class BatchRescheduleController {

    private final BatchRescheduleService rescheduleService;

    public BatchRescheduleController(BatchRescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    /** 装车登记（事实只进不退；此后改期必须审批） */
    @PostMapping("/load")
    public ApiResponse<TransportBatch> load(@Valid @RequestBody BatchLoadRequest req) {
        return ApiResponse.ok(rescheduleService.load(req));
    }

    /** 改期影响预览（重算预计到达+受影响温控区间，不落库） */
    @GetMapping("/{id}/reschedule-preview")
    public ApiResponse<Map<String, Object>> preview(@PathVariable("id") Long id,
                                                    @RequestParam("newPlanShipTime") String newPlanShipTime) {
        return ApiResponse.ok(rescheduleService.preview(id, LocalDateTime.parse(newPlanShipTime)));
    }

    /** 改期申请（未装车直接生效；已装车停留待审批） */
    @PostMapping("/reschedules")
    public ApiResponse<Map<String, Object>> apply(@Valid @RequestBody RescheduleApplyRequest req) {
        return ApiResponse.ok(rescheduleService.apply(req));
    }

    /** 同意已装车批次改期（重算生效、发承运通知、生待办；承诺时间仍不改） */
    @PostMapping("/reschedules/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable("id") Long id,
                                                    @Valid @RequestBody RescheduleReviewRequest req) {
        return ApiResponse.ok(rescheduleService.approve(id, req.getReviewNote()));
    }

    /** 驳回已装车批次改期（不生效、不通知、不生待办；预计到达与承诺均不变） */
    @PostMapping("/reschedules/{id}/reject")
    public ApiResponse<BatchReschedule> reject(@PathVariable("id") Long id,
                                               @Valid @RequestBody RescheduleReviewRequest req) {
        return ApiResponse.ok(rescheduleService.reject(id, req.getReviewNote()));
    }

    /** 再次通知承运方（新增通知行，attemptNo 递增） */
    @PostMapping("/reschedules/{id}/notify-retry")
    public ApiResponse<RescheduleNotify> retryNotify(@PathVariable("id") Long id,
                                                     @Valid @RequestBody RescheduleNotifyRetryRequest req) {
        return ApiResponse.ok(rescheduleService.retryNotify(id, req.getRetryReason()));
    }

    /** 改期待办逐项办结（完成事实只进不退） */
    @PostMapping("/reschedules/todos/{todoId}/done")
    public ApiResponse<RescheduleTodo> completeTodo(@PathVariable("todoId") Long todoId,
                                                    @Valid @RequestBody RescheduleTodoDoneRequest req) {
        return ApiResponse.ok(rescheduleService.completeTodo(todoId, req.getDoneNote()));
    }

    /** 改期单详情 */
    @GetMapping("/reschedules/{id}")
    public ApiResponse<BatchReschedule> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(rescheduleService.requireReschedule(id));
    }

    /** 某批次完整改期链（历史版本可追查） */
    @GetMapping("/{id}/reschedules")
    public ApiResponse<List<BatchReschedule>> listByBatch(@PathVariable("id") Long id) {
        return ApiResponse.ok(rescheduleService.listByBatch(id));
    }

    /** 某张改期单的承运通知记录（逐次追加） */
    @GetMapping("/reschedules/{id}/notifications")
    public ApiResponse<List<RescheduleNotify>> notifications(@PathVariable("id") Long id) {
        return ApiResponse.ok(rescheduleService.listNotifications(id));
    }

    /** 某张改期单的待办责任人 */
    @GetMapping("/reschedules/{id}/todos")
    public ApiResponse<List<RescheduleTodo>> todos(@PathVariable("id") Long id) {
        return ApiResponse.ok(rescheduleService.listTodos(id));
    }

    /** 全部未办结改期待办（责任人工作台） */
    @GetMapping("/reschedules/todos/open")
    public ApiResponse<List<RescheduleTodo>> openTodos() {
        return ApiResponse.ok(rescheduleService.listOpenTodos());
    }
}
