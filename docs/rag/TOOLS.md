# 统一只读工具与 MCP

2026-11-17。实际修改、执行命令、通过结果和剩余边界见 [TOOLS_DELIVERY.md](TOOLS_DELIVERY.md)、[TOOLS_VALIDATION.json](TOOLS_VALIDATION.json)。恢复入口为 [TOOLS_PROGRESS.md](TOOLS_PROGRESS.md)。

## 实际技术边界

本仓库为 Agent Workbench，并非 Nest/Prisma/Vercel AI SDK 项目。沿用 Spring Boot、JdbcTemplate、Flyway、React/Ant Design、ChatOpenAI 和 LangGraph。没有另建身份系统或聊天账本，也没有添加另一层模型来自动选择模式。

| 职责 | 实现 |
|---|---|
| 主体、固定知识范围、领域 ACL、历史、事务 | 原 Java backend |
| MCP 配置、只读发布、用户授权、调用审计 | backend/tools，Flyway V15 |
| 工具 Schema、注册、参数验证、执行、取消、结果/Token 预算 | agent_service/tools 与 context |
| 兼容 RAG | 原 create_agent 路径，search_knowledge 进入统一执行器 |
| 显式 Agent | react.py 的 StateGraph：prepare_context → model ⇄ tools → evidence_check → final |
| 浏览器入口 | Java /api/agent/chat[/stream]，转发至受服务凭据保护的 Python |
| 外部流式协议 | 保留本仓库 SSE，新增 v3；没有引入不存在的 NDJSON/AI SDK 协议 |

Python 本地工具直接调用原 KnowledgeGateway，不绕 MCP。检索算法及最终文档鉴权仍在原 Java 领域服务。原仓库没有图谱服务/GraphQueryTool，因此未伪造 graph_query。evidence_read 只接收本轮 evidenceId，由 Java 对已保存快照按当前文档状态、generation、正文 hash、所选范围和 ACL 重新回表；不能按任意 chunkId 取原文。

## 本地完整验收

以下命令使用可丢弃数据库和 HTTP 模型桩，不需要真实供应商或线上 MCP。仅绑定 127.0.0.1，开发栈须显式开启。Docker 镜像沿用仓库测试版本。

```powershell
docker run -d --name atlas-tools-dev -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=atlas_test -e POSTGRES_DB=atlas_tools -p 127.0.0.1:55468:5432 pgvector/pgvector:0.8.7-pg17-bookworm
.\mvnw.cmd -B "-Dmaven.repo.local=.cache/maven/repository" -pl services/backend -am package -DskipTests
uv sync --project services/agent-service --locked
$env:ATLAS_TOOL_FIXTURE='true'
uv run --project services/agent-service python scripts/tools/dev_stack.py
```

另一个终端执行：

```powershell
uv run --project services/agent-service python scripts/tools/smoke.py --fixture
```

脚本会创建服务、测试发现、验证默认关闭、发布指定只读工具、授权当前测试用户、并行调用两个工具、校验 SSE seq/大小/脱敏、读取 Java 历史与审计，最后禁用工具并验证旧答案和轨迹隐藏。结果写入 output/tools/smoke.json。加 `--leave-enabled` 可保留工具用于前端验收；之后用报告中的 `serverId` 运行 `smoke.py --fixture --server-id <serverId>` 验证下线，避免重复发布测试服务。`cancel_smoke.py` 另行验证慢工具开始事件及时到达，以及显式停止/断线的真实任务和审计收口。

```powershell
# 先保留已授权慢工具，再执行两种取消，最后禁用（serverId 从回执读取）。
uv run --project services/agent-service python scripts/tools/smoke.py --fixture --leave-enabled
uv run --project services/agent-service python scripts/tools/cancel_smoke.py
uv run --project services/agent-service python scripts/tools/smoke.py --fixture --server-id <serverId>
```

开发栈默认端口为 Java 8086、Python 8096、MCP 8765、模型桩 8766；可用 `--backend-port` 等参数修改。开发账号 alice / atlas-admin 的密码只用于隔离测试：`atlas-fixture-password`。前端另开终端：

```powershell
$env:KB_BACKEND_ORIGIN='http://127.0.0.1:8086'
npm --prefix web ci
npm --prefix web run dev -- --port 5176
```

登录后在聊天输入区选择「Agent 工具」。工具卡支持展开结果、参数、错误码，最终 MCP 引用使用独立来源类型，引用数字与内部知识来源统一排序；不会把 MCP 数据展示成内部文档。刷新重新登录后从 Java 恢复。停止开发栈：在其 `--state-dir` 目录创建名为 `stop` 的文件，监督进程会回收 Python/MCP/模型服务器及 Java 子进程；不要对其他运行目录或数据库执行清理。

单独启动 fixture：

```powershell
$env:ATLAS_TOOL_FIXTURE='true'
uv run --project services/agent-service python scripts/tools/mcp_fixture.py
```

包含普通文本、Pydantic structuredContent、慢调用、大结果、isError 业务失败、图片类型限制，以及用于验证凭证隔离的单向指纹工具。fixture 不属于生产服务。

可选真实模型冒烟：`dev_stack.py --real-model-env <受保护的服务端JSON路径>` 读取该文件中 `agent` 对象（或根对象）的 `AGENT_MODEL_BASE_URL`、`AGENT_MODEL`、`AGENT_MODEL_API_KEY`，不回显凭据。再运行 `smoke.py --expected-tools 1 --question "请调用一个只读工具查询服务窗口"`，不加 `--fixture`。按本地端口传入 `--origin` 和 `--mcp-url`；模型实际选择的工具数须与期望一致。该冒烟检查原生 Function Calling/流式闭环，不替代业务语义评测。

## 部署配置与认证

在服务端环境设置，不在浏览器填写：

| 配置 | 作用 |
|---|---|
| Python JAVA_RAG_ORIGIN / RAG_SERVICE_ID / RAG_SERVICE_TOKEN | 原 Java 服务身份，数据库服务配置保存其 SHA-256 hash |
| Java kb.rag.internal.enabled / service-token-hashes | 沿用已有服务认证及会话委托 |
| Java KB_AGENT_ORIGIN / KB_AGENT_TOKEN | 受信任 Python origin 与独立的反向服务凭据 |
| Python AGENT_INBOUND_TOKEN | 与 KB_AGENT_TOKEN 一致，至少 32 字符；不复用模型 key |
| Java KB_MCP_ALLOWED_ORIGINS | 部署允许访问的精确 origin 列表 |
| Python AGENT_MCP_ALLOWED_ORIGINS | 与 Java 对齐的 origin 列表；不跟随重定向 |
| credentialRef，例如 env:ATLAS_CRM_READ_TOKEN | 引用 AI worker 环境中的 bearer secret；数据库只有引用 |

Java → Python 聊天用服务 token + `agent-execution` audience，并转交既有 Basic 主体。发现用独立 `mcp-control` audience。Python → Java 工具接口需要服务 token、serviceId、`backend-tools` audience、有效会话委托和 deadline；每次读取连接信息、执行前授权、结算均重新绑定 run/owner/service。仅传 actorId 不构成许可。

MCP SDK 固定为 `mcp==1.26.0`，按[该版本官方文档](https://github.com/modelcontextprotocol/python-sdk/blob/v1.26.0/README.md#writing-mcp-clients)使用 ClientSession、initialize、分页 list_tools、call_tool 和 Streamable HTTP。每次操作独立连接并在同一异步 task 退出 SDK cancel scope，没有跨用户 session 池。凭证、客户端、取消对象和授权范围只留在可信运行上下文；图检查点只有消息，完成即删除。

当前支持服务端环境凭证。需要不同用户拥有不同远端身份时，分别配置对应服务/credentialRef 与授权；未实现用户 OAuth。平台批准的只读标记才是放行条件，readOnlyHint 只供参考。URL 只取管理员配置及部署允许列表；工具参数不能声明平台身份、凭据、授权范围或 deadline。工具结果中的 URL/资源不会被自动访问，图片及未支持的资源内容返回明确类型错误。

## 管理与内部 API

管理 API 沿用当前 `ROLE_KB_OPERATOR`；并不存在另一个 ADMIN/AuthorizationService。普通用户不能管理。所有列表 no-store，凭证只保存引用。

| 方法与路径 | 请求/作用 |
|---|---|
| GET /api/tools/servers | 本租户服务配置 |
| POST /api/tools/servers | `{name,url,credentialRef?,enabled}`；新工具仍默认关闭 |
| PUT /api/tools/servers/{id} | 完整配置 + expectedVersion；更新修订并关闭旧工具 |
| POST /api/tools/servers/{id}/test | initialize 与发现，不发布 |
| POST /api/tools/servers/{id}/discover | 分页发现并记录差异；定义变化标记 changed |
| GET /api/tools/servers/{id}/tools | discovered/published/hash/enabled/changed |
| PUT /api/tools/publish?toolKey=… | `{schemaHash,enabled,readOnly,timeoutMs,maxResultBytes}`；启用须 readOnly=true |
| PUT /api/tools/grant?toolKey=… | `{userId,enabled}`，仅本租户 |
| GET /api/tools/calls?runId=…&limit=50 | 脱敏状态与关联标识；最多100条。详情还受当前权限限制 |

完整可执行请求见 scripts/tools/smoke.py。服务 ID 与远端工具名生成稳定 toolKey；模型名经过字符限制与哈希映射，不会因两个服务同名工具而冲突。发布固定 input/output Schema、hash 和配置版本；发现变化后旧能力不可调用，须显式重新发布。没有自动放开新增工具。

内部工具路径前缀为 `/internal/tools/runs/{runId}`，包含 tools、calls、calls/{callId}/connection、calls/{callId}/authorize、calls/{callId}、evidence、validate 和 history-dependencies。它们不暴露到浏览器。开始许可与审计插入同一短事务，失败时不执行远端；调用 ID/供应商 ID 唯一去重。最终状态单独持久化，结算失败使运行明确失败，不声称成功；每5秒从数据库收口到期/取消的未完成调用。业务消息删除时连带清理调用正文，配置审计保留无敏感正文的操作标识。

## 预算、失败与取消

保留原上下文窗口、UTF-8 硬限制、输出预留和 tokenizer 配置。新增 `AGENT_MAX_RUN_TOKENS`（默认131072、保守估计）、`AGENT_TOOL_CONCURRENCY`（默认3）、`AGENT_MAX_RESULT_BYTES`（默认32768）；原 max_model_calls/max_tool_calls 分别限制步骤与调用。每次请求把系统提示、Schema、历史、call/result 和工具数据全部计数，摘要也消耗同一运行预算。并行结果预留容量，压缩仅修改送模副本且保留 call/result 配对、当前问题和用户限制。

参数在完整非流式规划响应后使用标准 JSON Schema 验证；非法 JSON、参数、未知/未授权工具、空内容、超时、取消、依赖不可用均有独立状态。工具故障不会转换为「没找到」。达到预算停止新调用，已有来源允许给出限定回答；不能再生成时返回明确不足状态。配置或供应商不支持 tools 时给出能力错误，不偷偷换模型或扩大权限。HTTP/SDK 不自动重试整轮或远端调用。

SSE v3 使用工具 start/success/failure/cancel 事件和 `data-tool` Part，Pydantic 与 Web Zod 契约及跨语言样例位于 tools/events.py、web/src/chat/toolContract.ts、packages/contracts。唯一事件分配器串行赋 seq，终态 done 后停止；单条事件小于64 KiB，超大终态只提示读取已保存快照。v1/v2 保留兼容。工具卡的耗时取服务端，浏览器断线不重提整轮。

显式停止写入 Java run 状态，Python 监控并取消真实模型/工具任务；代理连接断开沿用同一取消链。当前目标为单 Java/单 AI worker，`StateDirectoryLock` 拒绝两个 worker 共用检查点目录；运行状态及取消以 PostgreSQL 为准。尚未宣称多 AI worker 共享 SQLite/多实例部署已支持，不靠进程内 registry 跨实例恢复。

内部证据继续按原 ACL/版本校验。MCP 使用明确的来源 ID 与配置修订、Schema hash、授权、成功调用记录校验策略；这是已取得数据的快照，不代表远端数据永远不变。Java 在历史、摘要和派生回答读取时重新检查依赖。只继承实际选入模型历史/摘要的来源；撤权/禁用/定义变化后隐藏相关参数、摘要、工具卡和派生旧答案。历史本身不成为本轮可引用事实。

## 扩展及故障定位

新增本地工具：定义 ToolDefinition（输入 Schema 禁止身份字段），实现 AsyncExecutor，向 Java ToolLedger.builtins 与 Python prepare_tools 注册，领域资源在领域服务最终鉴权；输出 ToolResult 及受校验引用，不在工具内部生成最终答案。补充协议/权限/取消测试。只读接入不能作为业务写入许可，确认与幂等语义另行设计。

接入 MCP：部署允许 origin → 服务端 credentialRef → 创建服务 → test/discover → 审核 Schema 差异和只读性 → publish → grant → 少量 agent smoke。下线先 publish enabled=false 或撤权，必要时更新/禁用服务；所有步骤会写配置审计。

通过 callId/runId/assistantMessageId 查 kb_tool_call、kb_agent_run 和 kb_agent_message；配置变更在 kb_tool_config_audit。TOOL_AUDIT_UNAVAILABLE 表示审计未确认，MCP_SCHEMA_CHANGED 表示需重新发现批准，MCP_BUSINESS_ERROR 是远端业务失败，MCP_CONTENT_UNSUPPORTED 是当前内容限制，MCP_CONNECTION_UNAVAILABLE/TOOL_TIMEOUT 是连接或耗时故障。普通日志不包含凭证、原始 MCP 响应或隐藏推理。

运行校验：`uv run --project services/agent-service ruff check services/agent-service/src services/agent-service/tests scripts/tools`、pytest（Windows 用仓库内独立 --basetemp）、`npm --prefix web test`、`npm --prefix web run build`、根 Maven verify。数据库集成须使用可丢弃的 KB_TEST_JDBC_URL；测试会清空该库的业务表。脚本测试、真实协议/数据库/浏览器测试和真实供应商效果须分别报告。
