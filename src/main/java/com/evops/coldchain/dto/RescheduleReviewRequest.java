package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 已装车批次改期审批请求（同意生效 / 驳回不生效，均必须留意见） */
public class RescheduleReviewRequest {
    @NotBlank(message = "审批意见不能为空")
    private String reviewNote;

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
