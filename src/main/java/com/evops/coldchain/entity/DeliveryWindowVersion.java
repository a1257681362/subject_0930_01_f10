package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 送达时窗工作日区间版本（V10）。
 * 规则调整只能追加新版本：旧版本置 SUPERSEDED，版本行与其工作日区间行原值永不修改；
 * 派车/改期引用到的版本号永久可追（与药品档案版本、路线季节版本同一口径）。
 */
@TableName("t_cc_delivery_window_version")
public class DeliveryWindowVersion extends BaseEntity {
    private Long windowId;
    private Integer versionNo;
    private String changeReason;
    private LocalDateTime publishTime;
    /** EFFECTIVE / SUPERSEDED（工作日区间调整直接发布新版本，无 DRAFT 态） */
    private String status;

    public Long getWindowId() { return windowId; }
    public void setWindowId(Long windowId) { this.windowId = windowId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
