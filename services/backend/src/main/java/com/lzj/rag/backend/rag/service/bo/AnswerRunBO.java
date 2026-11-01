package com.lzj.rag.backend.rag.service.bo;

import java.time.Instant;
import java.util.UUID;

public record AnswerRunBO(UUID id,UUID conversationId,String requestId,String question,String status,Instant deadline,
        String answerText,RagAnswerBO result,String errorCode,Instant createdAt,Instant updatedAt) {}
