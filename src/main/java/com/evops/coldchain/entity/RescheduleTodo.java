package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 改期待办责任人（V10）。
 * 生效改期按受影响环节生成待办：CARRIER 承运/驾驶员（调整在途温控计划）、
 * DISPATCHER 调度（更新调度计划）、RECEIVER 收货岗（按新预计到达预留收货月台/人力）。
 * 待办只追加；完成只追加完成字段（事实只进不退），不改原待办内容。驳回的改期不产生待办。
 */
@TableName("t_cc_reschedule_todo")
public class RescheduleTodo extends BaseEntity {
    private Long rescheduleId;
    private Long batchId;
    private Integer todoSeq;
    /** CARRIER / DISPATCHER / RECEIVER */
    private String ownerRole;
    private String ownerName;
    private String actionDesc;
    private LocalDateTime dueTime;
    /** OPEN / DONE */
    private String status;
    private Long doneBy;
    private String doneName;
    private LocalDateTime doneTime;
    private String doneNote;

    public Long getRescheduleId() { return rescheduleId; }
    public void setRescheduleId(Long rescheduleId) { this.rescheduleId = rescheduleId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getTodoSeq() { return todoSeq; }
    public void setTodoSeq(Integer todoSeq) { this.todoSeq = todoSeq; }
    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getActionDesc() { return actionDesc; }
    public void setActionDesc(String actionDesc) { this.actionDesc = actionDesc; }
    public LocalDateTime getDueTime() { return dueTime; }
    public void setDueTime(LocalDateTime dueTime) { this.dueTime = dueTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDoneBy() { return doneBy; }
    public void setDoneBy(Long doneBy) { this.doneBy = doneBy; }
    public String getDoneName() { return doneName; }
    public void setDoneName(String doneName) { this.doneName = doneName; }
    public LocalDateTime getDoneTime() { return doneTime; }
    public void setDoneTime(LocalDateTime doneTime) { this.doneTime = doneTime; }
    public String getDoneNote() { return doneNote; }
    public void setDoneNote(String doneNote) { this.doneNote = doneNote; }
}
