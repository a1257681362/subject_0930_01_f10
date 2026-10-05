package com.evops.coldchain.dto;

/** 改期承运通知重试请求（失败留原因；重试新增通知行，attemptNo 递增） */
public class RescheduleNotifyRetryRequest {
    private String retryReason;

    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
}
