package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 批次改期单（V10，整单只追加）。
 *
 * 一张批次可多次改期，每次独立一张单。核心口径：
 * - 改期只重算预计到达（old/new_estimated_arrive_time），原承诺送达时间只做整单快照，
 *   任何审批/重算路径都不改写 t_cc_transport_batch.promised_arrive_time；
 * - 申请当刻已装车（loaded=TRUE）的批次必须审批：PENDING 不放行，APPROVED 后才生效；
 *   未装车（loaded=FALSE）直接生效（approval_status=DIRECT）；驳回 REJECTED 不生效、不产生通知待办；
 * - 重算采用的工作日区间版本、命中的节假日/临时闭馆规则ID随单快照，
 *   之后规则如何调整/作废，本单旧结论永久可追查。
 */
@TableName("t_cc_batch_reschedule")
public class BatchReschedule extends BaseEntity {
    private String rescheduleNo;
    private Long batchId;
    /** 申请当刻批次是否已进入装车状态（决定是否必须审批，随单固化） */
    private Boolean loaded;
    private String reason;
    private LocalDateTime oldPlanShipTime;
    private LocalDateTime newPlanShipTime;
    private LocalDateTime oldEstimatedArriveTime;
    private LocalDateTime newEstimatedArriveTime;
    /** 批次原承诺送达时间快照（永不被改写） */
    private LocalDateTime promisedArriveTimeSnap;
    private Long windowVersionId;
    private Integer windowVersionNo;
    /** 参与本次结论的有效节假日/临时闭馆规则ID（逗号分隔快照） */
    private String holidayRuleIds;
    private String calcDetail;
    /** DIRECT 直接生效 / PENDING 待审批 / APPROVED 同意已生效 / REJECTED 驳回未生效 */
    private String approvalStatus;
    private Long requestBy;
    private String requestName;
    private LocalDateTime requestTime;
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewNote;
    private LocalDateTime appliedTime;

    public String getRescheduleNo() { return rescheduleNo; }
    public void setRescheduleNo(String rescheduleNo) { this.rescheduleNo = rescheduleNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Boolean getLoaded() { return loaded; }
    public void setLoaded(Boolean loaded) { this.loaded = loaded; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getOldPlanShipTime() { return oldPlanShipTime; }
    public void setOldPlanShipTime(LocalDateTime oldPlanShipTime) { this.oldPlanShipTime = oldPlanShipTime; }
    public LocalDateTime getNewPlanShipTime() { return newPlanShipTime; }
    public void setNewPlanShipTime(LocalDateTime newPlanShipTime) { this.newPlanShipTime = newPlanShipTime; }
    public LocalDateTime getOldEstimatedArriveTime() { return oldEstimatedArriveTime; }
    public void setOldEstimatedArriveTime(LocalDateTime oldEstimatedArriveTime) { this.oldEstimatedArriveTime = oldEstimatedArriveTime; }
    public LocalDateTime getNewEstimatedArriveTime() { return newEstimatedArriveTime; }
    public void setNewEstimatedArriveTime(LocalDateTime newEstimatedArriveTime) { this.newEstimatedArriveTime = newEstimatedArriveTime; }
    public LocalDateTime getPromisedArriveTimeSnap() { return promisedArriveTimeSnap; }
    public void setPromisedArriveTimeSnap(LocalDateTime promisedArriveTimeSnap) { this.promisedArriveTimeSnap = promisedArriveTimeSnap; }
    public Long getWindowVersionId() { return windowVersionId; }
    public void setWindowVersionId(Long windowVersionId) { this.windowVersionId = windowVersionId; }
    public Integer getWindowVersionNo() { return windowVersionNo; }
    public void setWindowVersionNo(Integer windowVersionNo) { this.windowVersionNo = windowVersionNo; }
    public String getHolidayRuleIds() { return holidayRuleIds; }
    public void setHolidayRuleIds(String holidayRuleIds) { this.holidayRuleIds = holidayRuleIds; }
    public String getCalcDetail() { return calcDetail; }
    public void setCalcDetail(String calcDetail) { this.calcDetail = calcDetail; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public Long getRequestBy() { return requestBy; }
    public void setRequestBy(Long requestBy) { this.requestBy = requestBy; }
    public String getRequestName() { return requestName; }
    public void setRequestName(String requestName) { this.requestName = requestName; }
    public LocalDateTime getRequestTime() { return requestTime; }
    public void setRequestTime(LocalDateTime requestTime) { this.requestTime = requestTime; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public LocalDateTime getAppliedTime() { return appliedTime; }
    public void setAppliedTime(LocalDateTime appliedTime) { this.appliedTime = appliedTime; }
}
