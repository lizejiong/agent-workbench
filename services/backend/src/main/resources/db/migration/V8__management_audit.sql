-- Extend the existing same-transaction, scoped summary audit; never store source text, URLs, credentials or exceptions.
ALTER TABLE kb_document ADD COLUMN last_ingested_at TIMESTAMPTZ;
UPDATE kb_document d SET last_ingested_at=j.completed_at FROM kb_generation g JOIN kb_ingestion_job j ON j.id=g.job_id
  WHERE g.document_id=d.id AND g.generation=d.active_generation AND j.status='SUCCEEDED';
ALTER TABLE kb_content_audit ALTER COLUMN document_id DROP NOT NULL;
CREATE INDEX kb_content_audit_scope_idx ON kb_content_audit(kb_id,tenant_id,owner_id,created_at DESC,id DESC);

CREATE FUNCTION kb_management_summary(kind TEXT, r JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
SELECT CASE WHEN r IS NULL THEN '{}'::jsonb ELSE CASE kind
  WHEN 'kb_knowledge_base' THEN jsonb_build_object('version',r->'version','nameHash',md5(r->>'name'),
    'modelId',r->'embedding_model_id','modelRevision',r->'embedding_config_revision','dimension',r->'embedding_dimension','deleted',r->>'deleted_at' IS NOT NULL)
  WHEN 'kb_document' THEN jsonb_build_object('version',r->'version','configRevision',r->'config_revision','nameHash',md5(r->>'name'),
    'contentHash',r->'content_hash','enabled',r->'enabled','activeGeneration',r->'active_generation','chunkCount',r->'chunk_count','deleted',r->>'deleted_at' IS NOT NULL)
  WHEN 'kb_ingestion_job' THEN jsonb_build_object('jobId',r->'id','status',r->'status','stage',r->'stage','attempts',r->'attempts',
    'generation',r->'target_generation','errorCode',r->'error_code')
  WHEN 'kb_refresh_schedule' THEN jsonb_build_object('version',r->'version','enabled',r->'enabled','cron',r->'cron_expression','timezone',r->'timezone')
  WHEN 'kb_refresh_run' THEN jsonb_build_object('runId',r->'id','status',r->'status','jobId',r->'job_id','attempts',r->'attempts','reason',r->'reason')
  END END
$$;

CREATE FUNCTION kb_audit_management_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior JSONB; current_summary JSONB; previous_summary JSONB; scope_doc UUID; scope_kb UUID;
  scope_tenant TEXT; scope_owner TEXT; operator_id TEXT; operation TEXT;
BEGIN
  prior := CASE WHEN TG_OP='INSERT' THEN NULL ELSE to_jsonb(OLD) END;
  current_summary := kb_management_summary(TG_TABLE_NAME,to_jsonb(NEW));
  previous_summary := kb_management_summary(TG_TABLE_NAME,prior);
  IF current_summary=previous_summary THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='kb_knowledge_base' THEN
    scope_doc:=NULL;scope_kb:=NEW.id;scope_tenant:=NEW.tenant_id;scope_owner:=NEW.owner_id;operator_id:=NEW.updated_by;
    operation:='BASE_'||TG_OP;
  ELSE
    IF TG_TABLE_NAME='kb_document' THEN scope_doc:=NEW.id;operator_id:=NEW.updated_by;
    ELSE scope_doc:=NEW.document_id; END IF;
    SELECT kb_id,tenant_id,owner_id INTO scope_kb,scope_tenant,scope_owner FROM kb_document WHERE id=scope_doc;
    -- All current business writes are private to the trusted owner. Background changes retain that persisted identity.
    operator_id:=COALESCE(operator_id,scope_owner);
    operation:=CASE TG_TABLE_NAME WHEN 'kb_document' THEN 'DOCUMENT_METADATA'
      WHEN 'kb_ingestion_job' THEN 'INGESTION_STATE' WHEN 'kb_refresh_schedule' THEN 'REFRESH_SETTINGS' ELSE 'REFRESH_STATE' END;
  END IF;
  INSERT INTO kb_content_audit(id,document_id,kb_id,tenant_id,owner_id,actor_id,action,chunk_ids,before_summary,after_summary)
    VALUES(gen_random_uuid(),scope_doc,scope_kb,scope_tenant,scope_owner,operator_id,operation,'[]'::jsonb,previous_summary,current_summary);
  RETURN NEW;
END $$;

CREATE TRIGGER kb_audit_base AFTER INSERT OR UPDATE ON kb_knowledge_base FOR EACH ROW EXECUTE FUNCTION kb_audit_management_change();
CREATE TRIGGER kb_audit_document AFTER INSERT OR UPDATE ON kb_document FOR EACH ROW EXECUTE FUNCTION kb_audit_management_change();
CREATE TRIGGER kb_audit_ingestion AFTER INSERT OR UPDATE ON kb_ingestion_job FOR EACH ROW EXECUTE FUNCTION kb_audit_management_change();
CREATE TRIGGER kb_audit_schedule AFTER INSERT OR UPDATE ON kb_refresh_schedule FOR EACH ROW EXECUTE FUNCTION kb_audit_management_change();
CREATE TRIGGER kb_audit_refresh AFTER INSERT OR UPDATE ON kb_refresh_run FOR EACH ROW EXECUTE FUNCTION kb_audit_management_change();
