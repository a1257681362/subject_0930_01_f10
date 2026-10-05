package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalTime;

/**
 * 工作日可接收区间行（V10，只追加）：属于某个工作日区间版本。
 * weekday 取 ISO-8601（1=周一 … 7=周日）；同一工作日可维护多个相邻区间（如午休断开的上下午）。
 * 区间行随版本只追加，历史版本的区间原值永不修改。
 */
@TableName("t_cc_delivery_window_weekday")
public class DeliveryWindowWeekday extends CreateOnlyEntity {
    private Long versionId;
    private Integer weekday;
    private Integer slotSeq;
    private LocalTime openTime;
    private LocalTime closeTime;
    private String remark;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Integer getWeekday() { return weekday; }
    public void setWeekday(Integer weekday) { this.weekday = weekday; }
    public Integer getSlotSeq() { return slotSeq; }
    public void setSlotSeq(Integer slotSeq) { this.slotSeq = slotSeq; }
    public LocalTime getOpenTime() { return openTime; }
    public void setOpenTime(LocalTime openTime) { this.openTime = openTime; }
    public LocalTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalTime closeTime) { this.closeTime = closeTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
