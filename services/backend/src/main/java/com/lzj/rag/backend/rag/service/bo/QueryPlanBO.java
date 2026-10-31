package com.lzj.rag.backend.rag.service.bo;

import java.util.List;
import java.util.UUID;

public record QueryPlanBO(String originalQuery, String effectiveQuery, List<String> subQueries,
                          List<UUID> kbIds, boolean needsClarification, String clarificationQuestion,
                          boolean degraded, String fallbackReason, String promptVersion,
                          String model, String termVersion, String promptHash) {}
