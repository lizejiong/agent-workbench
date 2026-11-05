-- Agent 展示历史归 Java；Python 的图检查点不作为业务历史或执行授权。
CREATE TABLE kb_agent_conversation (
    id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    create_request_id uuid NOT NULL,
    create_hash varchar(64) NOT NULL,
    title varchar(120) NOT NULL,
    kb_ids jsonb NOT NULL,
    document_ids jsonb,
    next_sequence bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    active_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted_at timestamptz,
    UNIQUE(tenant_id, owner_id, create_request_id)
);
CREATE INDEX kb_agent_conversation_owner ON kb_agent_conversation(tenant_id, owner_id, active_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE kb_agent_run (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES kb_agent_conversation(id),
    request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    user_message_id uuid NOT NULL UNIQUE,
    assistant_message_id uuid NOT NULL UNIQUE,
    service_id varchar(80) NOT NULL,
    trace_id varchar(32) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','CANCELLED','FAILED','TIMED_OUT')),
    checkpoint_sequence bigint NOT NULL DEFAULT 0,
    checkpoint_hash varchar(64),
    error_code varchar(80),
    deadline_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ended_at timestamptz,
    UNIQUE(conversation_id, request_id)
);
CREATE UNIQUE INDEX kb_agent_one_running ON kb_agent_run(conversation_id) WHERE status='RUNNING';
CREATE INDEX kb_agent_run_expiry ON kb_agent_run(deadline_at) WHERE status='RUNNING';

CREATE TABLE kb_agent_message (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES kb_agent_conversation(id),
    run_id uuid NOT NULL REFERENCES kb_agent_run(id),
    request_id uuid NOT NULL,
    sequence bigint NOT NULL,
    role varchar(16) NOT NULL CHECK(role IN ('user','assistant')),
    content text NOT NULL DEFAULT '',
    status varchar(16) NOT NULL,
    evidence jsonb NOT NULL DEFAULT '[]',
    citations jsonb NOT NULL DEFAULT '[]',
    tools jsonb NOT NULL DEFAULT '[]',
    model varchar(120),
    prompt_version varchar(120),
    prompt_hash varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(conversation_id, sequence)
);
CREATE INDEX kb_agent_message_history ON kb_agent_message(conversation_id, sequence DESC);
