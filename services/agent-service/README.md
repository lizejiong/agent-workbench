# Python 主 Agent

正式入口 `agent_service.app:app`。Python 负责官方 LangChain create_agent / LangGraph 工具循环、上下文选择、主模型原生调用和 SSE；Java 统一保存业务会话、消息、运行状态、引用及证据快照。当前实际工具只有 search_knowledge，调用 Java /internal/rag/retrieve，共用 Java RAG 算法，不调用 /answer 后重新转述。

## 模块

| 文件 | 职责 |
|---|---|
| app.py | FastAPI、共享连接池、模型/Saver 生命周期、JSON/SSE、断连清理 |
| runtime.py | Java 执行权、历史预算、原生增量、分批保存、取消和引用校验 |
| conversations.py | Java 业务会话受授权 HTTP 客户端 |
| graph.py | 官方图、query-only 工具、可信上下文、模型/工具调用预算 |
| knowledge/delegation.py | 按当前用户申请会话绑定短期委托并撤销 |
| knowledge/adapter.py | 唯一 Java 知识适配器、来源映射及受控结果 |
| prompts/agent-*-v2.txt | 当前中文提示词；文件列表/版本在 graph.py 集中定义并记录 SHA-256 |

Python 3.12+，uv.lock 锁定现有 LangChain 1.4.7、LangGraph 1.2.19、langchain-openai 1.7.3、SQLite Saver 3.1.3、httpx 0.28.1；FastAPI/Pydantic 复用 rag-common。AnyIO 为原有 FastAPI 运行依赖，本轮显式声明，用于保护断连后的短清理，不引入新 Agent 框架。

## 启动

先启动已迁移 V11 的 Java，并按 [RAG RUNBOOK](../../docs/rag/RUNBOOK.md) 配好原内部接口服务凭证。

```powershell
uv sync --project services/agent-service --locked
$env:JAVA_RAG_ORIGIN='http://127.0.0.1:8080'
$env:RAG_SERVICE_ID='python-agent'
# 安全注入 RAG_SERVICE_TOKEN，与 Java 保存的 SHA-256 对应。
$env:AGENT_MODEL_BASE_URL='供应商原生兼容接口的 HTTPS 地址及实际 /v1 路径'
$env:AGENT_MODEL='支持原生工具调用和流式响应的模型'
# 安全注入 AGENT_MODEL_API_KEY。
$env:AGENT_STATE_DIR='C:/受保护且独占的数据目录/agent-service'
uv run --project services/agent-service --locked uvicorn agent_service.app:app --host 127.0.0.1 --port 8090 --workers 1
```

.env.example 不自动加载。远程 Java/模型地址采用可信 HTTPS，本机开发可用 HTTP；禁用环境代理和重定向。模型地址使用供应商原生协议，不能填 Java /ai/chat。GET /health 只表明进程存活。

## 会话与调用

Web/客户端先以当前用户 Basic 调用 Java POST /api/conversations，拿到 id；再以相同用户认证调用 Python POST /api/agent/chat/stream：

```json
{"conversationId":"00000000-0000-0000-0000-000000000001","requestId":"00000000-0000-0000-0000-000000000002","messageId":"00000000-0000-0000-0000-000000000003","question":"PostgreSQL 如何备份？"}
```

流返回 meta、delta、tool、error、done；正常结束和错误均读取 Java 权威状态。需要幂等重提时保留原三标识，服务端返回已有运行，绝不重执行；不支持 Last-Event-ID 事件重放。普通 POST /api/agent/chat 保留 JSON 及旧 sessionId 别名；旧无标识首次请求会通过 Java 创建会话，不能保证两次无 ID HTTP 是同一操作。

每轮重新认证并申请绑定会话、服务和固定知识范围的短期委托。租户/用户、委托、服务令牌和截止时间不进入模型工具参数。Python 经服务身份 + 委托读写 Java，不直接写业务表，也不再维护 Python 会话租约。

从 Java 最近60条有效完成消息中选最多12条、最多24000字节（受总预算进一步限制）；较早最多8条用户问题提取为2048字节摘要。助手历史须通过当前证据授权检查，其依赖继承到新回答，旧工具正文不作为历史消息送模。最终引用只能使用本轮实际检索的唯一 K 编号。

每 run 新建图，图按 runId checkpoint，结束清理、进程启动清理遗留；不会加载历史后重放未完成工具。一个 state_dir 只能由一个进程使用。旧 sessions.sqlite 不导入、不再读写；旧服务升级后这些 ID 不属于 Java 会话，需新建对话。实际业务历史保存在 Java 数据库并按其备份/删除策略管理。

默认并发4（无等待队列）、总预算60秒/上限120秒、8次模型/6次工具调用、工具4096 UTF-8字节、总上下文60000字节、输出1024模型 token、SDK重试0。上下文为保守字节预算，并非精确 tokenizer。正文约0.4–0.5秒保存一批，终态立即写入。取消关闭图与 HTTP，另用最多3.5秒尽力保存末尾，撤销委托另有0.5秒；保存失败不承诺末尾必达或供应商立即停止计费。

运行、证据、权限、错误与兼容边界的完整说明见 [会话 API](../../docs/rag/CONVERSATIONS.md)，浏览器代理见 [Web 文档](../../docs/web/CHAT.md)。

## 定向验证

```powershell
uv run --project services/agent-service --locked pytest services/agent-service/tests/test_agent.py examples/integration/rag_agent/test_knowledge_tool.py -q --basetemp=.cache/agent-tests-local
uv run --project services/agent-service --locked ruff check services/agent-service
```

只运行与变更相关的检查，不默认 Maven 全量 verify。真实 Java/PG/MinIO/Python 流式验收和供应商协议桩明确分层，见 [验证回执](../../docs/rag/CONVERSATION_VALIDATION.json)。真实模型效果、业务工具、人机恢复和生产部署仍未验收。
