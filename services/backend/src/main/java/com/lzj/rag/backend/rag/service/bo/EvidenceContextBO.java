package com.lzj.rag.backend.rag.service.bo;

import java.util.List;

public record EvidenceContextBO(List<EvidenceSnapshotBO> evidence,String modelContext,long estimatedTokens,
        int inputTokenBudget,boolean estimated,boolean truncated,String strategyVersion,String contentHash) {}
