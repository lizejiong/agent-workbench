-- 仅保存委托与取消控制元数据，不保存 Python Agent 对话、工具历史或图状态。
CREATE TABLE kb_rag_delegation (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    service_id varchar(80) NOT NULL,
    username varchar(128) NOT NULL,
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    agent_session_id uuid NOT NULL,
    kb_ids jsonb NOT NULL,
    document_ids jsonb,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX kb_rag_delegation_expiry ON kb_rag_delegation(expires_at);
CREATE TABLE kb_rag_tool_request (
    request_id varchar(128) PRIMARY KEY,
    delegation_id uuid NOT NULL REFERENCES kb_rag_delegation(id) ON DELETE CASCADE,
    trace_id char(32) NOT NULL,
    deadline_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('RUNNING','CANCELLED','TIMED_OUT','FINISHED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX kb_rag_tool_request_deadline ON kb_rag_tool_request(deadline_at) WHERE status='RUNNING';
