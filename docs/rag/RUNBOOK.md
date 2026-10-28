# RAG 运行手册

本轮交付 R1 检索基础，运行依赖、账号、对象存储与入库流程继续使用 [知识库运行手册](../knowledge-base/RUNBOOK.md)。当前没有独立问答或 Python Agent 服务可启动。

## 构建与验证

仓库根目录，JDK 17；Docker 用于隔离集成测试，不连接当前业务库。不要为运行测试设置指向业务库的 KB_TEST_JDBC_URL，该可选测试会清空目标表。以下 verify 命令用于没有从本 checkout 的 target 运行应用的环境；已有进程占用 JAR 时先复制构建源到独立目录，绝不在运行目录执行 package/verify。本轮根目录打包遇到此问题后的恢复和独立产物见 PROGRESS。

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' verify
```

仅检索相关行为：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am '-Dtest=HybridRetrievalBudgetTest,RerankDeadlineTest,VectorSearchDeadlineTest,ElasticsearchDeadlineTest,AiEmbeddingClientTest,KnowledgeSearchTest,HybridRetrievalTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本机可给测试增加 `-Dkb.test.elasticsearch-image=atlas-rag-elasticsearch:latest`，它是缓存的 ES 8.19.17，使用新容器和数据；不引用其他项目的数据卷。其他环境默认使用测试声明的公开 ES 8.19.17。Docker 不可用时集成测试会跳过，不能据此认定 PG/ES 已验证。

## 启动与依赖

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
