package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 改期承运通知（V10，只追加）。
 * 每次生效改期向承运方逐次追加一行；发送失败留 failReason，重试新增行且 attempt_no 递增，
 * 历史成功/失败行原样保留（与异常升级通知同一口径）。
 */
@TableName("t_cc_reschedule_notify")
public class RescheduleNotify extends CreateOnlyEntity {
    private Long rescheduleId;
    private Long batchId;
    private Integer attemptNo;
    private String targetName;
    private String targetPhone;
    /** SITE / WEBHOOK */
    private String channel;
    /** SUCCESS / FAILED */
    private String sendStatus;
    private String failReason;
    private String content;
    private LocalDateTime notifyTime;

    public Long getRescheduleId() { return rescheduleId; }
    public void setRescheduleId(Long rescheduleId) { this.rescheduleId = rescheduleId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getTargetPhone() { return targetPhone; }
    public void setTargetPhone(String targetPhone) { this.targetPhone = targetPhone; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getNotifyTime() { return notifyTime; }
    public void setNotifyTime(LocalDateTime notifyTime) { this.notifyTime = notifyTime; }
}
