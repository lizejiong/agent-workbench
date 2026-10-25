ALTER TABLE kb_document DROP CONSTRAINT kb_document_source_type_check;
ALTER TABLE kb_document ADD CONSTRAINT kb_document_source_type_check CHECK(source_type IN ('UPLOAD','URL'));
ALTER TABLE kb_document ADD COLUMN source_url VARCHAR(2048);
ALTER TABLE kb_document ADD CONSTRAINT kb_document_source_url_check CHECK((source_type='URL')=(source_url IS NOT NULL));

CREATE TABLE kb_refresh_schedule (
    document_id UUID PRIMARY KEY REFERENCES kb_document(id),
    cron_expression VARCHAR(128), timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 1,
    last_etag VARCHAR(512), last_modified VARCHAR(128), last_content_hash CHAR(64),
    last_status VARCHAR(32), last_reason VARCHAR(64), last_run_id UUID,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128), lease_until TIMESTAMPTZ, claim_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(NOT enabled OR cron_expression IS NOT NULL)
);
CREATE INDEX kb_refresh_due_idx ON kb_refresh_schedule(next_run_at) WHERE enabled;
CREATE TABLE kb_refresh_run (
    id UUID PRIMARY KEY, document_id UUID NOT NULL REFERENCES kb_document(id), kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL, trigger_type VARCHAR(16) NOT NULL CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
    status VARCHAR(32) NOT NULL CHECK(status IN ('PENDING','RUNNING','WAITING_INGESTION','SUCCESS','FAILED','SKIPPED')),
    document_version BIGINT NOT NULL, config_revision BIGINT NOT NULL, schedule_version BIGINT NOT NULL,
    confirm_overwrite_edited BOOLEAN NOT NULL DEFAULT FALSE,
    expected_document_version BIGINT,
    attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 3 CHECK(max_attempts BETWEEN 1 AND 10),
    next_run_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128), lease_until TIMESTAMPTZ, claim_token BIGINT NOT NULL DEFAULT 0, deadline TIMESTAMPTZ,
    job_id UUID REFERENCES kb_ingestion_job(id), candidate_object_key VARCHAR(1024),
    candidate_filename VARCHAR(255), candidate_mime VARCHAR(128), candidate_size BIGINT, candidate_hash CHAR(64),
    candidate_etag VARCHAR(512), candidate_last_modified VARCHAR(128),
    http_status INTEGER, reason VARCHAR(64), error_message VARCHAR(256),
    started_at TIMESTAMPTZ, execution_started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id,owner_id,request_id),
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE UNIQUE INDEX kb_refresh_one_active_idx ON kb_refresh_run(document_id) WHERE status IN ('PENDING','RUNNING','WAITING_INGESTION');
CREATE INDEX kb_refresh_recovery_idx ON kb_refresh_run(status,next_run_at,lease_until);
ALTER TABLE kb_ingestion_job ADD COLUMN refresh_run_id UUID REFERENCES kb_refresh_run(id), ADD COLUMN refresh_claim_token BIGINT;

-- Reservation precedes PUT. Waiting candidates cannot be deleted while their run can still publish.
CREATE TABLE kb_remote_object_cleanup (
    id UUID PRIMARY KEY, document_id UUID NOT NULL REFERENCES kb_document(id), kb_id UUID NOT NULL,
    object_key VARCHAR(1024) NOT NULL UNIQUE, run_id UUID REFERENCES kb_refresh_run(id),
    status VARCHAR(24) NOT NULL CHECK(status IN ('WAITING','RETAINED','PENDING','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0, next_run_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(128), lease_until TIMESTAMPTZ, claim_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64), completed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX kb_remote_gc_due_idx ON kb_remote_object_cleanup(status,next_run_at,lease_until);
CREATE TABLE kb_url_import (
    id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES kb_knowledge_base(id),
    tenant_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL, request_id VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL CHECK(status IN ('RUNNING','COMPLETED','FAILED')),
    document_id UUID REFERENCES kb_document(id), job_id UUID REFERENCES kb_ingestion_job(id),
    etag VARCHAR(512), last_modified VARCHAR(128), object_key VARCHAR(1024),
    lease_until TIMESTAMPTZ NOT NULL, error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(kb_id,tenant_id,owner_id,request_id),
    FOREIGN KEY(kb_id,tenant_id,owner_id) REFERENCES kb_knowledge_base(id,tenant_id,owner_id)
);
