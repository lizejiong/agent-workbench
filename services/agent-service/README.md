# Python 主 Agent

正式运行入口为 `agent_service.app:app`，公开异步接口为 `POST /api/agent/chat`。使用 LangChain `create_agent` 创建真实 LangGraph 工具循环，主模型通过 Python 的 ChatOpenAI 客户端调用原生 OpenAI Chat Completions 协议。Java 保留知识管理、入库、查询理解、召回、融合、精排、证据组装和独立问答。本服务只调用 Java 证据接口，不调用 `/api/rag/answer`。

## 模块与依赖

```text
src/agent_service/
  app.py                  FastAPI 入口、lifespan、连接池与断连取消
  config.py               可信部署配置；地址和凭据不由请求覆盖
  contracts.py            严格请求与受控错误契约
  runtime.py              回合预算、身份委托、会话上下文、最终引用校验
  graph.py                官方 Agent 图、工具注册、模型和工具调用预算
  sessions.py             Python 自有会话归属、固定知识范围和数据库租约
  knowledge/adapter.py    唯一知识工具适配器；由原 examples 实现提升
  knowledge/delegation.py Java 账号认证、短期委托签发与撤销
  prompts/                中文版本化系统提示与知识工具规则
```

Python 3.12+；使用本目录 `.python-version` 和 `uv.lock` 复现。本轮环境是 Python 3.12.13；锁定 LangChain 1.4.7、LangGraph 1.2.19、langchain-openai 1.7.3、langgraph-checkpoint-sqlite 3.1.3、httpx 0.28.1。FastAPI/Pydantic 由既有 `rag-common` 契约复用，本目录分别锁定 0.162.0 / 2.14.2；没有修改 python-common 的独立锁文件。

## 启动

从仓库根目录执行，Java 先按 [RAG RUNBOOK](../../docs/rag/RUNBOOK.md) 启用内部接口、配置服务令牌哈希，并准备有权限且已入库的知识库：

```powershell
uv sync --project services/agent-service --locked
$env:JAVA_RAG_ORIGIN='http://127.0.0.1:8080'
$env:RAG_SERVICE_ID='python-agent'
# 从秘密管理注入 RAG_SERVICE_TOKEN，与 Java 中的 SHA-256 对应。
$env:AGENT_MODEL_BASE_URL='供应商原生兼容接口的 HTTPS 地址，含 /v1 等实际路径'
$env:AGENT_MODEL='部署选择且支持 tools 的模型名称'
# 从秘密管理注入 AGENT_MODEL_API_KEY。
$env:AGENT_STATE_DIR='C:/受保护的数据目录/agent-service'
uv run --project services/agent-service --locked uvicorn agent_service.app:app --host 127.0.0.1 --port 8090
```

`.env.example` 仅列配置，不会自动读取 `.env`。远程 Java/模型只接受可信 HTTPS URL，本机开发允许 HTTP；连接池禁用环境代理与重定向。不要将 Java `/ai/chat` 填成供应商地址，两者协议不同。模型应支持工具调用，真实供应商能力需单独验收。

`GET /health` 只表示进程存活。正式对外由既有 HTTPS 网关暴露 `/api/agent/*`，不放开匿名模型代理或 CORS。SQLite 文件保存敏感图消息，生产目录须由运行账号独占、设定备份/保留与磁盘加密策略；不对外暴露检查点文件或图调试接口。当前为单机持久化基线；多主机共享图存储、长期消息保留/删除、人工中断恢复、业务 MCP 工具和 Agent SSE 不在本次范围。

## 调用与身份

使用现有 Java Basic 账号认证；Python 在每轮工具调用前将当前账号认证传给 Java 的委托签发接口。不配置万能用户，不接受请求中的 tenantId/userId，不保存用户密码。浏览器/客户端应在 HTTPS 内存会话中处理认证，不在请求示例提交真实值。

```python
import asyncio, os
import httpx


async def main():
    async with httpx.AsyncClient(
        base_url="http://127.0.0.1:8090", timeout=65
    ) as client:
        response = await client.post(
            "/api/agent/chat",
            auth=(os.environ["RAG_USER"], os.environ["RAG_PASSWORD"]),
            json={
                "question": "PostgreSQL 如何备份？",
                "kbIds": [os.environ["RAG_KB_ID"]],
            },
        )
        response.raise_for_status()
        first = response.json()["data"]
        print(first["answer"], first["citations"])
        followup = await client.post(
            "/api/agent/chat",
            auth=(os.environ["RAG_USER"], os.environ["RAG_PASSWORD"]),
            json={"sessionId": first["sessionId"], "question": "备份后如何恢复？"},
        )
        followup.raise_for_status()
        print(followup.json()["data"]["answer"])


asyncio.run(main())
```

首次不传 sessionId，服务端生成 UUID；kbIds/documentIds 仅表达期望范围，Java 负责授权。省略/空库范围遵循 Java 原有范围解析。返回的实际库/文档范围绑定该会话；后续仅传 sessionId 和 question，不允许修改范围，需要改范围时新建会话。每轮重新签发委托并验证会话 tenant/user 归属，跨用户和越权统一不可访问。完成、失败或取消均尽力在 0.5 秒内撤销本轮委托，撤销不可达时由 Java TTL 封顶。

## 响应与上下文

响应使用 `rag-common` 的 `{code,message,data,requestId}`。data 包含 sessionId、runId、traceId、status、answer、citations、受控 retrievals 摘要、模型与提示词版本/哈希。citations 只含实际取得且答案引用的来源映射；fileUrl 是 Java 原授权下载路径，下载时仍须认证。工具只接收 query，身份、范围、委托、deadline 由 ToolRuntime.context 注入，既不进入工具 JSON Schema，也不进入检查点。内部证据及错误契约见 [RAG API](../../docs/rag/API.md)。

主 Agent 接收 READY/NO_EVIDENCE/NEEDS_CLARIFICATION/UNAVAILABLE，按中文提示决定回答或进一步调用；返回 retrievals 让客户端区分状态。模型可自行组织回答，但这不证明其正确遵循所有语义要求。最终编号必须来自本轮实际工具证据；局部 E1 自动映射为请求唯一 K 编号，未知/未闭合引用导致 502/CITATION_INVALID，不进行整轮重放。

图由 AsyncSqliteSaver 保存，Python 自有 sessions.sqlite 保存归属/范围/并发租约。每轮重新授权后读取图，替换旧消息，仅保留最近至多 4 条、共 8000 UTF-8 字节的用户问题；旧 ToolMessage、助手答案和未完成的工具调用不会进入下一轮模型输入，也不自动重放取消后的图任务。新问题需要的证据重新检索。当前没有自动复用旧助手结论或摘要，也不暴露 `Command(resume=...)`。

默认每进程同时 4 回合、无等待队列；同会话持久租约防止并发。总预算 60 秒（最多 120），最多 8 次模型、6 次工具调用，SDK 重试关闭。每个工具结果默认 4096 UTF-8 字节，模型消息加提示词和协议预留最多 60000 字节；这是保守预算，不是目标模型精确 tokenizer。上下文过大返回 413，不静默丢掉当前引用映射。客户端断开/任务取消会取消图和 HTTP，并向 Java 尽力发送取消；清理最多另用约 1 秒。进程崩溃依靠截止时间和租约过期，不承诺供应商停止计费。

合法 W3C traceparent 只用于关联，不授予权限；没有合法值则服务生成 traceId。模型调用及 Java 检索沿用同 traceId，不同调用生成独立 span/requestId。禁用环境可能开启的 LangSmith 内容追踪；常规日志只记录受控类型和关联 ID，不输出原始上游响应。

## 最小验收

```powershell
uv run --project services/agent-service --locked pytest services/agent-service/tests -q --basetemp=.cache/agent-tests-local
uv run --project services/agent-service --locked ruff check services/agent-service
```

仅在需要验证时运行上述定向检查，不默认执行 Java 全量 verify。真实 Java + PG/MinIO + Python ASGI/图联调命令与脱敏结果见 [运行手册](../../docs/rag/RUNBOOK.md) 和 [联调回执](../../docs/rag/AGENT_RUNTIME_VALIDATION.json)。HTTP 协议桩、真实外部供应商验证、语义效果和生产负载分别记录，不能互相替代。
