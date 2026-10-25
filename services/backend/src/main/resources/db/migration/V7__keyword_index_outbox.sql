CREATE TABLE kb_keyword_sync (
    document_id UUID PRIMARY KEY REFERENCES kb_document(id), kb_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL, owner_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1, synced_revision BIGINT NOT NULL DEFAULT 0,
    indexed_target CHAR(64), status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0, claimed_revision BIGINT,
    next_run_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, lease_owner VARCHAR(128),lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0, attempt_deadline TIMESTAMPTZ, error_code VARCHAR(64), synced_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(document_id,kb_id,tenant_id,owner_id) REFERENCES kb_document(id,kb_id,tenant_id,owner_id)
);
CREATE INDEX kb_keyword_due_idx ON kb_keyword_sync(status,next_run_at,lease_until);
CREATE TABLE kb_keyword_cleanup (
    document_id UUID PRIMARY KEY REFERENCES kb_document(id), kb_id UUID NOT NULL,
    watermark BIGINT NOT NULL, claimed_watermark BIGINT, status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0, next_run_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),lease_until TIMESTAMPTZ,claim_token BIGINT NOT NULL DEFAULT 0,
    attempt_deadline TIMESTAMPTZ,error_code VARCHAR(64),updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- The transaction that changes content also records the index intent, including all deletion/GC paths.
CREATE FUNCTION kb_mark_keyword_dirty(target UUID) RETURNS void LANGUAGE SQL AS $$
    INSERT INTO kb_keyword_sync(document_id,kb_id,tenant_id,owner_id)
    SELECT id,kb_id,tenant_id,owner_id FROM kb_document WHERE id=target
    ON CONFLICT(document_id) DO UPDATE SET revision=kb_keyword_sync.revision+1,
        status=CASE WHEN kb_keyword_sync.status='RUNNING' THEN 'RUNNING' ELSE 'PENDING' END,
        attempts=CASE WHEN kb_keyword_sync.status='RUNNING' THEN kb_keyword_sync.attempts ELSE 0 END,
        next_run_at=clock_timestamp(),error_code=NULL,updated_at=clock_timestamp();
$$;
CREATE FUNCTION kb_keyword_chunk_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN PERFORM kb_mark_keyword_dirty(OLD.document_id);RETURN OLD;
    ELSE PERFORM kb_mark_keyword_dirty(NEW.document_id);RETURN NEW;END IF;
END $$;
CREATE TRIGGER kb_keyword_chunk_dirty AFTER INSERT OR UPDATE OR DELETE ON kb_chunk
    FOR EACH ROW EXECUTE FUNCTION kb_keyword_chunk_changed();
CREATE FUNCTION kb_keyword_document_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM kb_mark_keyword_dirty(NEW.id); RETURN NEW;
END $$;
CREATE TRIGGER kb_keyword_document_insert AFTER INSERT ON kb_document
    FOR EACH ROW EXECUTE FUNCTION kb_keyword_document_changed();
CREATE TRIGGER kb_keyword_document_update AFTER UPDATE OF active_generation,enabled,name,deleted_at ON kb_document
    FOR EACH ROW WHEN(OLD.active_generation IS DISTINCT FROM NEW.active_generation OR OLD.enabled IS DISTINCT FROM NEW.enabled
        OR OLD.name IS DISTINCT FROM NEW.name OR OLD.deleted_at IS DISTINCT FROM NEW.deleted_at)
    EXECUTE FUNCTION kb_keyword_document_changed();
INSERT INTO kb_keyword_sync(document_id,kb_id,tenant_id,owner_id) SELECT id,kb_id,tenant_id,owner_id FROM kb_document;
