package com.lzj.rag.backend.chunk;

import com.baomidou.mybatisplus.annotation.*;
import com.lzj.rag.backend.persistence.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter @Setter
@TableName(value="kb_chunk",autoResultMap=true)
public class StoredChunk {
    @TableId(type=IdType.INPUT) private UUID id;
    private UUID kbId;
    private UUID documentId;
    private Long generation;
    private String tenantId;
    private String ownerId;
    private Integer chunkIndex;
    private String content;
    private String embeddingText;
    private String contentHash;
    private Integer charCount;
    private Integer tokenCount;
    private Boolean tokenCountEstimated;
    private Boolean enabled;
    private Boolean edited;
    @TableField(typeHandler=JsonbTypeHandler.class) private String metadataJson;
    private String embeddingModelId;
    private String embeddingConfigRevision;
    private String aiConfigRevision;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
