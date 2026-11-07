# RAG 运行手册

## 回答偏好升级（2026-11-07）

先备份PG并部署Java V14，再更新Python/Web；无新增依赖或环境变量。设置位于聊天页账号菜单，404检查代理：仅 `/api/agent/chat` 及其流路径走Python，preferences及reset走Java；Vite和Nginx均已调整。409先重新加载，INVALID_PREFERENCES检查两端版本；不要自动重试写入或整轮生成。契约和实际验证见 [PREFERENCES.md](PREFERENCES.md)、[PREFERENCES_VALIDATION.json](PREFERENCES_VALIDATION.json)。下文按日期保留历史要求，当前版本需V14。

## 上下文第一轮升级（2026-11-07）

第一轮只需要支持history turns视图的Java；当前含摘要的版本需按文末完成V13迁移后再升级Python，不改Web。旧Python仍可读取原history数组，新Python遇到旧Java响应会明确拒绝INVALID_HISTORY。Java打包继续使用未被运行进程占用的隔离目录。

上下文环境变量在 `.env.example` 明确列出，但程序不自动读取该文件：AGENT_MODEL_CONTEXT_TOKENS=32768、AGENT_MAX_OUTPUT_TOKENS=1024、AGENT_CONTEXT_SAFETY_TOKENS=2048、AGENT_CONTEXT_BYTE_BUDGET=60000、AGENT_CONTEXT_SOFT_RATIO=0.8、AGENT_HISTORY_TURNS=6、AGENT_MAX_MODEL_CALLS=8、AGENT_MAX_TOOL_CALLS=6、AGENT_TOOL_BYTE_BUDGET=4096。AGENT_MODEL_PROVIDER为追踪标识；窗口是部署方确认的应用上限，不根据模型名称猜测。

AGENT_TOKEN_COUNTER默认utf8保守估计；只有确认目标模型的分词方式才设为tiktoken并配置AGENT_TOKEN_ENCODING。不能仅因供应商兼容OpenAI协议就填OpenAI encoding。编码器配置在启动时检查；首次加载BPE资源可能需要预置tiktoken缓存。所有计数仍包含协议估算及安全预留，usage存在时只在本轮向上校准，不保证精确Token计费。计数器配置错误会启动失败，不留下RUNNING业务请求。

排障：根据traceId查agent_context日志中的策略版本、输入字节/估计Token/计数器/投影批次。CONTEXT_BUDGET表示当前受保护输入无法在预算内继续，请缩小问题或新建会话；不能改报NO_EVIDENCE。CONTEXT_PROTOCOL检查工具ID和完整批次；INVALID_HISTORY检查Java版本和分页游标。当前不保留超过预算的近期轮次片段，不提供长期记忆；受授权摘要按文末第二轮配置启用。

定向验证：`services/agent-service/.venv/Scripts/python.exe -m pytest services/agent-service/tests/test_context.py services/agent-service/tests/test_agent.py -q --basetemp=<工作区内全新专用目录>`；Java仅运行KnowledgeSearchTest的完整轮次分页、历史授权/撤权与会话幂等方法。依赖锁检查 `uv lock --check --offline --project services/agent-service`。

## 工具与引用展示验收（2026-11-07）

更新 Web 即可，不需要数据库迁移或重启 Java/Python。`cd web` 后执行 `npm test -- src/chat/sourcePresentation.test.ts src/chat/format.test.ts src/chat/stream.test.ts` 与 `npm run build`。浏览器选择已有工具记录，检查折叠/展开、引用悬浮与键盘预览、点击精确片段、同版本片段切换、刷新恢复与375px布局；历史加载不应新增 Agent 执行请求。故障提示不等同于“资料不存在”，无位置元数据时不显示编造的页码。详情见 [CHAT_PRESENTATION.md](CHAT_PRESENTATION.md)。

## 有序块升级与排障（2026-11-06）

先备份 PostgreSQL，部署 Java 并确认 V12，再重启 Python 单 worker，最后更新 Web。必须在未被运行进程占用的隔离目录构建 JAR。旧消息 blocks 为空不会补造执行顺序。检查 v2 响应头、runId/messageId/seq、Java blocks/eventSeq；缺帧或断连仅查询历史，不重放整个 Agent 请求。详见 [TIMELINE.md](TIMELINE.md)。

## Java 业务会话与 Python SSE（2026-11-05）

升级顺序：先备份 Java PostgreSQL 与受保护 Agent 状态目录，部署 backend 并确认 Flyway V11，再升级 Python（`uv sync --project services/agent-service --locked`、单 worker），最后更新 Web。V11 是新增 Agent 领域表，不迁移 Java 独立问答或自动导入旧 Python 图历史。旧 sessions.sqlite 不再读写；按现有保留策略人工归档/删除，不把旧图中未经验证的来源变成新业务历史。

服务身份/用户委托继续使用原 `kb.rag.internal` 配置。每次 Python 执行都按当前用户取得会话绑定委托，不能使用固定万能用户。Java 不可用时停止模型执行或保存并准确报告失败，不切换到 Python 本地历史库。AGENT_STATE_DIR 只能供一个 Python 进程独占；重启会清理遗留执行图，Java 运行按持久 deadline 结算，不自动重放。

代理中 `/api/agent/chat` 及其流路径发往 Python，偏好及 `/api/conversations/*` 发往 Java。排障先用当前用户 GET 会话消息或 run；RUNNING 在 deadline 后最多约5秒清扫为 TIMED_OUT，部分文本保留。检查 meta 的 runId/消息 ID 与 Java requestId/traceId；这些关联字段不授予权限。checkpoint 冲突409通常表示重复标识内容变化或旧执行，不能通过新 ID 盲目重放业务操作。

SSE 无 done、persisted=false 或代理断连时先读 Java，禁止把全部失败当作无资料。取消后短预算保存可能滞后，Web 只读复查；异常终态工具显示 UNAVAILABLE，不显示成仍执行。删除清除业务消息/快照，图由 Python 生命周期清理。生产 Nginx、真实模型与容量仍需单独验收。

定向命令（在隔离构建副本执行 Java，先同步源码，不覆盖运行中的 JAR）：

```powershell
.\mvnw.cmd -pl services/backend -am '-Dtest=KnowledgeSearchTest#agentConversationHistoryIsOwnedOrderedIdempotentAndNeverReexecutes+agentHistorySnapshotsAreReauthorizedBeforeDisplayAndModelHistory+pythonMainAgentRunsGraphAgainstJavaWithUserDelegationAndValidatedSources' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dkb.test.agent-python=实际工作区/services/agent-service/.venv/Scripts/python.exe' '-Dkb.test.agent-smoke=实际工作区/services/agent-service/tests/java_runtime_smoke.py' test
uv run --project services/agent-service --locked pytest services/agent-service/tests/test_agent.py examples/integration/rag_agent/test_knowledge_tool.py -q --basetemp=.cache/agent-tests-local
python scripts/rag/build_conversation_openapi.py
```

Java 依赖 Docker 中独立 PG/pgvector 与 MinIO；显式 agent-python 使跨语言方法实际执行，跳过不能计为通过。回执为隔离副本 target/knowledge-base/agent-runtime-contract.json，分层结果见 [CONVERSATION_VALIDATION.json](CONVERSATION_VALIDATION.json)。以下旧轮次说明按其日期保留。

已交付共享检索、Java 普通/流式独立问答及 Python 主 Agent 正式入口。依赖、账号、对象存储与入库流程继续使用 [知识库运行手册](../knowledge-base/RUNBOOK.md)。Java 问答随 backend 启动；Python 是独立进程，启动、配置与调用见 [Agent README](../../services/agent-service/README.md)。

## Python 主 Agent 正式入口（2026-11-02）

Web 已接入：先启动原 Java 和 Python Agent，再在 web 目录设置 `KB_BACKEND_ORIGIN` / `KB_AGENT_ORIGIN` 并运行 `npm run dev`，默认分别为 8080/8090；登录进入聊天页。生产 Nginx 使用 `KB_AGENT_UPSTREAM`，与 Vite 配置分开，原知识库 Compose 不自动增加 Agent。接口缺口、模板配置和限制见 [Web 说明](../../web/README.md) 及 [聊天契约](../web/CHAT.md)。

先运行支持 V10 且启用 internal 的 Java；配置服务令牌哈希，原始令牌只注入 Python。执行 `uv sync --project services/agent-service --locked`，按 `.env.example` 注入 Java origin、服务 ID/令牌、原生主模型地址/名称/密钥及受保护状态目录，再执行：

```powershell
uv run --project services/agent-service --locked uvicorn agent_service.app:app --host 127.0.0.1 --port 8090
```

接口为 Python 的 `POST /api/agent/chat`，沿用原账号 Basic；不要给它匿名代理、任意 origin 或万能用户。默认 60 秒、每进程 4 回合、无等待队列；同会话由 SQLite 租约拒绝并发。状态目录保存 Python 会话与敏感检查点，生产按私有目录保护并持久挂载；当前没有多主机图存储和自动保留/删除调度。主模型直接使用支持 tools 的原生 Chat Completions 协议，不填 Java `/ai/chat` 地址。

排障只看受控 code、requestId/runId/traceId 和版本：401 重新认证；404 检查本人会话/当前知识范围；409 等待同会话回合结束，崩溃后由 lease_until 过期释放；503 区分 Java 身份服务、工具检索、主模型及本地容量；504 检查总预算，不盲目重放。工具 UNAVAILABLE 与 NO_EVIDENCE 分开，不把原始异常发给模型。SDK 自动重试关闭，取消会中断图和 HTTP，Java 取消通知/委托撤销各最多另用 0.5 秒，进程崩溃依赖 Java deadline/TTL 和 Python 租约过期。

正式图入口的跨语言验收仍使用隔离 Java 构建目录和现有 dataset；增加一项方法，不默认重跑全部 Java：

```powershell
.\mvnw.cmd -B -ntp -f .cache/rag-development-20261031/pom.xml '-Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository' -pl services/backend -am '-Dtest=KnowledgeSearchTest#pythonMainAgentRunsGraphAgainstJavaWithUserDelegationAndValidatedSources' '-Dkb.test.agent-python=C:/Users/lzj/Documents/ChatGPT/rag/services/agent-service/.venv/Scripts/python.exe' '-Dkb.test.agent-smoke=C:/Users/lzj/Documents/ChatGPT/rag/services/agent-service/tests/java_runtime_smoke.py' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

路径按实际工作区替换，并先同步源到独立构建目录；不要覆盖本地运行 JAR。只有显式提供 agent-python 时才执行该方法，跳过不算通过。测试 stdin 注入临时配置，启动真实 Uvicorn TCP，走官方图循环、两次 Java 检索和答案来源返回，并确认委托已撤销、Java 未创建独立问答会话。receipt 在隔离副本 `services/backend/target/knowledge-base/agent-runtime-contract.json`；脱敏汇总见 `AGENT_RUNTIME_VALIDATION.json`。模型是原生协议桩，不算真实供应商效果或部署。

Python 仅运行服务 tests 的定向检查与原适配器 4 项回归，命令见 README；新增检查点/会话状态由 Python 管理，Java 不做迁移。没有新增业务 MCP 或前端页面，也未部署。

## 构建与验证

仓库根目录，JDK 17；Docker 用于隔离集成测试，不连接当前业务库。不要为运行测试设置指向业务库的 KB_TEST_JDBC_URL，该可选测试会清空目标表。已有进程占用 JAR 时先复制构建源到独立目录，绝不在运行目录执行 package/verify。本轮根目录打包遇到此问题后的恢复和独立产物见 PROGRESS。

开发时先选择与改动直接相关的已有测试类或方法，一次 `test` 同时完成编译与定向验证。例如修改向量调用预算时：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am '-Dtest=VectorSearchDeadlineTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

需要测试类中的单个场景时，可将 `-Dtest` 改为 `类名#方法名`。`surefire.failIfNoSpecifiedTests=false` 仅允许上游模块没有匹配用例；核对目标模块报告确实执行了选中的测试。文档和注释改动通常只执行 `git diff --check`，不启动 Maven 或 Docker。

阶段验收根据影响范围选择相关集合。下面是检索预算、模型客户端、向量和混合检索的可选组合，按实际改动缩小；纯向量改动通常不需要启动 Elasticsearch：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am '-Dtest=HybridRetrievalBudgetTest,RerankDeadlineTest,VectorSearchDeadlineTest,ElasticsearchDeadlineTest,AiEmbeddingClientTest,KnowledgeSearchTest,HybridRetrievalTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本机可给测试增加 `-Dkb.test.elasticsearch-image=atlas-rag-elasticsearch:latest`，它是缓存的 ES 8.19.17，使用新容器和数据；不引用其他项目的数据卷。其他环境默认使用测试声明的公开 ES 8.19.17。Docker 不可用时集成测试会跳过，不能据此认定 PG/ES 已验证。

权限、启停、删除、activeGeneration、向量空间、维度校验或依赖失败的规则发生变化时，选择覆盖对应边界的已有集成用例；缺少覆盖才补测试。检查已通过且相关代码未再变化时复用结果，阶段结束不自动重复全量测试。

全量验证按显式要求、发布流程或实际影响范围触发，在没有运行中 JAR 占用的隔离构建目录执行：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' verify
```

实际执行、未执行和复用的验证范围写入 PROGRESS；开发期缩减验证不等于完成真实供应商效果或生产负载验收。

## 启动与依赖

查询理解默认 `kb.rag.understanding.enabled=false`；开启后沿用 `kb.ai` 的 AI 服务连接，model 默认 qwen-plus、timeout 默认 5s、max-sub-queries=3、max-output-tokens=512。可维护 `kb.rag.understanding.terms` 映射，例如 `PG: PostgreSQL`；包含编号/数字的术语映射必须保留原编号。配置修改后重启，映射快照哈希进入 query.termVersion。JSON 结构错误、路由越权或模型失败在总 deadline 内回退规范化查询，返回明确降级原因。

多查询召回：`kb.retrieval.max-sub-queries=3`，`max-total-candidates=300`；与原 `channel-candidates=50`、`fusion-candidates=20`、最终 topK=5 分开。每通道实际召回量不超过总额除以查询数×通道数，服务端验证总额至少覆盖配置中的单通道和融合容量。子查询分批复用原零队列有界执行器，不为子查询建立新的线程池或新的 deadline。RRF 常数与权重继续使用既有配置。

精排：`kb.retrieval.rerank.failure-mode` 默认 BEST_EFFORT，可设 REQUIRED；REQUIRED 要求 enabled=true。默认 min-score 不设置；如需要门槛，必须结合已标注样本校准，并同时启用精排。当前策略逐条丢弃弱候选，记录 BELOW_THRESHOLD；精排故障时不使用 RRF 分冒充模型分。精排仍使用既有 AI 服务、qwen-rerank 别名、timeout 和 max-input-chars；输入策略版本为 display-content-v1。

中文提示词集中在 `services/backend/src/main/resources/rag/prompts`，维护说明见同目录 README。`kb.rag.prompts.query-version` 默认 query-understanding-v1；修改行为时新增版本，服务端代码继续校验输出。`kb.rag.prompts.directory` 可指定部署方受控模板目录；必须提供选中版本的同名 UTF-8 `.txt`，重启后使用。实际版本和 SHA-256 都会追踪，普通用户不能指定模板文件或读取模板正文。

证据预算配置前缀 `kb.rag.context`：window-tokens=8192、system-reserve=1024、output-reserve=1024、safety-reserve=512、top-k=5、per-document=2、per-query=3、per-source=2、neighbor-radius=0（可设 1）。window-tokens 须由部署方按实际生成模型设置；当前没有模型专用 tokenizer。AI 服务的 `/ai/utils/estimate-tokens` 是既有粗略计数工具，结果以 estimated=true 加 25% 余量处理，同时有保守字节限制。每次非空上下文只请求一次估算；空范围/空证据不调用该工具。

邻居启用后给补充块预留一个条数位置，仍受文档、子问题、章节/sheet 配额约束。409/RAG_EVIDENCE_CHANGED 应重新检索；422/RAG_CONTEXT_BUDGET 应缩小问题/历史或调整与目标模型匹配的预算，不能通过跳过权限或版本检查恢复。估算失败是依赖错误，不转换成无相关资料。

沿用 `deploy/knowledge-base/compose.yml` 和现有私有 `.env`，不覆盖已有凭据。PG/pgvector、MinIO、AI 服务按知识库手册启动；backend 的 `KB_AI_BASE_URL` 指向已有 AI 服务。关键词与 rerank 默认为 false，向量可以独立工作，不要求 ES 健康才能启动。

```powershell
java -jar services/ai-service/target/rag-ai-service-0.1.0-SNAPSHOT.jar
java -jar services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar
```

以上命令假定已在各进程的受控环境中注入现有供应商、数据库、存储和账号配置；直接启动 JAR 不会读取 Compose .env。已有业务进程运行时，在独立目录打包，部署切换由现有发布流程完成。本轮新功能没有切换到本机运行实例；进程仅为恢复打包改写的原阶段运行包而重启，恢复后健康和授权读取已验证。

本轮新包为 `services/backend/target/rag-backend-0.1.0-SNAPSHOT-rag-r1.jar`（也保留 `.cache/rag-r1-build/services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar`）。验收/切换 R1 时使用这个包，而不是当前恢复的 stage10 默认文件；不要在已占用 8080 的进程旁直接启动第二个 worker 连接同一业务库。

| 配置 | 默认 / 含义 |
|---|---|
| KB_RETRIEVAL_DEADLINE | 40s，检索引擎总预算；优先于旧 KB_SEARCH_MODEL_DEADLINE，未设置新值时兼容旧值 |
| kb.search.deadline | 对应 Spring 属性；现在覆盖授权、召回、精排、组装、最终授权复核，而非仅 embedding |
| KB_RETRIEVAL_CONCURRENCY | 4，原 ThreadPoolTaskExecutor，固定大小、零队列；授权和最终处理也消耗此容量 |
| KB_RETRIEVAL_VECTOR_TIMEOUT / KB_RETRIEVAL_KEYWORD_TIMEOUT | 40s / 5s；还受总预算限制 |
| KB_RERANK_ENABLED / TIMEOUT / MAX_INPUT_CHARS | false / 10s / 30000；HTTP deadline 受剩余总预算限制 |
| KB_AI_CONNECT_TIMEOUT / CALL_TIMEOUT | 5s / 30s，沿用现有模型传输；实际等待还受本次 deadline 限制 |
| KB_ES_CALL_TIMEOUT | 3s，每个 ES 调用；首次 schema 验证也受查询 deadline 限制 |
| KB_DB_CONNECTION_TIMEOUT_MS / STATEMENT_TIMEOUT_MS | 5000 / 10000；残留 JDBC 操作的现有资源保护 |

检索总预算须正值且最多 2 分钟。反向代理响应超时应大于该预算并留序列化/发送余量。减小 budget 可能使原本成功的请求转为明确 504，这是总预算的预期约束，不能通过无限重试恢复。

## 只读检索验收

在安全终端设置现有 KB_USERNAME / KB_PASSWORD，选取该用户已入库的 KB_ID。请求不会改变资料，但会调用配置的模型。

```powershell
$ragAuth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:KB_USERNAME+':'+$env:KB_PASSWORD))
$ragHeaders=@{Authorization='Basic '+$ragAuth; 'X-Request-Id'='rag-check-'+[guid]::NewGuid().ToString()}
$ragBody=@{query='PostgreSQL 备份'; kbIds=@($env:KB_ID); strategy='AUTO'; rerank=$false; topK=5}|ConvertTo-Json
Invoke-RestMethod -Method Post -Uri ($env:KB_BASE_URL+'/api/knowledge/retrieve') -Headers $ragHeaders -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody))
```

检查实际 strategy、channels、status/outcome、trace 和真实来源；keywordConfigured=true 只说明配置。要检查实际 HTTP 头可将 Invoke-RestMethod 改为 Invoke-WebRequest；search 仍返回数组并包含新增状态头，两个接口均应 no-store。全量现有数据创建/生命周期验收命令仍在知识库 RUNBOOK，明确会保留样例，不在生产环境注入故障。

## 排障与验收边界

第 7 轮启用 Flyway V9（kb_rag_conversation、kb_rag_answer_run）；沿用原数据库迁移流程。`kb.rag.answer` 默认 enabled=true、model=qwen-plus、timeout=60s（最多 2 分钟）、max-output-tokens=1024、max-answer-chars=16384、repair-citations=true。模型别名必须在 AI 服务实际存在；输出上限不得超过 context.output-reserve。不启用查询理解也可独立问答。

中文模板选择：`kb.rag.prompts.answer-version=rag-answer-v1`、`repair-version=rag-citation-repair-v1`，与 query-version 共用受控 directory；部署目录须包含所有选中的模板。先增加新版本文件、再切换配置并重启。缺文件、非 UTF-8、空白或超大模板启动失败，不自动换模板。

验收：用已入库自有库调用 `POST /api/rag/answer`，保存返回 answerId/conversationId；读取 `/api/rag/runs/{id}` 和 `/api/rag/conversations/{id}`。5xx 读取错误 data 的运行标识排障；NO_EVIDENCE 不等于依赖故障，CITATION_INVALID 不等于完整成功。历史失效来源不会返回正文，unavailableSources 提示原编号；文件下载始终执行当前权限。

第 7 轮定向命令选择 `-Dtest=KnowledgeSearchTest#standaloneAnswersPersistReferencesAndNeverReadAnotherUsersConversation+answerNoEvidenceCitationRepairAndModelFailureHaveDifferentPersistentOutcomes+conversationConcurrencyAndRevocationDuringGenerationCannotProduceSuccessfulAnswers`，其余隔离构建参数沿用上文。3 项真实 PG/MinIO 集成回归已通过，Chat 为 HTTP 桩，无全量 verify。

## SSE 验收与取消

沿用 V9，无新外部服务。`kb.rag.execution.concurrency=4` 控制每实例问答工作池及 SSE 写池，各自零队列；AI 流与普通调用共用 `kb.ai.concurrency`。单流缓存 32 事件，写线程空闲每秒发心跳；慢连接不会无限积压。HTTP connect-timeout 沿用 kb.ai，流的读写空闲上限为 kb.ai.call-timeout（默认 30s），整流 Call timeout 取 answer.timeout 剩余量。SseEmitter timeout 为 answer.timeout+2s，给正常终态发送留少量时间；不能依赖该余量继续生成。

示例请求文件用 UTF-8 保存 `{"question":"PostgreSQL 如何备份？","kbIds":["实际已授权库UUID"],"strategy":"VECTOR"}`，用已有认证配置环境变量验收：

```powershell
curl.exe --no-buffer --user "$($env:KB_SMOKE_USER):$($env:KB_SMOKE_PASSWORD)" -H "Content-Type: application/json" --data-binary '@answer-request.json' http://localhost:8080/api/rag/answer/stream
curl.exe --user "$($env:KB_SMOKE_USER):$($env:KB_SMOKE_PASSWORD)" -X POST http://localhost:8080/api/rag/runs/实际runId/cancel
curl.exe --user "$($env:KB_SMOKE_USER):$($env:KB_SMOKE_PASSWORD)" http://localhost:8080/api/rag/runs/实际runId
```

反向代理关闭该路径缓冲，连接空闲超时须允许心跳和总预算，客户端按空行分帧，逐个解析 data JSON。done 只表示结束，依据 finish.status/persisted 决定结果；连接断开后 GET 查终态，不能整流自动重放。无证据/澄清没有模型 usage，多个增量可共同组成一个引用。

主动取消应得到 CANCELLED 和部分文本；关闭真实客户端连接后，下一次写/心跳检测断连并取消。下游 OkHttp Call.cancel 与线程中断都执行，HTTP 超时/缺 done 不能成功落库。每秒监控也消费其他实例写入的取消状态并保存部分文本；旧进程 RUNNING 最迟在持久 deadline 到期后的清扫中标 TIMED_OUT。JDBC/Servlet 底层 I/O 的即时停止仍受原驱动、socket、statement_timeout 和容器限制，未做生产慢客户端/容量压测。

排障查看 requestId/runId、状态、errorCode、模型/提示词哈希及证据版本；不记录完整资料或供应商错误正文。RAG_RUN_PERSISTENCE_UNAVAILABLE/UNKNOWN 表示数据库结算未确认，恢复依赖后重读运行；未过期 RUNNING 等截止清扫。不要手工将部分答案改成 COMPLETED。

第 8 轮定向方法：`KnowledgeSearchTest#realSseDeliversIncrementalTextAndPersistsOnlyTheCompletedReferenceSet+realSseDistinguishesNoEvidenceInvalidCitationsBrokenUpstreamsAndTimeouts+realSseCancelAndDisconnectCloseUpstreamAndExpiredRunsRecoverWithoutReplay`，同时复用上面的 3 项普通问答回归和 QueryUnderstandingServiceTest。真实网络验证使用 Spring MVC 随机端口、OkHttp 客户端、PG/MinIO 与本地 HTTP 模型桩，不依赖现有运行实例。

## 第 9 阶段：内部证据与 Python 适配

以下为第 9 阶段原备用交付的适配记录，正式主 Agent 以本页开头为准。直接取证示例见 `examples/integration/rag_agent/README.md`，依赖固定 httpx 0.28.1、FastAPI 0.143.3、Pydantic 2.14.1，Python 3.12+。Java 沿用原服务启动，Flyway V10 新建委托/取消控制表，不改变索引或 Agent 图数据。

`kb.rag.internal.enabled=false` 默认关闭。启用时通过受控 YAML 配置 `service-token-hashes.<service-id>` 为安全随机服务令牌的 SHA-256（64 位小写十六进制）；不要配置原始用户/租户代替授权。示例配置位于 `backend-agent.yml.example`，复制后按原 Spring 配置机制加载。原始令牌只在 Python/部署秘密管理，用户在原 Basic 认证边界签发范围委托；服务令牌单独不能查数据。委托默认 delegation-ttl=5m（最多 15m），concurrency=4 的内部零队列工作池复用原检索门面。跨主机使用 HTTPS，内部路径不应映射为匿名浏览器代理。

宿主 FastAPI lifespan 复用一个 AsyncClient；每回合为工具绑定可信身份/会话/范围、委托、剩余 deadline 和 traceId。deadline 由 Java 再限制，取消通知最多 0.5s，无整请求自动重放。服务凭据或委托过期返回 401，先在用户边界重新授权；超范围 403 不得扩大范围兜底。429/502/503/504 按工具 retryable 与宿主总预算处理；HTTP 错误不是 NO_EVIDENCE。内部 requestId 复用 409，重试必须新建 ID。

委托撤销通过本人 `DELETE /api/rag/delegations/{id}`；进行中的请求返回前复核，监控消费撤销/跨实例取消。控制表无查询正文，过期委托一天后每分钟最多批量清理 1000 条并级联清理控制记录。RAG_TOOL_TIMEOUT 为总截止，原 KB_MODEL_TIMEOUT 为模型 HTTP 超时；RAG_TOOL_CAPACITY 是内部池拒绝，RAG_DELEGATION_INVALID 为过期/撤销或当前用户失效。只按 requestId/traceId 和受控 code 排障，不把令牌、SQL、资料或原始响应放进 Agent 日志。

定向 Java 验证使用隔离构建副本，避免覆盖运行 JAR：

```powershell
.\mvnw.cmd -B -ntp -f .cache/rag-development-20261031/pom.xml '-Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository' -pl services/backend -am '-Dtest=KnowledgeSearchTest#internalToolUsesUserDelegationCurrentScopeAndIndependentTraceAcrossHttp+internalToolCancellationBeforeAndDuringRecallNeverProducesEvidenceAndFailuresStayUnavailable,KnowledgeBaseSecurityConfigurationTest,QueryUnderstandingServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

真实跨语言验收另选 `KnowledgeSearchTest#pythonAdapterCallsRunningJavaEvidenceServiceWithUniqueReferencesAndSharedTrace`，追加本机 `-Dkb.test.python-exe=C:/Users/lzj/Documents/ChatGPT/rag/packages/python-common/.venv/Scripts/python.exe` 和 `-Dkb.test.rag-adapter=C:/Users/lzj/Documents/ChatGPT/rag/examples/integration/rag_agent/contract_smoke.py`。该方法只有显式提供 Python 路径时执行；未配置而跳过不能算联调通过。凭据用进程 stdin 注入，不出现在命令行。真实样例回执在隔离副本 `target/knowledge-base/agent-tool-contract.json`，已提交脱敏汇总见 `AGENT_INTEGRATION_VALIDATION.json`。

本轮上述 Java 9 项与真实跨语言 1 项均通过、0 跳过；Python 4 项和 ruff 通过，FastAPI lifespan 关闭验证通过。未启动真实 LangGraph、业务 Agent、付费模型或生产部署。恢复图前的身份校验与旧消息清理须由宿主接入，不会因示例提供 reconfirm 就自动生效。

- 404：核对当前账号归属和请求范围；不要换成管理身份绕过检查。
- 409/KB_SEARCH_MODEL_MISMATCH：按兼容嵌入空间分别请求；不能更换向量模型来规避错误。KB_SEARCH_SCOPE_CHANGED 表示请求期间绑定变化，重新读取并提交一个新请求。
- DEGRADED/INDETERMINATE：看 channels.errorCode 与 rerankStatus，修复 ES 同步/精排依赖；不是“已证明无知识”。
- 503/KB_SEARCH_CAPACITY：原零队列执行器已满，调用方有限退避；评估 Tomcat、检索池、AI 并发和数据库连接池的整体容量。
- 504/KB_SEARCH_TIMEOUT：检查总耗时、模型/ES 延迟、DB 锁和连接池；精排不再获得额外完整预算。KB_MODEL_TIMEOUT 保留原模型 HTTP 错误语义。
- 中断请求后 HTTP Future 会取消；JDBC 语句终止仍依赖数据库超时。当前 JSON MVC 的浏览器断连不保证立刻触发中断，服务仍在总预算内结束。

不记录完整 query、资料正文、账号密码或模型原始响应。普通返回只含脱敏 trace，operator 指标权限沿用原规则。响应禁缓存不等于外部客户端无法保存已获授权资料，撤权无法收回此前已下发的内容；本服务不维护可绕过复核的结果缓存。

模拟故障、真实 PG/MinIO/ES、真实供应商模型和语义效果分别记录，结果见 PROGRESS 与 EVALUATION。本轮不宣称应用镜像、生产网络、LLM 答案质量或负载已验收。

## 受授权摘要启用与验收（2026-11-07）

先备份业务PostgreSQL，部署含Flyway V13的Java服务，再启动Python。不要在运行中的JAR目录执行重新打包；继续使用隔离构建目录。Python读取 `AGENT_SUMMARY_ENABLED=true`、`AGENT_SUMMARY_TIMEOUT=10`；主模型须支持JSON object，调用失败会有界回退近期完整历史。配置false可关闭新增摘要调用，原记录仍保留。排障、字段和限制见 [CONTEXT_SUMMARIES.md](CONTEXT_SUMMARIES.md)。

定向命令（PowerShell，repo根目录；pytest使用新的工作区basetemp避免宿主临时目录权限问题）：

```powershell
services/agent-service/.venv/Scripts/python.exe -m pytest services/agent-service/tests/test_summary.py services/agent-service/tests/test_agent.py -q --basetemp=.cache/agent-summary-tests-<本次唯一标识>
.\mvnw.cmd -B -ntp -f .cache/<隔离构建>/pom.xml '-Dmaven.repo.local=<Maven缓存绝对路径>' -pl services/backend -am '-Dtest=KnowledgeSearchTest#agentSummaryCommitReuseAndDerivedAnswersKeepAuthorizationLineage' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

先用隔离依赖和协议桩验证；真实模型验收另行记录摘要提示版本、模型、额外耗时和调用量。健康接口成功不能证明摘要质量，加载已有会话不应发起Agent POST。

## 动态工具预算（2026-11-07）

只更新Python Agent即可，Java继续使用V13；不重建索引、不迁移数据库。`AGENT_TOOL_BYTE_BUDGET`仍默认4096、范围1024–16384，现为每次工具输出的最大上限，实际还受完整上下文剩余额度约束。上下文策略context-v2包含该配置，旧摘要按原记录重建；不需要清理原会话或检查点来绕过权限。

受控日志agent_tool_budget记录trace_id、tool-results-v1、calls、max_result_bytes及max_result_estimated_tokens。CONTEXT_BUDGET可能在工具执行前结束当前批次；TOOL_RESULT_BUDGET表示无法下发足够证据，先缩小问题/结果范围，不通过扩大模型或权限参数重放原请求。查看工具卡的裁剪标记及来源，具体原文需重新检索。

按影响只运行新增2项和相关历史、状态、取消、引用及摘要检查，命令与实际结果见 [TOOL_BUDGET_VALIDATION.json](TOOL_BUDGET_VALIDATION.json)。Python重启前确认没有RUNNING执行，再做鉴权history/健康只读检查，不需重复Java或前端构建。
