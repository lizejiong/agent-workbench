# 统一工具系统交付与验收

2026-11-17。实现、迁移、测试、脚本和前端闭环已完成。本文记录验收时的状态：当时未提交、推送或部署；后续按用户指令提交和推送，具体版本以 Git 记录为准。运行配置及 API 见 [TOOLS.md](TOOLS.md)，机器结果见 [TOOLS_VALIDATION.json](TOOLS_VALIDATION.json)，恢复入口见 [TOOLS_PROGRESS.md](TOOLS_PROGRESS.md)。

## 实际仓库与边界

仓库实际为 Agent Workbench：Spring Boot/JDBC/Flyway、React/Ant Design/Vite、FastAPI/LangGraph、PostgreSQL。没有 Nest/Prisma/Vercel AI SDK、rag_ai/models、GraphQueryTool 或 ADMIN/AuthorizationService/AuditService。按本次“以实际代码为准”的要求，复用原 Basic 主体、服务 bearer hash、会话委托、ROLE_KB_OPERATOR、知识服务和业务账本；新增 Web Zod，没有替换技术栈或另建身份系统。读取了 README、架构、流式、历史、授权、检索、上下文及相关代码，未发现适用 AGENTS.md，开始时工作树干净。

保留 SSE v1/v2，新增 SSE v3 data-tool；Python 产生稳定事件，Java 代理并保存业务历史，浏览器只访问 Java。没有引入仓库原本不存在的 NDJSON/AI SDK 链路。本地知识工具直接复用 KnowledgeGateway → Java 检索/证据领域服务，不绕 MCP。没有可复用图谱服务，因此提供 search_knowledge（toolKey=builtin:knowledge_search）和 evidence_read，未虚构 graph_query。

## 修改与完成行为

| 位置 | 交付 |
|---|---|
| agent_service/tools | Pydantic v2 ToolDefinition/Call/Result、AsyncExecutor Protocol、标准 JSON Schema、内部/远端/模型名字映射；可信 ExecutionContext、去重、审计、有限并行、取消、超时、结构限长、独立结果状态 |
| agent_service/react.py、graph.py、runtime.py、context | 沿用 ChatOpenAI/模型配置；rag 兼容路径进入统一执行器；agent 为 StateGraph；完整 call/result 配对、非流式规划、流式最终答案、来源校验、步骤/Token/结果/deadline 预算 |
| tools/mcp.py、app.py、config.py、state_lock.py | 官方 mcp==1.26.0 Streamable HTTP；initialize/分页/调用/同任务关闭和 lifespan 回收；环境凭证引用、允许 origin、定义漂移拒绝；服务身份/audience 和独占目录 |
| backend/tools、Flyway V15 | 配置/发现/显式只读发布/授权/记录 API；run+owner+service+委托绑定；执行前再授权；配置和调用持久审计、去重、过期收口、聊天代理 |
| backend/agent、安全配置 | 原消息/运行/证据账本；MCP 独立来源；按实际送模历史继承依赖；撤权/禁用/修订变化隐藏旧参数、轨迹和派生回答；知识审计详情重新检查 ACL |
| timeline.py、tools/events.py、packages/contracts | 唯一 seq 分配器、终态后无事件、单帧小于64 KiB；稳定 start/success/failure/cancel data-tool；生成 JSON Schema 与跨语言样例 |
| web/src/chat | Zod 校验、RAG/Agent 入口、通用状态卡、脱敏展开、并行稳定顺序、服务端耗时、MCP 独立来源和统一引用数字；历史重新鉴权、清除旧缓存 |
| Vite/Nginx/部署示例 | 全部 API 经 Java；服务凭据留在服务端；代理断连关闭上游，不重放整轮 |
| scripts/tools 与测试 | MCP fixture、HTTP 模型桩、隔离开发栈、smoke/cancel_smoke、Schema 导出、权限/故障/漂移/预算/回放回归 |

工具成功、无结果、参数无效、拒绝、超时、不可用、失败和取消分开。模型即使借远端 additionalProperties 传入嵌套身份/凭据/范围字段也会被拒绝。工具输出不能修改系统权限；URL 只取管理员配置和部署策略，返回的资源链接不自动访问，图片/资源类型有明确限制。

开始许可与审计插入是同一 Java 短事务，写入失败不执行；最终写入失败使运行明确失败，数据库到期任务收口未完成调用。MCP call_tool 前再次检查 run、授权、启用、修订和 Schema hash。新发现工具默认关闭，readOnlyHint 仅参考；每次操作独立会话，不跨用户共享状态或凭据。

## 执行命令与结果

从仓库根目录执行。测试仅使用可丢弃数据库，不能指向业务库。日志在 Git 忽略的 .cache，长期结果保留在本文件、机器记录与 output/tools。

```powershell
uv sync --project services/agent-service --locked
uv run --project services/agent-service pytest services/agent-service/tests -q --basetemp=.cache/tools-python-final-20261117
uv run --project services/agent-service pytest examples/integration/rag_agent/test_knowledge_tool.py -q --basetemp=.cache/tools-knowledge-regression-20261117
uv run --project services/agent-service ruff check services/agent-service/src services/agent-service/tests scripts/tools
uv run --project services/agent-service ruff format --check services/agent-service/src services/agent-service/tests/test_mcp.py services/agent-service/tests/test_react.py services/agent-service/tests/test_tool_failures.py services/agent-service/tests/java_runtime_smoke.py services/agent-service/tests/protocol_fixture.py scripts/tools
npm --prefix web run check
npm --prefix web test
npm --prefix web run build
```

Python 55 passed、原 knowledge_tool 4 passed；Ruff 通过、37个相关文件格式通过；Web 6个文件41项通过，TypeScript 与 Vite build 通过。

```powershell
$env:KB_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55468/atlas_tools'
$env:KB_TEST_DB_USERNAME='atlas_test'
$env:KB_TEST_DB_PASSWORD=''
.\mvnw.cmd -B '-Dmaven.repo.local=.cache/maven/repository' verify
```

根10模块全部 BUILD SUCCESS，各模块汇总328项，0失败/错误、2项条件跳过；其中 backend 219项。涵盖原检索、独立 RAG、引用、聊天、上下文/摘要/偏好、入库和文档回归，以及新增 tools 6项真实数据库集成。保留 database/backup/cooking、topic-vector、真实 PG/MinIO/ES 的确定性基线，没有把协议检查换算成语义质量分数。

为执行那两项有 Python 环境条件的跨语言用例，进一步运行：

```powershell
.\mvnw.cmd -B '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am verify `
  '-Dtest=ToolIntegrationTest,KnowledgeSearchTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dkb.test.python-exe=C:/Users/lzj/Documents/ChatGPT/rag/services/agent-service/.venv/Scripts/python.exe' `
  '-Dkb.test.rag-adapter=C:/Users/lzj/Documents/ChatGPT/rag/examples/integration/rag_agent/contract_smoke.py' `
  '-Dkb.test.agent-python=C:/Users/lzj/Documents/ChatGPT/rag/services/agent-service/.venv/Scripts/python.exe' `
  '-Dkb.test.agent-smoke=C:/Users/lzj/Documents/ChatGPT/rag/services/agent-service/tests/java_runtime_smoke.py'
```

BUILD SUCCESS，backend 36项实际执行，0失败/错误/跳过，包含前述两项。重跑用例不重复计入独立测试总数。该命令重新编译了最终 ToolLedger，包括知识 ACL 对审计详情的隐藏。

主要日志：tools-python-final-20261117.log、tools-knowledge-regression-20261117.log、tools-web-tests-20261117.log、tools-web-build-20261117.log、tools-java-verify-20261116-green.log、tools-java-cross-language-20261117.log。

## 完整 HTTP 与浏览器闭环

独立 pgvector 容器、atlas_tools_smoke 库、真实 Java/Python/MCP，模型为明确 HTTP 桩。最终 smoke 使用最后定向 verify 的 Java 构建和当前 Python 源码：

```powershell
$env:ATLAS_TOOL_FIXTURE='true'
uv run --project services/agent-service python scripts/tools/dev_stack.py --db-url jdbc:postgresql://127.0.0.1:55468/atlas_tools_smoke --backend-jar .cache/tools-backend-delivery-20261117.jar --state-dir .cache/tools-dev-stack-delivery-20261117 --backend-port 8087 --agent-port 8097 --mcp-port 8767 --model-port 8768
# 另一个终端；复用此前创建的本任务隔离测试服务。
uv run --project services/agent-service python scripts/tools/smoke.py --fixture --origin http://127.0.0.1:8087 --mcp-url http://127.0.0.1:8767/mcp --server-id aa585b01-69ad-4b5a-a81d-dcae4bd7e16a --leave-enabled --output output/tools/smoke-enabled-final.json
uv run --project services/agent-service python scripts/tools/cancel_smoke.py --origin http://127.0.0.1:8087 --output output/tools/cancellation-smoke.json
uv run --project services/agent-service python scripts/tools/smoke.py --fixture --origin http://127.0.0.1:8087 --mcp-url http://127.0.0.1:8767/mcp --server-id aa585b01-69ad-4b5a-a81d-dcae4bd7e16a --output output/tools/smoke.json
```

上述 jar 副本和 serverId 仅属于本任务验证。新环境直接使用 dev_stack 默认 jar 路径、去掉 --server-id 即可完整演示创建、发现、默认关闭、发布、授权、执行、审计及禁用，详见运行手册。重复验收复用自己回执中的服务 ID。

- [最终 smoke](../../output/tools/smoke.json)：2个并行 MCP 成功、21条连续 seq 事件、帧限长、Java 历史、2条成功审计、脱敏和禁用隐藏。
- [取消 smoke](../../output/tools/cancellation-smoke.json)：慢工具 start 在完成前到达；显式停止与真实 TCP 断开均使 run=CANCELLED、tool=cancelled，不重执行。
- [真实供应商 smoke](../../output/tools/provider-smoke.json)：使用已有受保护配置，1个 MCP 工具成功、73条连续事件、原生流式答案、历史/审计/脱敏/禁用隐藏通过；凭据未写入回执、截图或可见命令。这是小规模真实模型冒烟，未评价广泛工具选择或事实综合质量。
- Browser/React：重新登录读取 Java 历史，同批卡片稳定顺序、服务端耗时、MCP 1/2引用；展开结果 password/access_token 显示隐藏。禁用后重新选择相同历史，参数、轨迹和相关旧答案隐藏。截图为 [时间线](../../output/tools/browser-timeline.png)、[展开脱敏](../../output/tools/browser-replay.png)、[禁用隐藏](../../output/tools/browser-revoked.png)。

Python 用例覆盖单/多工具、多轮、非法 JSON/Schema、未知工具、重复供应商 ID、tools 不支持、空授权集、预算和真实模型流取消。官方 MCP fixture 覆盖结构结果、大结果、isError、类型限制、同名路由、分页/重复 cursor、故障、超时/取消、凭证隔离和 Schema 漂移。Java 集成覆盖默认关闭、角色/租户/run/service 绑定、撤权、持久脱敏、历史依赖、漂移和到期审计。Web 消费 Python 事件样例，验证全部终态与来源类型隔离。

## 剩余边界与资源收尾

- 单 Java/单 AI worker；目录锁拒绝第二 worker，PostgreSQL 保存运行与取消。没有多 AI worker 共享 SQLite、跨实例图恢复或事件重放。
- MCP 仅 Streamable HTTP、环境 bearer credentialRef；不同远端用户需独立配置及授权。没有用户 OAuth、业务写工具、完整管理后台、多级审批或对外 Atlas MCP Server。
- MCP 来源为受配置版本/Schema hash/当前授权校验的已取得快照，不等同远端数据始终实时有效。具体服务业务准确性、广泛模型语义、吞吐/SLO、生产代理/镜像部署未纳入结果。
- 固定 SDK 兼容函数有弃用提示，fixture 有 Pydantic lifespan 前向引用警告。全仓 Java 验证中，原后台任务在 Testcontainers 关闭后有连接日志，Surefire 有 fork JVM 退出超时后终止诊断；全部断言和构建通过。未将这些结果解释为生产性能保证。
- 本次创建的 Java/Python/MCP/模型桩、两个 Vite、临时 Windows PostgreSQL 已停止，相关端口核查为空。专用 atlas-tools-test-20261116 容器已停止并保留数据；原知识库 PostgreSQL/MinIO 未停止。未迁移原业务库、未修改既有真实模型配置。

新增本地工具、接入 MCP、配置权限、查看失败和下线步骤均在 [TOOLS.md](TOOLS.md)。
