package com.lzj.rag.backend.rag.service.bo;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;

public class AnswerFailure extends KnowledgeBaseException {
    private final AnswerRunBO run;
    public AnswerFailure(int status,String code,AnswerRunBO run){super(status,code,"Answer run did not complete; inspect the authorized run status");this.run=run;}
    public AnswerRunBO run(){return run;}
}
