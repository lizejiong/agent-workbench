-- V3 jobs remain readable; their already captured inputs are backfilled from their document/base.
ALTER TABLE kb_ingestion_job
    DROP CONSTRAINT kb_ingestion_job_status_check,
    DROP CONSTRAINT kb_ingestion_job_attempts_check,
    DROP CONSTRAINT kb_ingestion_job_max_attempts_check,
    ALTER COLUMN lease_owner DROP NOT NULL,
    ALTER COLUMN lease_until DROP NOT NULL,
    ALTER COLUMN started_at DROP NOT NULL,
    ALTER COLUMN started_at DROP DEFAULT,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN attempt_deadline TIMESTAMPTZ,
    ADD COLUMN execution_started_at TIMESTAMPTZ,
    ADD COLUMN retry_of UUID REFERENCES kb_ingestion_job(id),
    ADD COLUMN input_snapshot JSONB,
    ADD CONSTRAINT kb_ingestion_job_status_check CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    ADD CONSTRAINT kb_ingestion_job_attempts_check CHECK(attempts>=0 AND attempts<=max_attempts),
    ADD CONSTRAINT kb_ingestion_job_max_attempts_check CHECK(max_attempts BETWEEN 1 AND 10),
    ADD CONSTRAINT kb_ingestion_request_uq UNIQUE(tenant_id,owner_id,request_id);
UPDATE kb_ingestion_job j SET input_snapshot=jsonb_build_object(
    'originalFilename',d.original_filename,'mimeType',d.mime_type,'fileSize',d.file_size,
    'namespace',b.vector_namespace,'binding',jsonb_build_object(
        'modelId',b.embedding_model_id,'provider',b.embedding_provider,
        'modelName',b.embedding_config_json->>'modelName','dimension',b.embedding_dimension,
        'embeddingSpace',b.embedding_config_json->>'embeddingSpace','configVersion',b.embedding_config_json->>'configVersion',
        'localRevision',j.embedding_config_revision,'expectedAiRevision',b.embedding_ai_config_revision)),
    attempt_deadline=j.lease_until
FROM kb_document d JOIN kb_knowledge_base b ON b.id=d.kb_id WHERE d.id=j.document_id;
ALTER TABLE kb_ingestion_job ALTER COLUMN input_snapshot SET NOT NULL;
DROP INDEX kb_ingestion_active_uq;
CREATE UNIQUE INDEX kb_ingestion_active_uq ON kb_ingestion_job(document_id) WHERE status IN ('PENDING','RUNNING');
CREATE INDEX kb_ingestion_due_idx ON kb_ingestion_job(next_run_at,created_at,id) WHERE status='PENDING';
ALTER TABLE kb_ingestion_stage_log DROP CONSTRAINT kb_ingestion_stage_log_status_check;
ALTER TABLE kb_ingestion_stage_log ADD CONSTRAINT kb_ingestion_stage_log_status_check CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED'));

-- No object-store or remote vector side effect: deletion of an inactive generation is one short DB transaction.
CREATE TABLE kb_generation_cleanup (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES kb_document(id),
    generation BIGINT NOT NULL CHECK(generation>0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE(document_id,generation)
);
CREATE INDEX kb_generation_cleanup_due_idx ON kb_generation_cleanup(next_run_at,id) WHERE status='PENDING';
-- Queue historical inactive generations too; active generations are always rechecked under a document lock.
INSERT INTO kb_generation_cleanup(id,document_id,generation)
SELECT g.job_id,g.document_id,g.generation FROM kb_generation g JOIN kb_document d ON d.id=g.document_id
WHERE g.generation IS DISTINCT FROM d.active_generation ON CONFLICT DO NOTHING;
