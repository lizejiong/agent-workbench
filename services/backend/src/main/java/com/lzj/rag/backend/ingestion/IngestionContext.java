package com.lzj.rag.backend.ingestion;

import com.lzj.rag.backend.document.DocumentTypes.IngestionSpec;
import com.lzj.rag.backend.model.EmbeddingClient;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import java.time.Instant;
import java.util.UUID;

public record IngestionContext(UUID jobId,String requestId,UUID documentId,UUID kbId,KnowledgeBasePrincipal actor,
        long documentVersion,long configRevision,long generation,String objectKey,String originalFilename,
        String mimeType,long fileSize,String contentHash,IngestionSpec spec,EmbeddingClient.Binding binding,
        String namespace,Instant deadline,int attempt,long claimToken,String leaseOwner) { }
