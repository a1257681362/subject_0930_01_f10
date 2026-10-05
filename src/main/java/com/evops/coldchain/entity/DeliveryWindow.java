package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 收货机构送达时窗主档（V10）：一个收货机构一行。
 * 工作日可接收区间按版本维护（见 {@link DeliveryWindowVersion}/{@link DeliveryWindowWeekday}）；
 * 节假日与临时闭馆规则独立成表（见 {@link DeliveryWindowCalendar}），不随工作日版本调整改写。
 */
@TableName("t_cc_delivery_window")
public class DeliveryWindow extends BaseEntity {
    private String windowNo;
    private String receiverOrg;
    private String receiverName;
    private String contactName;
    private String contactPhone;
    private Integer currentVersion;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getWindowNo() { return windowNo; }
    public void setWindowNo(String windowNo) { this.windowNo = windowNo; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public Integer getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
