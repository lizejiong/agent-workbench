package com.lzj.rag.backend.rag.service.bo;

import com.lzj.rag.backend.search.service.bo.RetrievalBO;

public record RagRetrievalBO(String status, QueryPlanBO query, RetrievalBO retrieval) {}
