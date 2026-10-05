package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 批次装车登记请求（V10）。
 * 登记装车时间后批次“已进入装车状态”，此后改期必须审批；装车事实只进不退，不提供撤销装车路径。
 */
public class BatchLoadRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 装车时间（不传取当前时间） */
    private LocalDateTime loadTime;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getLoadTime() { return loadTime; }
    public void setLoadTime(LocalDateTime loadTime) { this.loadTime = loadTime; }
}
