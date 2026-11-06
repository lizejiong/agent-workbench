-- 内容块是业务消息的一部分；不保存 LangGraph 图或供应商原始响应。
ALTER TABLE kb_agent_message
    ADD COLUMN blocks jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN event_seq bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT kb_agent_message_blocks_array CHECK (jsonb_typeof(blocks) = 'array'),
    ADD CONSTRAINT kb_agent_message_event_seq CHECK (event_seq >= 0);
