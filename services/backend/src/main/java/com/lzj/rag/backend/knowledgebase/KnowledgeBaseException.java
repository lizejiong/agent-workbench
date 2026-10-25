package com.lzj.rag.backend.knowledgebase;

public class KnowledgeBaseException extends RuntimeException {
    private final int status;
    private final String code;

    public KnowledgeBaseException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
