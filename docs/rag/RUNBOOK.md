# RAG 运行手册

本轮交付 R1 检索基础，运行依赖、账号、对象存储与入库流程继续使用 [知识库运行手册](../knowledge-base/RUNBOOK.md)。当前没有独立问答或 Python Agent 服务可启动。

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

- 404：核对当前账号归属和请求范围；不要换成管理身份绕过检查。
- 409/KB_SEARCH_MODEL_MISMATCH：按兼容嵌入空间分别请求；不能更换向量模型来规避错误。KB_SEARCH_SCOPE_CHANGED 表示请求期间绑定变化，重新读取并提交一个新请求。
- DEGRADED/INDETERMINATE：看 channels.errorCode 与 rerankStatus，修复 ES 同步/精排依赖；不是“已证明无知识”。
- 503/KB_SEARCH_CAPACITY：原零队列执行器已满，调用方有限退避；评估 Tomcat、检索池、AI 并发和数据库连接池的整体容量。
- 504/KB_SEARCH_TIMEOUT：检查总耗时、模型/ES 延迟、DB 锁和连接池；精排不再获得额外完整预算。KB_MODEL_TIMEOUT 保留原模型 HTTP 错误语义。
- 中断请求后 HTTP Future 会取消；JDBC 语句终止仍依赖数据库超时。当前 JSON MVC 的浏览器断连不保证立刻触发中断，服务仍在总预算内结束。

不记录完整 query、资料正文、账号密码或模型原始响应。普通返回只含脱敏 trace，operator 指标权限沿用原规则。响应禁缓存不等于外部客户端无法保存已获授权资料，撤权无法收回此前已下发的内容；本服务不维护可绕过复核的结果缓存。

模拟故障、真实 PG/MinIO/ES、真实供应商模型和语义效果分别记录，结果见 PROGRESS 与 EVALUATION。本轮不宣称应用镜像、生产网络、LLM 答案质量或负载已验收。
