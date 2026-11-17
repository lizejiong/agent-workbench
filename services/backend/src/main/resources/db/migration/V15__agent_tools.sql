-- 工具配置与调用记录在业务数据库内；只保存凭证引用，发现不等于发布。
ALTER TABLE kb_agent_run ADD COLUMN mode varchar(8) NOT NULL DEFAULT 'rag' CHECK(mode IN ('rag','agent'));

CREATE TABLE kb_tool_server (
    id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    name varchar(120) NOT NULL,
    url varchar(1000) NOT NULL,
    credential_ref varchar(128),
    config_version integer NOT NULL DEFAULT 1,
    enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE kb_tool_definition (
    tool_key varchar(200) PRIMARY KEY,
    server_id uuid NOT NULL REFERENCES kb_tool_server(id),
    model_name varchar(64) NOT NULL UNIQUE,
    remote_name varchar(120) NOT NULL,
    discovered jsonb NOT NULL,
    observed_hash varchar(64) NOT NULL,
    published jsonb,
    schema_hash varchar(64),
    config_version integer,
    enabled boolean NOT NULL DEFAULT false,
    approved_read_only boolean NOT NULL DEFAULT false,
    changed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(server_id,remote_name)
);
CREATE TABLE kb_tool_grant (
    tenant_id varchar(128) NOT NULL,
    user_id varchar(128) NOT NULL,
    tool_key varchar(200) NOT NULL REFERENCES kb_tool_definition(tool_key),
    enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,user_id,tool_key)
);
CREATE TABLE kb_tool_call (
    call_id varchar(33) PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES kb_agent_run(id),
    message_id uuid NOT NULL REFERENCES kb_agent_message(id) ON DELETE CASCADE,
    tool_key varchar(200) NOT NULL,
    provider_call_id varchar(128) NOT NULL,
    config_version integer NOT NULL,
    schema_hash varchar(64),
    request_hash varchar(64) NOT NULL,
    batch integer NOT NULL,
    call_order integer NOT NULL,
    arguments jsonb NOT NULL,
    status varchar(24) NOT NULL,
    result jsonb,
    error_code varchar(80),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ended_at timestamptz,
    deadline_at timestamptz NOT NULL,
    UNIQUE(run_id,provider_call_id)
);
CREATE INDEX kb_tool_call_run ON kb_tool_call(run_id,batch,call_order);
CREATE INDEX kb_tool_call_expiry ON kb_tool_call(deadline_at) WHERE status='running';

-- 对使用过历史工具结果的后续答案保守继承依赖，撤权后整条派生助手消息隐藏。
CREATE TABLE kb_tool_dependency (
    run_id uuid NOT NULL REFERENCES kb_agent_run(id),
    source_call_id varchar(33) NOT NULL REFERENCES kb_tool_call(call_id) ON DELETE CASCADE,
    PRIMARY KEY(run_id,source_call_id)
);
CREATE TABLE kb_tool_config_audit (
    id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    actor_id varchar(128) NOT NULL,
    action varchar(40) NOT NULL,
    resource varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
