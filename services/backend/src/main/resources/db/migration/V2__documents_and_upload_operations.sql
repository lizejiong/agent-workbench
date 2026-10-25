ALTER TABLE kb_knowledge_base
    ADD COLUMN embedding_profile_id VARCHAR(128),
    ADD COLUMN embedding_model_id VARCHAR(128),
    ADD COLUMN embedding_provider VARCHAR(64),
    ADD COLUMN embedding_dimension INTEGER,
    ADD COLUMN embedding_config_revision CHAR(64),
    ADD COLUMN embedding_config_json JSONB,
    ADD COLUMN vector_namespace VARCHAR(160),
    ADD COLUMN created_by VARCHAR(128),
    ADD COLUMN updated_by VARCHAR(128),
    ADD CONSTRAINT kb_base_binding_ck CHECK (
        (embedding_profile_id IS NULL AND embedding_model_id IS NULL AND embedding_provider IS NULL AND embedding_dimension IS NULL
         AND embedding_config_revision IS NULL AND embedding_config_json IS NULL AND vector_namespace IS NULL)
        OR (embedding_profile_id IS NOT NULL AND embedding_model_id IS NOT NULL AND embedding_provider IS NOT NULL AND embedding_dimension IS NOT NULL AND embedding_dimension = 1024
         AND embedding_config_revision IS NOT NULL AND embedding_config_json IS NOT NULL AND vector_namespace IS NOT NULL)),
    ADD CONSTRAINT kb_base_scope_uq UNIQUE (id, tenant_id, owner_id),
    ADD CONSTRAINT kb_base_namespace_uq UNIQUE (tenant_id, owner_id, vector_namespace);
UPDATE kb_knowledge_base SET created_by = owner_id, updated_by = owner_id;
ALTER TABLE kb_knowledge_base ALTER COLUMN created_by SET NOT NULL, ALTER COLUMN updated_by SET NOT NULL;

CREATE TABLE kb_document (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL CHECK (length(btrim(name)) > 0),
    original_filename VARCHAR(255) NOT NULL,
    source_type VARCHAR(16) NOT NULL DEFAULT 'UPLOAD' CHECK (source_type = 'UPLOAD'),
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    mime_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    content_hash CHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    process_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (process_status = 'PENDING'),
    ingestion_spec JSONB NOT NULL,
    config_revision BIGINT NOT NULL DEFAULT 1 CHECK (config_revision > 0),
    active_generation BIGINT,
    chunk_count BIGINT NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT kb_document_scope_fk FOREIGN KEY (kb_id, tenant_id, owner_id)
        REFERENCES kb_knowledge_base(id, tenant_id, owner_id)
);
CREATE UNIQUE INDEX kb_document_name_uq ON kb_document(kb_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX kb_document_page_idx ON kb_document(tenant_id, owner_id, kb_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Written before external PUT. Recovery never deletes an object referenced by a committed document.
CREATE TABLE kb_upload_operation (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('UPLOADING','COMPLETED','CLEANUP_PENDING','CLEANED')),
    lease_until TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    claim_token UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT kb_upload_scope_fk FOREIGN KEY (kb_id, tenant_id, owner_id)
        REFERENCES kb_knowledge_base(id, tenant_id, owner_id),
    UNIQUE (kb_id, tenant_id, owner_id, idempotency_key)
);
CREATE INDEX kb_upload_recovery_idx ON kb_upload_operation(status, next_run_at, lease_until);
