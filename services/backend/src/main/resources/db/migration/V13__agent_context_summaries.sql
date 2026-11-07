-- 派生摘要保留不可变修订；消息依赖具体修订，不能因新摘要覆盖旧撤权链。
CREATE TABLE kb_agent_context_summary (
    conversation_id uuid NOT NULL REFERENCES kb_agent_conversation(id),
    revision bigint NOT NULL CHECK (revision > 0),
    run_id uuid NOT NULL REFERENCES kb_agent_run(id),
    through_sequence bigint NOT NULL CHECK (through_sequence > 0),
    source_hash varchar(64) NOT NULL,
    sources jsonb NOT NULL,
    content jsonb NOT NULL,
    evidence jsonb NOT NULL,
    model varchar(120) NOT NULL,
    prompt_version varchar(120) NOT NULL,
    prompt_hash varchar(64) NOT NULL,
    policy_version varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (conversation_id, revision),
    UNIQUE (run_id)
);
ALTER TABLE kb_agent_message ADD COLUMN context_summary_revision bigint;
ALTER TABLE kb_agent_message ADD CONSTRAINT kb_agent_message_summary_fk
    FOREIGN KEY (conversation_id, context_summary_revision)
    REFERENCES kb_agent_context_summary(conversation_id, revision);
