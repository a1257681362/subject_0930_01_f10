package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 节假日 / 临时闭馆规则作废请求（只允许作废留痕，不提供原地修改/删除） */
public class WindowCalendarVoidRequest {
    @NotBlank(message = "作废原因不能为空")
    private String voidReason;

    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
}
