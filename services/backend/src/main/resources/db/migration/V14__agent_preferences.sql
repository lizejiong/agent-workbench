-- 只保存用户显式选择的表达偏好；没有自动事实抽取或模型写入接口。
CREATE TABLE kb_agent_preferences (
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    language varchar(16) NOT NULL DEFAULT 'AUTO' CHECK (language IN ('AUTO','ZH_CN','EN')),
    detail varchar(16) NOT NULL DEFAULT 'BALANCED' CHECK (detail IN ('BALANCED','CONCISE','DETAILED')),
    format varchar(16) NOT NULL DEFAULT 'AUTO' CHECK (format IN ('AUTO','PARAGRAPHS','BULLETS')),
    updated_at timestamptz,
    PRIMARY KEY (tenant_id, owner_id)
);
ALTER TABLE kb_agent_run ADD COLUMN preferences_snapshot jsonb NOT NULL
    DEFAULT '{"revision":0,"epoch":0,"language":"AUTO","detail":"BALANCED","format":"AUTO","updatedAt":null}'::jsonb;
