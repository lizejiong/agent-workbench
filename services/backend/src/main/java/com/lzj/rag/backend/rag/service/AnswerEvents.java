package com.lzj.rag.backend.rag.service;

import com.lzj.rag.backend.rag.service.bo.*;

/** 普通 JSON 和 SSE 共用问答流水线，边界数据由 Controller 转为用户视图。 */
public interface AnswerEvents {
    AnswerEvents NONE=new AnswerEvents() {};
    default void meta(AnswerRunBO run) {}
    default void retrieval(RagRetrievalBO retrieval) {}
    default void sources(EvidenceContextBO context) {}
    default void message(String text) {}
    default void error(String code) {}
    default void finish(AnswerRunBO run,boolean persisted) {}
    default void done() {}
}
