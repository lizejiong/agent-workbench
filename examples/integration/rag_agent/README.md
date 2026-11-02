# Python Agent 知识工具接入示例

2026-11-02：主 Agent 已接入 [services/agent-service](../../../services/agent-service/README.md)，使用正式 FastAPI 入口和官方 LangGraph 循环。知识适配器已提升至该服务，本目录的 knowledge_tool.py 仅保留兼容导入，不复制算法。以下 demo.py 仍是直接取证示例，不是主 Agent 入口；正式启动和会话调用以 Agent 服务说明为准。

Java 继续负责查询理解、召回、融合、精排、权限与证据组装。原第 9 阶段的回执见 [适配联调](../../../docs/rag/AGENT_INTEGRATION_VALIDATION.json)，新增真实图入口联调见 [Agent 联调](../../../docs/rag/AGENT_RUNTIME_VALIDATION.json)。

直接适配示例仍兼容 Python 3.12+、httpx 0.28.1、FastAPI 0.143.3、Pydantic 2.14.1，无需安装 LangGraph。正式主 Agent 使用自己的 uv.lock，实际版本见服务说明；不要用本示例 requirements.txt 替代主 Agent 锁文件。

## 运行真实调用示例

1. 按原部署流程运行支持 Flyway V10 的 backend、原数据库/对象存储及 AI 服务，不覆盖运行中的 JAR。把 `backend-agent.yml.example` 复制为受控 YAML，通过 Spring Boot 既有配置加载；启用 internal，并配置服务令牌的 SHA-256。Java 保存哈希，Python 保存原始服务令牌；服务令牌使用至少 32 字符的安全随机 URL-safe 值。生产跨服务传输使用 HTTPS。
2. 安装现有锁定环境或示例依赖：

```powershell
uv sync --project packages/python-common --locked
# 或在宿主虚拟环境中：python -m pip install -r examples/integration/rag_agent/requirements.txt
```

3. 从可信运行配置设置以下变量，再执行示例。变量不是模型工具参数，不把真实值提交 Git：

```powershell
$env:JAVA_RAG_ORIGIN='http://127.0.0.1:8080'
$env:RAG_SERVICE_ID='python-agent'
# 从部署秘密管理注入 RAG_SERVICE_TOKEN、RAG_USER、RAG_PASSWORD
$env:RAG_KB_IDS='已入库且有权限的知识库UUID'
$env:RAG_QUERY='PostgreSQL 如何备份？'
$env:RAG_SECOND_QUERY='PostgreSQL 如何恢复备份？'
packages/python-common/.venv/Scripts/python.exe -X utf8 examples/integration/rag_agent/demo.py
```

示例在工具外以用户 Basic 调用 `/api/rag/delegations`，取得短期范围委托；`finally` 撤销委托。正式宿主应在自己的已认证边界完成这一步并安全注入，不让模型接触用户密码或委托。示例输出两次结构化检索与已校验来源映射，业务工具位置为明确的只读桩，没有真实业务调用或 LLM 决策。没有调用 Java `/answer`。

## 接入已有宿主

`lifespan.py` 展示共享 httpx.AsyncClient 的创建和关闭；合并到已有 FastAPI lifespan，而不是新建匿名代理路由。默认 16 连接/8 keepalive、禁用重定向与环境代理，原始服务令牌只存在部署配置与请求头。运行时为每个用户会话创建 `TrustedContext`，为每个 Agent 回合创建 `TurnReferences`：

```python
gateway = app.state.knowledge_gateway
context = TrustedContext.from_grant(grant_from_authenticated_boundary, seconds=20)
references = TurnReferences()
search_knowledge = gateway.bind(context, references)
# 交给宿主已有工具注册器；唯一模型参数是 query: str。
result = await search_knowledge("某产品在禁止外网环境下如何备份？")
```

用户/租户/会话来自签发结果和宿主认证上下文；知识范围来自用户委托，Java 每次重新授权。agentSessionId 是宿主管理的稳定 UUID；若既有会话标识是其他格式，由宿主建立映射。模型不能传入 tenant、user、会话、kbIds、令牌、queryMode、模型或 deadline。`bind` 闭包不得跨用户缓存，引用表也拒绝跨用户、租户或会话复用。

宿主系统提示可按其已有版本管理方式引用唯一的 [中文片段](../../../services/agent-service/src/agent_service/prompts/agent-knowledge-v1.txt)，记录实际选用版本/哈希。正式主 Agent 已加载该片段；本目录不再维护另一份提示词。

## 状态、引用与预算

READY 使用证据回答或继续业务工具；NO_EVIDENCE 说明当前资料不足，可在原范围调整独立问题；NEEDS_CLARIFICATION 补充提问；UNAVAILABLE 说明依赖不可用，只由宿主在剩余预算内决定是否重试。401/403/404 映射 ACCESS_DENIED/retryable=false，不能换其他用户或扩大 scope 兜底。原始错误正文、地址和 SQL 不进入模型。

Java 编号局限于单次检索。Python 输出 `K{本次requestId无连字符}_E1`，保留 localEvidenceId 与真实文档、Chunk、generation、定位和哈希；同一回合多次/并行检索不会因为各自有 E1 而混用。`references.validate_answer(text)` 检查知识引用是否确实取得，拒绝未知 K 编号、未闭合 K 标记和未映射的局部 E 编号。业务工具使用其他命名空间，由宿主合并实际来源集合后验证。编号合法不证明事实正确或语义支持。

工具结果默认 UTF-8 4096 字节上限，作为粗略 Token 预算的保守 guard，不是目标模型精确 tokenizer。裁剪保持证据标识、版本与定位；toolTruncated/toolContentHash 区分裁剪视图与 Java snapshotHash。未展示的候选不进入引用表；元数据也放不下时返回 UNAVAILABLE/TOOL_RESULT_BUDGET，不能伪装未命中。长资料、表格被裁剪后须用更具体问题重查，不能把预览当完整制度。每回合最多 32 次适配调用、引用表初始限制 128；宿主仍负责总上下文、并发工具和模型 Token 策略。

Agent 取消会关闭当前 httpx 响应并保留 CancelledError，然后以最多 0.5s 通知 Java `/internal/rag/requests/{requestId}/cancel`。超时也尝试取消。取消接口与主请求使用同一服务及委托；跨实例通过 DB 控制记录传播，取消先到会阻止后续同 requestId 开始。通知不可达时 Java 总 deadline 仍封顶；这不保证供应商已停止计费或数据库立即取消。

图恢复前先通过宿主认证重新取得当前委托，清理旧敏感 ToolMessage，再用 `gateway.reconfirm(saved_reference, fresh_context, new_references)` 检查当前权限、generation 与 snapshotHash。身份/会话不符或当前检索无法精确确认时返回 None，旧正文不能重新送模。引用表检查点只保存来源定位、查询线索与身份绑定，不保存令牌；不允许仅凭旧编号或链接恢复权限。此目录不读写图检查点，也不写 Java 知识库表。真实图恢复钩子必须在宿主集成，尚未验证。

## 最小验证

```powershell
packages/python-common/.venv/Scripts/python.exe -m pytest examples/integration/rag_agent/test_knowledge_tool.py -q
packages/python-common/.venv/Scripts/python.exe -m ruff check examples/integration/rag_agent
```

4 项适配测试使用 httpx.MockTransport，覆盖工具参数边界、状态/故障、取消关闭、唯一引用、预算、恢复版本与身份。另有 Java 随机端口的真实 Python HTTP 联调，命令见 [RAG RUNBOOK](../../../docs/rag/RUNBOOK.md)。`contract_smoke.py` 通过 stdin 接收受控配置，不把秘密放在命令行，输出脱敏回执。新增正式 Agent 已验证真实 LangGraph 工具循环、检查点恢复策略和 Uvicorn→Java 调用；模型仍为协议桩，真实业务工具、供应商效果及生产部署未验收。
