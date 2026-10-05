package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 节假日 / 临时闭馆独立规则新增请求（V10）。
 * 日历规则与时窗工作日版本序列脱钩保存：新增不产生新版本，工作日区间调整也不改写本规则。
 */
public class WindowCalendarRequest {
    /** HOLIDAY 节假日 / TEMP_CLOSED 临时闭馆 */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;
    @NotNull(message = "截止日期不能为空")
    private LocalDate endDate;
    @NotBlank(message = "节假日名称/闭馆原因不能为空")
    private String reason;

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
