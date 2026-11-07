# Agent Workbench 架构边界

2026-11-05。Java 统一管理业务会话和消息；Python 通过受授权接口读写，继续负责 Agent 主循环和上下文选择。本说明替代此前 Python 自有业务会话账本的边界。

## 模块归属

| 能力 | 模块与实现 |
|---|---|
| Agent 决策、工具调度、主模型原生流 | services/agent-service；官方 create_agent / LangGraph |
| 当前图执行检查点 | Python AsyncSqliteSaver，按 runId 隔离；不作为业务历史或重放授权 |
| Agent 会话、消息、引用/证据、运行状态 | services/backend/agent；Flyway V11、JdbcTemplate 与短事务 |
| 跨会话回答偏好 | Java V14按tenant/owner保存并绑定run；Python校验后加入预算内背景；Web账号菜单编辑 |
| 历史输入选择及摘要 | Python，从 Java 已授权历史中选择近期消息与有界用户问题提取摘要 |
| 知识管理、入库、向量/关键词、融合、精排、证据组装 | services/backend；复用已有共享 RAG 引擎及索引 |
| Java 独立问答及其 SSE/历史 | services/backend/rag；原 V9 表与接口，不混作 Agent 会话 |
| Embedding、Rerank、Java LLM/Vision 基础设施 | services/ai-service，沿用已有接口 |
| 用户、数据归属、服务/用户委托 | Java 原 Basic、服务凭证及 V10 委托，不新增复杂 IAM |
| 聊天与后台 | web；Java 分页历史 + Python SSE；后台管理继续调用原接口 |
| Java/Python 业务工具 | 后续按需接入；当前唯一实际 Agent 工具为 search_knowledge |

## 调用与可信上下文

Web 先调用 Java POST /api/conversations 创建固定范围会话，再把 conversationId、question、requestId、messageId 发送给 Python。Python 用当前用户认证申请会话绑定委托，以服务身份 + 用户委托建立 Java run、原子保存用户/助手消息并取得唯一执行权，然后读取 Java 历史、执行图和原生模型流。工具只暴露 query，身份/范围/截止时间从 ToolRuntime.context 注入。

Python 分批写 Java 快照并输出 SSE；终态落库确认后才声称 persisted=true。Web 刷新、重新登录、其他设备均通过 Java GET 恢复历史和运行状态。加载、分页、重连查询不会恢复旧工具节点；重复请求只返回已有 run。

浏览器同源代理 `/api/agent/chat` 及其流路径发往 Python，`/api/agent/preferences`、会话和其他 `/api/*` 发往 Java。用户/租户、固定范围和凭证不能由模型填写，trace 仅作关联。偏好是固定表达枚举，当前问题明确要求优先，不构成事实、审批或授权；完整契约见 [回答偏好](rag/PREFERENCES.md)。

## 历史、权限与删除

Java 保存模型实际可见的证据快照、知识 generation/模型索引版本和引用。每页展示及模型历史读取复用现有有效性回表；失效依赖使整个派生助手消息隐藏。模型使用历史助手内容时，新回答继承该内容的证据依赖；历史编号不自动成为本轮可引用证据。

Python 删除原 sessions.py，不再写 sessions.sqlite。图检查点只在执行期间由 Python 保存，执行结束清理；独占 state_dir 的进程重启时清理遗留执行图，不自动恢复。Java 删除会话时清除业务消息/证据并取消活动运行；只保留防重放的最小墓碑。旧本地 sessions.sqlite 不自动导入，按运维数据保留策略处理。

当前模型/图/Java 写入均有 deadline 和调用预算，Java 到期清扫只结束状态。尚未支持人机中断恢复、流事件重放、业务工具或多进程共享 SQLite。真实供应商语义质量、Nginx 生产代理及容量应另外验收。

完整字段、状态机、SSE 与故障语义见 [会话契约](rag/CONVERSATIONS.md)，启动见 [Agent README](../services/agent-service/README.md)，验收分层见 [会话验证记录](rag/CONVERSATION_VALIDATION.json)。
