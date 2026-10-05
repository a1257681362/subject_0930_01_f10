package com.evops.coldchain.dto;

/** 改期待办办结请求（完成事实只进不退，逐项留痕） */
public class RescheduleTodoDoneRequest {
    private String doneNote;

    public String getDoneNote() { return doneNote; }
    public void setDoneNote(String doneNote) { this.doneNote = doneNote; }
}
