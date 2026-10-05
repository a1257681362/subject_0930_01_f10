package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 批次改期申请（V10）。
 * 以新的计划发运时间重算预计到达；原承诺送达时间不改写。
 * 批次已进入装车状态（load_time 非空）时本申请停留 PENDING，必须审批同意后才生效。
 */
public class RescheduleApplyRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotNull(message = "新的计划发运时间不能为空")
    private LocalDateTime newPlanShipTime;
    /** 改期原因 */
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getNewPlanShipTime() { return newPlanShipTime; }
    public void setNewPlanShipTime(LocalDateTime newPlanShipTime) { this.newPlanShipTime = newPlanShipTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
