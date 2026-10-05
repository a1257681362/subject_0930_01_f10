package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDate;

/**
 * 节假日与临时闭馆独立规则（V10）。
 *
 * 与时窗工作日版本序列脱钩保存：新增/作废一条节假日或临时闭馆不产生工作日新版本；
 * 反过来，工作日区间规则调整出新版本时绝不改动本表任何一行。
 * 规则只追加：作废仅置 REVOKED + 作废原因/时间（留痕），不提供原地修改或删除路径，
 * 已被改期单快照引用（holiday_rule_ids）的旧结论永久可追查。
 */
@TableName("t_cc_delivery_window_calendar")
public class DeliveryWindowCalendar extends BaseEntity {
    private Long windowId;
    /** HOLIDAY 节假日 / TEMP_CLOSED 临时闭馆 */
    private String ruleType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    /** EFFECTIVE / REVOKED */
    private String status;
    private String voidReason;
    private java.time.LocalDateTime voidTime;

    public Long getWindowId() { return windowId; }
    public void setWindowId(Long windowId) { this.windowId = windowId; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public java.time.LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(java.time.LocalDateTime voidTime) { this.voidTime = voidTime; }
}
