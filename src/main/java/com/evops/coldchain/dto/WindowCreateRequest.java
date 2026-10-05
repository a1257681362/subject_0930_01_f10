package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 收货机构送达时窗建档请求（V10）。
 * 建档即发布工作日区间 V1；节假日与临时闭馆不在此请求内，走独立的日历规则接口保存。
 */
public class WindowCreateRequest {
    @NotNull(message = "收货机构不能为空")
    private String receiverOrg;
    private String receiverName;
    private String contactName;
    private String contactPhone;
    /** 工作日可接收区间（至少一个工作日一段），按工作日分组 */
    @NotEmpty(message = "工作日可接收区间不能为空")
    @Valid
    private List<WeekdaySlot> weekdays;
    private String remark;

    /** 一个工作日的可接收区间（可多段） */
    public static class WeekdaySlot {
        @NotNull(message = "星期(1=周一…7=周日)不能为空")
        private Integer weekday;
        @NotEmpty(message = "区间列表不能为空")
        @Valid
        private List<Slot> slots;

        public Integer getWeekday() { return weekday; }
        public void setWeekday(Integer weekday) { this.weekday = weekday; }
        public List<Slot> getSlots() { return slots; }
        public void setSlots(List<Slot> slots) { this.slots = slots; }
    }

    /** 单个可接收区间 [openTime, closeTime)，形如 09:00 / 11:30 */
    public static class Slot {
        @NotNull(message = "区间开始时刻不能为空")
        private String openTime;
        @NotNull(message = "区间截止时刻不能为空")
        private String closeTime;
        private String remark;

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }
        public String getCloseTime() { return closeTime; }
        public void setCloseTime(String closeTime) { this.closeTime = closeTime; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public List<WeekdaySlot> getWeekdays() { return weekdays; }
    public void setWeekdays(List<WeekdaySlot> weekdays) { this.weekdays = weekdays; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
