CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='vector' AND n.nspname='public')
        THEN RAISE EXCEPTION 'pgvector must be installed in public schema'; END IF;
END $$;
ALTER TABLE kb_knowledge_base
    ADD COLUMN embedding_ai_config_revision CHAR(64),
    ADD CONSTRAINT kb_base_ai_binding_ck CHECK(embedding_ai_config_revision IS NULL OR embedding_model_id IS NOT NULL),
    ADD CONSTRAINT kb_base_vector_identity_uq UNIQUE(id,tenant_id,owner_id,vector_namespace,embedding_model_id,embedding_config_revision,embedding_ai_config_revision);

ALTER TABLE kb_document DROP CONSTRAINT kb_document_process_status_check;
ALTER TABLE kb_document
    ADD CONSTRAINT kb_document_process_status_check CHECK (process_status IN ('PENDING','PROCESSING','READY','FAILED')),
    ADD CONSTRAINT kb_document_scope_uq UNIQUE(id,kb_id,tenant_id,owner_id),
    ADD COLUMN next_generation BIGINT NOT NULL DEFAULT 0 CHECK(next_generation >= 0),
    ADD COLUMN latest_job_id UUID;

CREATE TABLE kb_ingestion_job (
    id UUID PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    document_id UUID NOT NULL,
    kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    document_config_revision BIGINT NOT NULL,
    document_version BIGINT NOT NULL,
    embedding_config_revision CHAR(64) NOT NULL,
    ai_config_revision CHAR(64),
    target_generation BIGINT NOT NULL CHECK(target_generation > 0),
    object_key VARCHAR(1024) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    ingestion_spec JSONB NOT NULL,
    job_type VARCHAR(32) NOT NULL DEFAULT 'INGEST' CHECK(job_type='INGEST'),
    status VARCHAR(32) NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED')),
    stage VARCHAR(32) NOT NULL CHECK(stage IN ('FETCH','PARSE','SPLIT','EMBED','PERSIST','PUBLISH')),
    attempts INTEGER NOT NULL DEFAULT 1 CHECK(attempts=1),
    max_attempts INTEGER NOT NULL DEFAULT 1 CHECK(max_attempts=1),
    next_run_at TIMESTAMPTZ,
    lease_owner VARCHAR(128) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    claim_token BIGINT NOT NULL DEFAULT 1,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    UNIQUE(document_id,target_generation),
    UNIQUE(document_id,id),
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE UNIQUE INDEX kb_ingestion_active_uq ON kb_ingestion_job(document_id) WHERE status='RUNNING';
CREATE INDEX kb_ingestion_expiry_idx ON kb_ingestion_job(lease_until) WHERE status='RUNNING';
ALTER TABLE kb_document ADD CONSTRAINT kb_document_latest_job_fk FOREIGN KEY(id,latest_job_id) REFERENCES kb_ingestion_job(document_id,id);

CREATE TABLE kb_ingestion_stage_log (
    job_id UUID NOT NULL REFERENCES kb_ingestion_job(id),
    attempt INTEGER NOT NULL DEFAULT 1,
    stage VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    duration_ms BIGINT CHECK(duration_ms>=0),
    status VARCHAR(32) NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED')),
    input_count BIGINT,
    input_unit VARCHAR(32),
    output_count BIGINT,
    output_unit VARCHAR(32),
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    PRIMARY KEY(job_id,attempt,stage)
);
CREATE TABLE kb_generation (
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    job_id UUID NOT NULL UNIQUE REFERENCES kb_ingestion_job(id),
    embedding_config_revision CHAR(64) NOT NULL,
    ai_config_revision CHAR(64) NOT NULL,
    chunk_count BIGINT NOT NULL CHECK(chunk_count>0),
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(document_id,generation),
    UNIQUE(document_id,generation,kb_id,tenant_id,owner_id),
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE TABLE kb_chunk (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    chunk_index INTEGER NOT NULL CHECK(chunk_index>=0),
    content TEXT NOT NULL CHECK(length(content)>0),
    embedding_text TEXT NOT NULL CHECK(length(embedding_text)>0),
    content_hash CHAR(64) NOT NULL,
    char_count INTEGER NOT NULL CHECK(char_count>0),
    token_count INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    edited BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json JSONB NOT NULL,
    embedding_model_id VARCHAR(128) NOT NULL,
    embedding_config_revision CHAR(64) NOT NULL,
    ai_config_revision CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id,generation,chunk_index),
    UNIQUE(id,kb_id,document_id,generation,tenant_id,owner_id),
    FOREIGN KEY(document_id,generation,kb_id,tenant_id,owner_id) REFERENCES kb_generation(document_id,generation,kb_id,tenant_id,owner_id)
);
CREATE TABLE kb_vector (
    chunk_id UUID PRIMARY KEY,
    kb_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    vector_namespace VARCHAR(160) NOT NULL,
    embedding_model_id VARCHAR(128) NOT NULL,
    embedding_config_revision CHAR(64) NOT NULL,
    ai_config_revision CHAR(64) NOT NULL,
    embedding public.vector(1024) NOT NULL CHECK(public.vector_norm(embedding)>0),
    metadata_json JSONB NOT NULL,
    FOREIGN KEY(chunk_id,kb_id,document_id,generation,tenant_id,owner_id) REFERENCES kb_chunk(id,kb_id,document_id,generation,tenant_id,owner_id) ON DELETE CASCADE,
    FOREIGN KEY(kb_id,tenant_id,owner_id,vector_namespace,embedding_model_id,embedding_config_revision,ai_config_revision)
        REFERENCES kb_knowledge_base(id,tenant_id,owner_id,vector_namespace,embedding_model_id,embedding_config_revision,embedding_ai_config_revision)
);
CREATE INDEX kb_vector_scope_idx ON kb_vector(tenant_id,owner_id,kb_id,vector_namespace,document_id,generation);
-- Exact cosine search first. No approximate index until recall/volume acceptance in retrieval stage.
