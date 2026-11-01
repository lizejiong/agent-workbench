CREATE TABLE kb_rag_conversation (
    id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX kb_rag_conversation_owner ON kb_rag_conversation(tenant_id, owner_id, created_at DESC);

CREATE TABLE kb_rag_answer_run (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES kb_rag_conversation(id),
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    question text NOT NULL CHECK (length(question) BETWEEN 1 AND 16000),
    status varchar(32) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','NO_EVIDENCE','NEEDS_CLARIFICATION','CITATION_INVALID','CANCELLED','TIMED_OUT','FAILED')),
    deadline_at timestamptz NOT NULL,
    answer_text text NOT NULL DEFAULT '',
    result_json jsonb,
    error_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX kb_rag_one_active_turn ON kb_rag_answer_run(conversation_id) WHERE status='RUNNING';
CREATE INDEX kb_rag_turn_history ON kb_rag_answer_run(tenant_id,owner_id,conversation_id,created_at DESC);
CREATE INDEX kb_rag_expired_run ON kb_rag_answer_run(deadline_at) WHERE status='RUNNING';
