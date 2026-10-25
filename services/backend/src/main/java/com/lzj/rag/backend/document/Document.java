package com.lzj.rag.backend.document;

import com.baomidou.mybatisplus.annotation.*;
import com.lzj.rag.backend.persistence.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName(value = "kb_document", autoResultMap = true)
public class Document {
    @TableId(type = IdType.INPUT) private UUID id;
    private UUID kbId;
    private String tenantId;
    private String ownerId;
    private String name;
    private String originalFilename;
    private String sourceType;
    private String sourceUrl;
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private String contentHash;
    private Boolean enabled;
    private String processStatus;
    @TableField(typeHandler = JsonbTypeHandler.class) private String ingestionSpec;
    private Long configRevision;
    private Long activeGeneration;
    private Long chunkCount;
    private Long version;
    private Long manualRevision;
    private Boolean hasManualChanges;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime lastIngestedAt;
    private OffsetDateTime deletedAt;
}
