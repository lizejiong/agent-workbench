package com.lzj.rag.backend.rag.service.bo;

import com.lzj.rag.backend.search.service.bo.SearchBO;
import java.util.UUID;

public record RagRetrieveBO(SearchBO search, UUID conversationId) {}
