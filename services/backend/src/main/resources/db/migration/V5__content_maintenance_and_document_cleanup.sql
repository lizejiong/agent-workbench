ALTER TABLE kb_document
    ADD COLUMN manual_revision BIGINT NOT NULL DEFAULT 0 CHECK(manual_revision>=0),
    ADD COLUMN has_manual_changes BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE kb_ingestion_job
    ADD COLUMN confirm_overwrite_edited BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN expected_document_version BIGINT CHECK(expected_document_version>0);
ALTER TABLE kb_generation DROP CONSTRAINT kb_generation_chunk_count_check;
ALTER TABLE kb_generation
    ADD CONSTRAINT kb_generation_chunk_count_check CHECK(chunk_count>=0),
    ADD COLUMN next_chunk_index INTEGER NOT NULL DEFAULT 0 CHECK(next_chunk_index>=0);
UPDATE kb_generation g SET next_chunk_index=COALESCE((SELECT max(c.chunk_index)+1 FROM kb_chunk c
    WHERE c.document_id=g.document_id AND c.generation=g.generation),0);
ALTER TABLE kb_chunk ADD COLUMN token_count_estimated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN created_by VARCHAR(128), ADD COLUMN updated_by VARCHAR(128);
UPDATE kb_chunk SET created_by=owner_id,updated_by=owner_id;
ALTER TABLE kb_chunk ALTER COLUMN created_by SET NOT NULL, ALTER COLUMN updated_by SET NOT NULL;

CREATE TABLE kb_content_audit (
    id UUID PRIMARY KEY, document_id UUID NOT NULL REFERENCES kb_document(id), kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL, actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL, chunk_ids JSONB NOT NULL, before_summary JSONB NOT NULL,
    after_summary JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE INDEX kb_content_audit_document_idx ON kb_content_audit(document_id,created_at,id);

CREATE TABLE kb_document_cleanup (
    id UUID PRIMARY KEY, document_id UUID NOT NULL UNIQUE, kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL, object_key VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    stage VARCHAR(16) NOT NULL DEFAULT 'DATABASE' CHECK(stage IN ('DATABASE','OBJECT')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0), max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 10),
    next_run_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, lease_owner VARCHAR(128), lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0, error_code VARCHAR(64), error_message VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMPTZ,
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE INDEX kb_document_cleanup_due_idx ON kb_document_cleanup(status,next_run_at,lease_until);
