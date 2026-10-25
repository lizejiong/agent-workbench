CREATE TABLE kb_knowledge_base (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL CHECK (length(btrim(name)) > 0 AND name = btrim(name)),
    description VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX kb_knowledge_base_owner_name_uq
    ON kb_knowledge_base (tenant_id, owner_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX kb_knowledge_base_owner_page_idx
    ON kb_knowledge_base (tenant_id, owner_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;

COMMENT ON TABLE kb_knowledge_base IS 'Knowledge base metadata; all application access is scoped to tenant and owner';
COMMENT ON COLUMN kb_knowledge_base.deleted_at IS 'Tombstone; excluded from API reads and writes';
