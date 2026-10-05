package com.evops.coldchain.dto;

/**
 * 工作日区间规则调整请求（V10）：只能生成新版本，不改动任何节假日规则旧行与旧版本结论。
 * 入参结构与建档一致：整组工作日区间作为新版本全量发布（旧版本置 SUPERSEDED 原样保留）。
 */
public class WindowChangeRequest extends WindowCreateRequest {
    /** 调整原因（必填，随新版本留痕） */
    private String changeReason;

    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
