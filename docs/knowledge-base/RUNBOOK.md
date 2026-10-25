# 知识库运行手册（第 10 阶段交付）

## Web 管理台（2026-10-25）

已补 React + Ant Design + Vite 管理台；复用现有账号，不建设用户/角色管理。先启动下文后端设施和服务，再从仓库根目录执行：

```powershell
cd web
npm ci --registry=https://registry.npmjs.org
$env:KB_BACKEND_ORIGIN='http://127.0.0.1:8080'
npm run dev
```

访问 http://127.0.0.1:5173，使用服务端已有账号登录。KB_BACKEND_ORIGIN 是 Vite 服务端环境变量，不以 VITE_ 注入浏览器；API 同源代理，不放宽 CORS。账号密码只存本次内存，刷新/退出/401 后需重新登录；生产必须使用 HTTPS。不要把真实密钥写进 .env.example 或前端构建参数。

```powershell
# 在 web/ 执行
npm run check
npm test
npm run build
npm run preview
```

preview 在 4173，仅用于本地验证 dist，仍通过同源代理访问后端。Node 使用 20.19+ 的 20.x 或 22.12+；完整操作/部署约定见 [Web 说明](../../web/README.md)。生产可将 dist 部署到既有 Nginx 并使用 web/nginx.conf；需调整 upstream 为部署内网地址，保留认证、安全头与代理预算。

可选容器入口（仓库根目录；先配置既有 Compose .env）：

```powershell
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml --profile web config --quiet
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml --profile web up -d --build
```

访问 http://127.0.0.1:8088，web profile 同源转发到 backend:8080。可合并 --profile keyword；服务是否启用仍以后台 capabilities 和真实返回策略为准。Nginx 文件上限 21 MiB 含 multipart；改变 KB_MAX_UPLOAD_BYTES、KB_MAX_UPLOAD_SIZE、KB_MAX_REQUEST_SIZE 时同步调整入口。镜像构建与启动的实际验证范围见最新 PROGRESS，配置可解析不等于镜像已验收。

实际本地验证：标准锁文件 npm ci、npm ls、19项测试、TypeScript/生产构建、35项浏览器/HTTP场景及 Compose web+keyword 配置检查通过，见 [WEB_VALIDATION.json](WEB_VALIDATION.json)。后端 JAR/PG/MinIO/ES 与远程下载实际运行，模型为明确协议桩；没有运行生产 Nginx/应用镜像或真实供应商质量验收。临时测试设施退出，示例数据不常驻。

发布新 dist 时对 index.html 保持不缓存或每次验证，并保留前版带哈希 assets 一段时间，避免活跃页面请求旧分包失败。界面遇页面资源更新/加载失败会显示“刷新并重新登录”，不会展示原始错误堆栈。供应商分包保留 [Rolldown 的初始化执行顺序](https://rolldown.rs/reference/OutputOptions.codeSplitting)，修改构建配置后应重新验证生产预览，而不只运行开发服务器。

浏览器验收顺序：登录→建库（服务端模型）→上传→提交入库→任务阶段→检索证据→授权原文件→编辑 Chunk 后重新检索→独立禁用 Chunk→文档禁用/重启（独立禁用仍保留）→重入库覆盖确认→URL 导入/刷新→删除及资源清理。任务页面提供真实阶段/次数/耗时/错误码与重试；关键词失败可查询状态并重试。409 应先刷新审阅，不自动重放写入；失败清理/卡住任务仍可使用后文持久化恢复命令。

## 第 10 阶段：部署与 API 管理入口

原 API 管理交付继续可用；新 Web 管理台启动见上节。入口GET /api/knowledge/management，契约GET /openapi/knowledge-base.yaml，52条调用集合GET /openapi/knowledge-base.postman_collection.json，均需现有Basic认证。将集合导入Postman，在本地私有环境填写baseUrl/username/password；按操作顺序选择请求，不整份盲跑包含删除/覆盖的集合。凭据不导出、不提交。仅部署 API 时无需 Node；Web 构建要求见上节。不新增 IAM。

推荐启动顺序（仓库根目录，Java17、Docker和Maven可用）：

```powershell
# 先保留已有.env；首次部署才复制并编辑所有占位值。
if (-not (Test-Path -LiteralPath deploy/knowledge-base/.env)) { Copy-Item deploy/knowledge-base/.env.example deploy/knowledge-base/.env }
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' verify
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml config --quiet
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml up -d postgres minio minio-init
# 另一个受控终端配置BAILIAN_API_KEY后启动既有AI服务；默认绑定qwen-embed/text-embedding-v4/1024。
java -jar services/ai-service/target/rag-ai-service-0.1.0-SNAPSHOT.jar
# KB_AI_BASE_URL须是backend所在网络可达的AI地址，然后启动已打包的业务镜像：
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml up -d --build backend
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml logs --tail 80 backend
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

不要覆盖已有.env；上面Copy-Item只用于首次初始化。AI供应商密钥只注入AI进程，默认绑定的百炼模型不能用另一供应商同维度向量代替。AI受控内网/网关部署、固定模型与配置修订、原生精排协议见[AI服务说明](../../services/ai-service/README.md)。不配置真实模型可启动管理/上传，但入库/查询会明确失败，不能据此称端到端已完成。直接Java启动backend时不会自动读取Compose的.env，注入方式见后文“迁移、本地设施与启动”。

模型认证排查：AI 健康检查 UP 仅证明进程可访问；模型描述 active=true 仅证明本地已启用且密钥非空。应通过 /ai/embed 使用不含敏感信息的短文本验证真实认证和 1024 维响应。若返回 AUTH，核对供应商；百炼 401/invalid_api_key 需检查密钥完整性、有效性、所属地域与 Base URL，参见[官方错误码](https://help.aliyun.com/zh/model-studio/error-code#apikey-error)。当前默认 dashscope.aliyuncs.com 对应北京，不能直接使用其他地域或其他供应商的密钥。将正确密钥保存在本地环境配置并重启 AI 服务后，再重试失败入库任务；原文件无需重新上传。不要为排查输出密钥、完整向量或文档正文，也不要更换已有库的嵌入空间来规避认证问题。

PG17.11/pgvector0.8.7、MinIO发布版本、Java17与Boot3.5.16保持兼容。Compose的ES为可选keyword profile，默认关闭；启用步骤在下一节。普通PDF/Office/Excel/CSV本地解析无需外部OCR；扫描PDF的fast没有OCR，MinerU/VLM须另外配置，见[格式矩阵](FORMATS.md)。当前只交付检索证据，没有新增answer接口。

迁移到本版前同时备份数据库和私有bucket，停止旧worker；启动自动执行Flyway V1–V8，保留原迁移校验和。V8新增last_ingested_at、扩展既有摘要审计与trigger。PG扩展必须在public且1024维模型与vector(1024)一致，默认精确cosine扫描，无HNSW。迁移身份可单独配置spring.flyway.user/password；不使用clean/baseline/repair绕过故障。数据库、S3和可选ES并非分布式事务，按持久清理/同步记录恢复。生产需限定S3 bucket/key权限，bucket必须未启用版本化；外部保留/合规锁定策略另行验证。

任务并发默认2、零缓冲队列、AI并发2、模型call30s、入库attempt120s；租约30s/续租5s，刷新45s/续租5s及run总预算15m。解析并发2、子进程堆256MiB、本地30s；上传20MiB。全部现有配置见下文和.env.example。Cron由请求明确timezone（如Asia/Shanghai）计算，TIMESTAMPTZ存UTC，不依赖容器默认时区。单服务容器内存预算包含JVM和解析子进程；整套本地设施含可选ES建议至少6GiB可用内存。

一键HTTP验收（只创建唯一名称的自有样例，部分库保留供检查；不会安装模型桩或修改部署配置）：

```powershell
$env:KB_BASE_URL='http://127.0.0.1:8080'
# KB_USERNAME/KB_PASSWORD从本地安全环境注入，不把真实值粘贴到版本库。
$env:KB_DELIVERY_REPORT='.cache/delivery-report.json'
python -X utf8 scripts/knowledge-base/delivery_acceptance.py
# 受控本地URL来源须先按第8阶段精确白名单/端口配置，才启用以下检查：
$env:KB_RUN_REMOTE_ACCEPTANCE='1'
python -X utf8 scripts/knowledge-base/delivery_acceptance.py
```

该脚本验证管理/审计/指标权限，依次运行管理、上传、入库、检索、维护、格式和混合检索脚本；远程检查默认明确SKIPPED。关键词未配置则混合脚本验证真实向量并明确跳过关键词；设置KB_REQUIRE_HYBRID=1可强制要求真实ES。精排已配置时要求实际APPLIED，未配置明确跳过。KB_ACCEPTANCE_EXTERNAL_FORMATS=1会额外调用已配置MinerU/VLM，否则格式脚本仅测本地能力。执行会使用目标模型服务，质量和费用按目标部署实际配置。

进程重启、租约失效、跨owner/tenant、错误协议和事务回滚由Java集成验收，必须在Docker可用的环境执行verify并检查报告。HybridRetrievalTest默认公开ES8.19.17镜像，允许-Dkb.test.elasticsearch-image指定已验证同版本镜像；本机隔离验证用缓存atlas-rag-elasticsearch:latest（8.19.17），只启用内置cjk，不使用其他项目卷。没有Docker时跳过的测试不能算集成通过；LocalPostgresTest的12项需可选既有测试库配置，不影响已执行的容器测试。

## 运维指标、日志与审计

复用已有Actuator/Micrometer，无额外监控服务依赖。KB_OPERATOR默认false，只把**当前服务端映射账号**标记为可读全局指标；业务数据仍受tenant+owner限制。普通账号403、未登录401，operator不等于可读其他用户文档。需要分离账号时沿用外置kb.security.accounts列表配置，不提供账号管理接口。只暴露health/metrics，其他Actuator路由拒绝。[Boot端点安全](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)、[Micrometer指标](https://docs.micrometer.io/micrometer/reference/concepts/meters.html)。

```powershell
$kbAuth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:KB_USERNAME+':'+$env:KB_PASSWORD))
$kbHeaders=@{Authorization='Basic '+$kbAuth}
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/api/knowledge/management')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.ingestion.jobs?tag=status:PENDING')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.ingestion.stage?tag=stage:EMBED&tag=status:FAILED')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.model.failures?tag=operation:embedding')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.model.failures?tag=operation:rerank')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.cleanup.resources?tag=kind:document&tag=status:failed')
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/actuator/metrics/kb.refresh.runs?tag=status:FAILED')
```

kb.ingestion.jobs按状态计当前持久任务，kb.ingestion.stage为FunctionTimer，COUNT/TOTAL_TIME由持久阶段日志累计；kb.cleanup.resources按upload/generation/document/remote/keyword分pending/failed，kb.keyword.sync显示同步状态，kb.refresh.runs按状态计刷新结果。阶段/任务/清理聚合在一次只读快照内读取、最多每10秒一次，不为每标签查询数据库；kb.metrics.snapshot.available=0或snapshot.age增加说明读取失败/陈旧，不能把旧值当健康。无kbId/documentId/tenant等高基数标签。kb.model.calls和kb.model.failures为进程级逻辑调用统计，重启归零，多实例由监控端聚合；持久队列/阶段是全库视图，采集多实例不要再相加。现有Actuator JSON用于诊断，本轮没有新增Prometheus/Grafana部署。

日志knowledge-base.operations在事务提交后写kbId/documentId/jobId/generation/stage/status，可与原X-Request-Id及任务阶段表关联；刷新尚未形成job/generation时相应字段为空，不伪造代号。错误只记录受控errorCode/类型，不能把源文档、请求内容、base64、密钥或供应商堆栈加入普通日志。GET /api/kbs/{kbId}/audit查询同范围的既有Chunk摘要与本版管理摘要，按API契约分页。首次部署V8只审计后续变更，不能补造历史操作；审计/任务保留与归档由部署方按需求安排。

## 常用恢复命令

```powershell
# 正常租约恢复：修复依赖后启动worker或重启，等待有效租约/退避；不手改claimToken或activeGeneration。
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml restart backend
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/api/ingestion-jobs/'+$env:KB_JOB_ID)
Invoke-RestMethod -Headers $kbHeaders ($env:KB_BASE_URL+'/api/ingestion-jobs/'+$env:KB_JOB_ID+'/stages?size=100')
$kbRetry=@{requestId='retry-'+[guid]::NewGuid().ToString()}|ConvertTo-Json
Invoke-RestMethod -Method Post -Headers $kbHeaders -ContentType application/json -Body $kbRetry ($env:KB_BASE_URL+'/api/ingestion-jobs/'+$env:KB_JOB_ID+'/retry')
Invoke-RestMethod -Method Post -Headers $kbHeaders ($env:KB_BASE_URL+'/api/document-cleanups/'+$env:KB_CLEANUP_ID+'/retry')
Invoke-RestMethod -Method Post -Headers $kbHeaders ($env:KB_BASE_URL+'/api/kbs/'+$env:KB_ID+'/storage-operations/'+$env:KB_STORAGE_OPERATION_ID+'/retry-cleanup')
Invoke-RestMethod -Method Post -Headers $kbHeaders ($env:KB_BASE_URL+'/api/kbs/'+$env:KB_ID+'/keyword-sync/'+$env:KB_DOCUMENT_ID+'/retry')
```

仅FAILED/CANCELLED任务可显式retry；若有人工块必须先审阅并补确认/当前版本。刷新关联job使用新refresh请求重新下载，不能直接retry旧文件快照。删除立即退出检索，S3/旧generation/ES物理回收各有持久状态；清理未完成时删父库409是保护行为。generation/remote清理的限定范围SQL、刷新卡住诊断及对象租约宽限见下文各阶段恢复节。默认health仅验证进程/DB，不代表S3/模型/ES在线；关闭ES时AUTO=VECTOR，启用ES但故障时按通道契约降级。

## 最终验证与尚未联调的外部依赖（2026-10-25）

全10模块verify成功：250项/238通过/0失败错误/12项可选LocalPostgresTest跳过；Docker集成全部执行。最终JAR实际运行8份HTTP脚本，另外真实暂停/恢复ES、错误模型/精排响应、两种独立进程身份范围检查通过；入库/清理/刷新分别以两个真实Java进程完成重启恢复。OpenAPI41路径/52操作/416引用、52条集合请求和两种Compose配置检查通过。247个构建输入与最终JAR资源/类文件哈希已复核。统计、产物SHA-256与边界见[STAGE10_VALIDATION.json](STAGE10_VALIDATION.json)，执行结果见[DELIVERY_HTTP_REPORT.json](DELIVERY_HTTP_REPORT.json)。

真实设施为PG17.11/pgvector0.8.7、MinIO和ES8.19.17/cjk BM25；Embedding/Rerank/VLM/MinerU为明确的本地HTTP协议桩。生产模型固定版本、账户权限/费用/语义质量、OCR效果、来源DNS/出口、S3保留策略和生产ES均未真实联调。本轮没有前端应用，前端检查/构建不适用，交付API管理入口、文档、集合和脚本；没有新增用户管理。

应用镜像未构建/启动：首次docker pull eclipse-temurin:17-jre-jammy在基础层发生short read/unexpected EOF（47519993字节期望，44334499实际），第二次有界重试362秒仍未完成，已停止本任务的下载进程。该网络限制不影响已验收的Java JAR，也不能用JAR成功替代镜像验证。网络恢复后的验收命令：

```powershell
docker pull eclipse-temurin:17-jre-jammy
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml build backend
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml up -d backend
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
python -X utf8 scripts/knowledge-base/delivery_acceptance.py
```

也可按后文注入DB/S3/AI/账号环境后直接java -jar services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar。验证用临时数据库/对象/索引和Java进程均已清理；交付示例UUID仅留作证据。实际部署应重新创建库并运行验收，不复用临时ID。本版仍为唯一1024维pgvector精确查询，生产数据规模性能、归档/保留和多实例容量应在目标环境评估。

## 第 9 阶段：混合检索与关键词同步（当前有效）

备份PG后按既有方式升级JAR，Flyway应用V7，不改V1–V6校验和。V7创建kb_keyword_sync、kb_keyword_cleanup及事务trigger，并为旧文档补建待同步记录；正文/启停/发布/删除写入与Outbox同PG事务。不要禁用或绕过trigger，也不要手工改syncedRevision让未同步索引可见。关键词和精排默认false，原VECTOR无需ES；已有AI/Embedding配置和权限继续复用。

| 配置 | 默认 | 含义 |
|---|---|---|
| KB_KEYWORD_ENABLED | false | 启用ES适配及持久化同步；仍需KB_WORKER_ENABLED=true才自动执行 |
| KB_ES_ENDPOINT / API_KEY / INDEX | localhost:9200 / 空 / kb-chunks-v1 | 本地JAR默认HTTP origin；Compose默认http://elasticsearch:9200；API key仅环境变量 |
| KB_ES_ANALYZER | cjk | 内置CJK二元分词；可smartcn/ik_max_word/ik_smart，必须在所连服务实际可用 |
| KB_ES_CALL_TIMEOUT / KEYWORD_LEASE | 3s / 1m | 每次连接/取连接/读取≤15s；租约15s..5m，2s续租，总尝试2m |
| KB_KEYWORD_MAX_SCOPE_DOCUMENTS / BATCH_SIZE | 2000 / 100 | ES terms有效修订配额、批量写入1..500；超范围缩小documentIds |
| KB_KEYWORD_MAX_ATTEMPTS / POLL_DELAY_MS | 5 / 1000 | 同步/GC有限次数与扫描；退避2s指数至60s |
| KB_RETRIEVAL_STRATEGY | AUTO | AUTO/VECTOR/HYBRID/KEYWORD；AUTO按关键词配置选择 |
| KB_RETRIEVAL_CHANNEL_CANDIDATES / FUSION_CANDIDATES | 50 / 20 | 1..200，fusion≤channel；topK更大时有界扩大预算 |
| KB_RETRIEVAL_RRF_CONSTANT / VECTOR_WEIGHT / KEYWORD_WEIGHT | 60 / 1 / 1 | 常数1..1000、有限正权重0.01..100；仅融合排名 |
| KB_RETRIEVAL_CONCURRENCY | 4 | 2..32线程、零缓冲；饱和通道明确失败，数据库同步意图仍持久化 |
| KB_RETRIEVAL_VECTOR_TIMEOUT / KEYWORD_TIMEOUT | 40s / 5s | 独立正deadline≤2m；迟到结果也丢弃 |
| KB_RERANK_ENABLED / MODEL / TIMEOUT | false / qwen-rerank / 10s | 共享AI传输，固定服务端别名，超时≤1m |
| KB_RERANK_MAX_INPUT_CHARS / MIN_SCORE | 30000 / 空 | query+全部候选正文总预算；超限回落。可选有限默认阈值需启用精排 |

Elasticsearch Java Client8.18.8沿用Boot BOM，对真实8.19.17实测；服务版本需8.18+的8.x协议族，其他大版本未宣称兼容。默认cjk无需装插件；SmartCN/IK须按同一ES版本安装到所有节点，不能把标准分词或substring说成已部署中文BM25。适配器检查info版本、中文_analyze结果、专用索引schema/analyzer/BM25映射；不符时同步失败/通道降级并记录errorCode，不自动换分析器。修改分析器应使用新专用index名、统一所有实例配置并重建，旧索引由运维确认留存后处理。

Compose可选本地全文profile（已有.env先保留原DB/S3/账号/AI配置，只增加下面变量）：

```powershell
$env:KB_KEYWORD_ENABLED='true'
$env:KB_ES_ENDPOINT='http://elasticsearch:9200'
docker compose --env-file deploy/knowledge-base/.env --profile keyword -f deploy/knowledge-base/compose.yml up -d --build
# 单独本地Java进程连接同一可选ES时，改用宿主机地址：
$env:KB_ES_ENDPOINT='http://127.0.0.1:9200'
```

默认可选镜像docker.elastic.co/elasticsearch/elasticsearch:8.19.17、1分片、0副本、512MiB堆；profile只绑定回环9200，关闭ES认证用于本地验收。生产连接已有TLS/受权ES和API key，授予所需index读写/删除、mapping/analyze检查和初始化权限，先核对专用schema；不要把本地未认证配置用于公网。index初次创建后生产可自行配置副本/备份。keyword profile额外需要约1.5GiB容器预算，整套服务建议至少6GiB可用内存。当前验证使用隔离缓存8.19.17/IK镜像及内置cjk；官方可选镜像和生产集群部署需在目标环境验证。

启用精排前配置既有AI服务的真实百炼或SiliconFlow密钥和对应/ai/rerank模型别名。backend默认qwen-rerank，服务端返回不同别名会回落融合，避免把不同模型分数用于同一阈值；若只用silicon-rerank则显式设置KB_RERANK_MODEL。原AI fallback配置可能切模型，请按业务固定供应商或明确部署策略。请求和响应遵循AI已有原生协议映射，不能直接替换为任意OpenAI兼容地址。KB_RERANK_MIN_SCORE依所选供应商实际评测设定，不把RRF/BM25当置信度。

查询能力和同步状态（账号、KB_BASE_URL、KB_ID、KB_DOCUMENT_ID沿用当前环境）：

```powershell
$kbAuth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:KB_USERNAME+':'+$env:KB_PASSWORD))
$kbHeaders=@{Authorization='Basic '+$kbAuth}
Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/knowledge/search-capabilities') -Headers $kbHeaders
Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/documents/'+$env:KB_DOCUMENT_ID+'/keyword-index') -Headers $kbHeaders
Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/kbs/'+$env:KB_ID+'/keyword-sync') -Headers $kbHeaders
$kbQuery=@{query='麒麟数据库 ZX-1472';kbIds=@($env:KB_ID);strategy='HYBRID';rerank=$false;topK=5}|ConvertTo-Json
Invoke-RestMethod -Method Post -Uri ($env:KB_BASE_URL+'/api/knowledge/retrieve') -Headers $kbHeaders -ContentType application/json -Body ([Text.Encoding]::UTF8.GetBytes($kbQuery))
python -X utf8 scripts/knowledge-base/hybrid_acceptance.py
```

HTTP验收脚本按capabilities检查已配置能力，关键词关闭时明确跳过BM25并验证真实向量；KB_REQUIRE_HYBRID=1可强制要求ES。创建并保留自己的中文/编号/备份/无关主题样例库，不装模拟ES或更改服务配置。精排仅在capabilities显示已配置时验证，模型仍以目标服务配置为准；可用KB_HYBRID_EXAMPLE_OUTPUT导出实际调用，KB_ACCEPTANCE_WAIT_SECONDS默认300。

PENDING卡住：检查worker/keyword开关、nextRunAt、ES网络/权限/分析器/映射、连接容量。RUNNING进程退出后等待租约过期自动领取；失权者不能切换可见修订。FAILED修复原因后授权POST /api/kbs/{kbId}/keyword-sync/{documentId}/retry，墓碑文档也可重试；原/search可临时明确strategy=VECTOR，不能伪造关键词已同步。dirty文档暂不参加关键词召回，向量始终根据当前PG有效代工作。

运维只读检查（psql使用-v document_id=实际UUID限定范围）：

```sql
SELECT document_id,revision,synced_revision,indexed_target,status,attempts,next_run_at,
       lease_until,attempt_deadline,error_code,synced_at
FROM kb_keyword_sync WHERE document_id=:'document_id'::uuid;
SELECT document_id,watermark,status,attempts,next_run_at,lease_until,error_code
FROM kb_keyword_cleanup WHERE document_id=:'document_id'::uuid;
```

索引提交与PG不是分布式事务。ES按doc:revision:chunk写不可变记录，PG只确认持权且未变化的修订；查询terms按PG已确认修订过滤，回表再核对正文和启停，不需要信任滞后删除。旧记录回收延迟40s（覆盖已启动有界请求），失败有限重试，成功每小时再查该水位。清理FAILED修复后可通过同一个授权retry恢复，或由数据库管理员按指定文档重排：

```sql
UPDATE kb_keyword_cleanup SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),
       lease_owner=NULL,lease_until=NULL,claim_token=claim_token+1,error_code=NULL
WHERE document_id=:'document_id'::uuid AND status='FAILED';
```

已索引文档的同步/清理未完成时父KB删除409。ES关闭后已有清理意图仍保留，恢复ES再处理；不以日志替代状态。索引整库丢失/映射被外部改动时停止执行者、恢复或建立新专用索引，统一配置并通过retry重建；不要混跑不同indexedTarget的实例。旧索引更名/切集群属于显式运维迁移，不自动删除其他索引。接口返回refillLimitReached时可缩小范围/重试并检查同步滞后；提高预算须同时评估DB/ES/模型超时与容量。

本轮只交付证据检索；capabilities不等于外部联调成功。真实模型精排/语义质量和生产集群安全配置须单独验证，详见PROGRESS实际结果。

## 第 8 阶段：远程来源与自动刷新（当前有效）

启动前备份现有PG，停止旧版本执行者后升级到当前JAR，Flyway自动应用V6（保留V1–V5校验和）。V6增加document.source_url、URL类型约束、refresh_schedule/run、url_import、remote_object_cleanup，以及job对refresh的版本关联。仍只用现有PG/pgvector/S3/AI；不要让未包含刷新校验的旧后端同时运行。Java17、Maven和下文DB/S3/AI配置沿用。

| 环境变量 | 默认 | 含义 |
|---|---|---|
| KB_REMOTE_ENABLED | false | 显式启用受控下载/刷新扫描；关掉不影响本地上传和远程对象GC |
| KB_REMOTE_ALLOWED_HOSTS | 空 | 逗号分隔精确host，空时全部拒绝；无通配，不写scheme/path |
| KB_REMOTE_TRUSTED_PRIVATE_HOSTS | 空 | 管理员为内部来源设置的精确例外，必须也在allowed列表；默认私网/回环不可用 |
| KB_REMOTE_ALLOWED_PORTS / ALLOW_HTTP | 443,80 / false | 端口与明文协议分别允许；默认实际仅HTTPS |
| KB_REMOTE_CONNECT_TIMEOUT / READ_TIMEOUT / DOWNLOAD_TIMEOUT | 5s / 10s / 30s | 正超时；总下载≤2m且覆盖DNS和所有重定向 |
| KB_REMOTE_MAX_REDIRECTS / MAX_BYTES | 3 / 20971520 | 最多0..8跳，流式大小还受KB_MAX_UPLOAD_BYTES约束 |
| KB_REMOTE_CONCURRENCY | 2 | 1..8，有界下载/DNS线程与每实例在途refresh上限 |
| KB_REFRESH_LEASE / DEADLINE / MAX_ATTEMPTS | 45s / 15m / 3 | 租约≥15s、run总预算≤30m、最多1..5次；续租5s，不能复活过期claim |
| KB_REFRESH_POLL_DELAY_MS | 1000 | 到期、失效/失败协调和领取扫描；还需KB_WORKER_ENABLED=true |
| KB_REMOTE_CLEANUP_POLL_DELAY_MS | 10000 | 候选/旧对象GC；次数/退避用KB_CLEANUP_MAX_ATTEMPTS及worker.backoff |

来源必须由服务端批准，部署同时配置出口防火墙/网络策略限制源站。无代理/Cookie/用户Authorization透传；禁止认证URL和HTTPS降级，逐跳校验全部DNS地址并固定实际连接地址。例外不是通用“关闭SSRF校验”；链路本地和元数据服务仍拒绝。URL可能含业务查询参数，仅存归属受限业务表，不进入检索来源或普通错误日志。首版不支持需要登录、浏览器渲染、gzip响应、HTML页面抓取、任意请求头或来源认证密钥；请提供可下载的已支持格式文件。

本地进程验收可为受控回环来源显式设置（生产不用此例外）：

```powershell
$env:KB_REMOTE_ENABLED='true'
$env:KB_REMOTE_ALLOWED_HOSTS='127.0.0.1'
$env:KB_REMOTE_TRUSTED_PRIVATE_HOSTS='127.0.0.1'
$env:KB_REMOTE_ALLOWED_PORTS='18081'
$env:KB_REMOTE_ALLOW_HTTP='true'
# 按后文配置DB/S3/AI/账号，启动当前backend JAR，然后执行：
$env:KB_BASE_URL='http://127.0.0.1:8080'
$env:KB_REFRESH_SOURCE_PORT='18081'
python -X utf8 scripts/knowledge-base/refresh_acceptance.py
```

该脚本自己启动只供验收的HTTP来源，创建独立KB并保留以供检查；验证首次导入/同key重放、304、同字节新ETag、内容变化、检索、真实@Scheduled时区触发及5xx有限失败。模型使用当前后端配置，不偷偷装入桩；需已可用Embedding。KB_REFRESH_EXAMPLE_OUTPUT可导出实际请求/响应。Docker后端访问宿主机来源时，配置精确host.docker.internal及内部例外、18081端口，并通过KB_REFRESH_SOURCE_URL指定后端能访问的地址；脚本默认只绑定127.0.0.1，跨容器场景需另行提供受控可达来源，不能直接假设回环互通。

一般来源请求（KB_SOURCE_URL、KB_ID、KB_USERNAME/PASSWORD由环境提供）：

```powershell
$kbAuth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:KB_USERNAME+':'+$env:KB_PASSWORD))
$kbHeaders=@{Authorization='Basic '+$kbAuth;'Idempotency-Key'=[guid]::NewGuid().ToString()}
$kbImport=@{url=$env:KB_SOURCE_URL;filename='guide.md';ingest=$true}|ConvertTo-Json
$kbImported=Invoke-RestMethod -Method Post -Uri ($env:KB_BASE_URL+'/api/kbs/'+$env:KB_ID+'/documents/import-url') -Headers $kbHeaders -ContentType application/json -Body ([Text.Encoding]::UTF8.GetBytes($kbImport))
$kbDocumentId=$kbImported.data.document.id
Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/ingestion-jobs/'+$kbImported.data.jobId) -Headers $kbHeaders
$kbRefresh=@{requestId='refresh-'+[guid]::NewGuid().ToString()}|ConvertTo-Json
Invoke-RestMethod -Method Post -Uri ($env:KB_BASE_URL+'/api/documents/'+$kbDocumentId+'/refresh') -Headers $kbHeaders -ContentType application/json -Body $kbRefresh
Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/documents/'+$kbDocumentId+'/refresh-runs') -Headers $kbHeaders
$kbSchedule=Invoke-RestMethod -Uri ($env:KB_BASE_URL+'/api/documents/'+$kbDocumentId+'/refresh-schedule') -Headers $kbHeaders
$kbScheduleBody=@{version=$kbSchedule.data.version;enabled=$true;cron='0 */30 * * * *';timezone='Asia/Shanghai'}|ConvertTo-Json
Invoke-RestMethod -Method Put -Uri ($env:KB_BASE_URL+'/api/documents/'+$kbDocumentId+'/refresh-schedule') -Headers $kbHeaders -ContentType application/json -Body $kbScheduleBody
```

调度version与文档version不同；更新前读取当前值。人工Chunk变化要求审阅当前document.version后，refresh请求显式confirmOverwriteEdited=true及expectedDocumentVersion；自动调度遇人工内容只SKIPPED。未变hash不会覆盖人工块，也不会强制重新分块。文档禁用/删除同步停schedule；文档重启用后如需刷新，另行PUT启用schedule。

运维检查（psql先以`-v document_id=实际UUID`传入范围，管理员数据库连接沿用项目配置）：

```sql
SELECT document_id,enabled,version,cron_expression,timezone,next_run_at,last_status,last_reason,
       consecutive_failures,lease_until,claim_token
FROM kb_refresh_schedule WHERE document_id=:'document_id'::uuid;
SELECT id,request_id,status,attempts,max_attempts,job_id,reason,started_at,completed_at,duration_ms,
       next_run_at,lease_until,deadline
FROM kb_refresh_run WHERE document_id=:'document_id'::uuid ORDER BY created_at DESC LIMIT 20;
SELECT j.id,j.status,j.stage,j.error_code,l.attempt,l.stage,l.status,l.duration_ms,l.error_code
FROM kb_ingestion_job j LEFT JOIN kb_ingestion_stage_log l ON l.job_id=j.id
WHERE j.document_id=:'document_id'::uuid ORDER BY j.created_at DESC,l.attempt,l.started_at;
SELECT id,status,attempts,next_run_at,lease_until,error_code
FROM kb_remote_object_cleanup WHERE document_id=:'document_id'::uuid ORDER BY created_at DESC;
```

PENDING卡住：确认remote+worker开关、nextRunAt、来源白名单、线程/DNS容量和DB连通。WAITING_INGESTION先查job/stages；解析/模型/PG失败不会推进HTTP成功基线。进程崩溃后等待租约过期，扫描器取消旧job并以新claim重做同run；不手工改activeGeneration/token/HTTP基线，不在有效租约期间强行重领。总次数/期限耗尽为FAILED，修复原因后提交新requestId的refresh；关联job不要直接retry。过期同步import会FAILED，先查对应storage-operations处理未引用对象，再换新的Idempotency-Key导入。

GC宽限至少2×S3 API timeout+30s（默认210s），覆盖失权时仍可能在途的PUT，且再次核对live文件和活跃候选引用。失败进入PENDING退避，最多既有cleanupMaxAttempts次后FAILED；修复S3权限/未版本化bucket/DB后，可按指定文档安全重排终态清理意图：

```sql
UPDATE kb_remote_object_cleanup
SET status='PENDING',attempts=0,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL,
    next_run_at=clock_timestamp()+interval '3 minutes',error_code=NULL,completed_at=NULL
WHERE document_id=:'document_id'::uuid AND status='FAILED';
```

未完成清理（含FAILED）阻止删父KB。正常spool立即关闭删除；异常/重启后本组件上传/下载/解析spool每5m最多扫描256个过30m文件，源文件有效job租约受保护；使用专用可写KB_UPLOAD_TEMP与磁盘监控。PG提交和S3删除不属于分布式事务，不能用仅打印日志替代持久化清理。生产来源/DNS/出口策略、真实模型和目标Docker应用部署仍需按目标环境联调；本地HTTP来源不证明生产网络已放通。

## 第 7 阶段：多格式解析与外部适配（历史，格式能力仍有效）

沿用后文DB/S3/AI/worker配置和V1–V5迁移，无V6数据库变更。打包包含Tika3.3.3 PDF/Microsoft模块、POI5.5.1、Commons CSV1.14.1等。Java17运行时还会启动同包解析子进程，无需安装LibreOffice/Tesseract或另开Java HTTP服务；正常JAR与开发classpath都须验证，生产保留完整Boot JAR和可执行java。

| 变量 | 默认 | 限制/含义 |
|---|---|---|
| KB_PARSE_LOCAL_TIMEOUT | 30s | 正、≤2m；子进程硬超时且≤job剩余deadline |
| KB_PARSE_CONCURRENCY | 2 | 1..8；本地子进程并发；MinerU另有独立并发，同步内核仍受worker并发限制 |
| KB_PARSE_HEAP_MB | 256 | 子进程-Xmx，128..1024 |
| KB_PARSE_MAX_ROWS / MAX_COLUMNS / MAX_SHEETS / MAX_PAGES | 20000 / 128 / 32 / 500 | 逐格式最大行/列/sheet/页，Excel实际遍历总行也受maxRows限制 |
| KB_IMAGE_MAX_BYTES / MAX_PIXELS | 2097152 / 16777216 | 图片字节/像素，另每边≤10000 |
| KB_PARSE_MAX_RESULT_BYTES | 8388608 | 子进程结构输出及MinerU响应有界 |
| KB_MINERU_ENABLED / BASE_URL / TOKEN | false / 空 / 空 | 自托管2.7.6服务及可选网关Bearer，仅后端配置可信URL |
| KB_MINERU_CONNECT_TIMEOUT / CONCURRENCY | 5s / 1 | 连接超时及独立外部并发1..8 |
| KB_MINERU_TIMEOUT / BACKEND / LANGUAGE | 90s / pipeline / ch | 总同步RPC预算≤job deadline；backend可pipeline/hybrid-auto-engine/vlm-auto-engine |
| KB_VLM_ENABLED / MODEL / MAX_OUTPUT_TOKENS | false / qwen-vision / 2048 | 既有AI/ai/vision，模型别名必须匹配返回，64..8192输出Tokens |

MAX_*变量完整名字为KB_PARSE_MAX_COLUMNS等；图片为KB_IMAGE_MAX_PIXELS。KB_AI_CALL_TIMEOUT/CONCURRENCY/BASE_URL/TOKEN继续控制共享AI传输，backend不接收用户传模型供应商URL或密钥。启用图片前确认AI服务vision路由模型和大小/请求体预算，真实供应商密钥留在AI服务配置。图片用base64发送给现有AI协议，但不保存到Chunk元数据/普通日志。

MinerU请部署与本适配器匹配的2.7.6及所需模型（官方当前4.x的/v1接口不兼容），仅提供PDF；开启/openapi.json，网关同时放行GET该路径及POST/file_parse。后端检查请求字段和返回版本，不依赖服务名猜协议。不配置则fidelity/OCR明确失败；fast PDF不启动系统OCR。同步HTTP无远程jobId、不轮询、202不支持；网关/后端/任务超时按总预算配置，较慢OCR可把KB_INGESTION_DEADLINE适当增大（≤15m），不要靠无界轮询。远端2.7.6按其BackgroundTasks清理输出目录；运维须监控其磁盘/超时断连后的清理，不能把远端资源当本地事务回滚。

本地生产应设置专用KB_UPLOAD_TEMP，目录仅服务账号可读写、预留磁盘预算。正常下载/解析结束立即删，崩溃残留每5m扫过30m的源/请求/结果文件，源文件有效job租约受保护。子进程监测父退出和deadline；扫描失败下次重试。两类超时分开：本地解析超时视为格式/预算失败，不自动反复耗CPU；MinerU网络/超时走持久化有限重试。

父进程和子进程分别占内存，建议backend容器至少2GiB，父Java按容器50%上限，另预留默认2×256MiB与本机PDF/图片开销。镜像沿用Java17 JRE、非root用户，子进程不需要编译器；共享DB/S3状态不进子进程。本轮只确认本地真实进程和基础设施，目标生产镜像/模型/OCR需要各自联调。

真实HTTP格式验收（保留脚本创建的样例库，可用第6阶段删除接口清理）：

```powershell
# KB_BASE_URL、KB_USERNAME、KB_PASSWORD沿用已启动的服务；Embedding仍需就绪。
python -X utf8 scripts/knowledge-base/formats_acceptance.py
# 只有已配置兼容MinerU/VLM且允许调用时再显式设置；默认不调用外部OCR/图片模型。
$env:KB_ACCEPTANCE_EXTERNAL_FORMATS='1'
$env:KB_FORMAT_EXAMPLE_OUTPUT='target/formats-http.json'
python -X utf8 scripts/knowledge-base/formats_acceptance.py
```

查看GET /api/knowledge/formats；按既有上传metadata带parseProfile，再POST入库、查询/stages。代表样例与本地/协议桩/真实外部分层结果见FORMATS.md。解析失败先查看PARSE的errorCode，不对PDF扫描件反复尝试fast。CSV默认UTF-8（允许BOM）、RFC4180逗号分隔、首记录表头；不推测GBK/分隔符，不计算CSV公式。Excel公式错误先修正源或转换为经过审阅的静态值，不能静默用空值。Word无可靠分页就不提供页码，复杂表格可转换PDF后走已配置fidelity。

以下为此前阶段记录；格式支持与解析档位以本节和支持矩阵为准。

## 第 6 阶段：人工维护、删除与恢复（当前有效）

升级前备份数据库与私有bucket，停止旧worker再部署，Flyway自动应用 **V5__content_maintenance_and_document_cleanup.sql**（保留V1–V4不改）。新增文档manual字段、Chunk审计字段、generation序号、覆盖确认及审计/清理表；旧Chunk审计人回填owner。允许删至零块；其他原约束保持。V5之后不能回退到忽略人工确认/墓碑的旧应用混跑。

| 环境变量 | 默认 | 作用 |
|---|---|---|
| KB_MAINTENANCE_MAX_BATCH_IDS | 200 | 显式chunkIds上限1..1000；全选受原maxChunks限制 |
| KB_MAINTENANCE_MODEL_DEADLINE | 40s | 人工编辑/缺向量恢复总模型预算，正且≤2m |
| KB_DOCUMENT_CLEANUP_LEASE | 4m | 租约10s..10m，且≥2×kb.storage.apiTimeout+10s |
| KB_DOCUMENT_CLEANUP_POLL_DELAY_MS | 10000 | 清理扫描固定间隔，沿用KB_WORKER_ENABLED开关 |
| KB_CLEANUP_MAX_ATTEMPTS | 5 | 文档/旧代清理最大尝试1..10 |
| KB_WORKER_BACKOFF / KB_WORKER_MAX_BACKOFF | 2s / 5m | 文档清理有限指数退避与抖动 |

模型继续复用KB_AI_*配置和全局有界并发。默认S3单API预算90s，物理删除包括查询bucket版本状态和DeleteObject，清理lease须覆盖两个API预算；调整预算时一并调lease。仅使用专用、未开启过版本控制的私有bucket；权限还须允许s3:GetBucketVersioning、s3:DeleteObject。开启或暂停版本控制会明确失败并持久化待修复任务；本版不擅自清除存储保留版本，生产有版本留存策略时先扩展适配器。ObjectLock/存储拒绝删除也将保留失败记录。

按既有启动/Compose命令启动后，执行新增真实HTTP验收（使用环境中的测试账号，创建自己的临时知识库；最终删除自己创建的文档和KB）：

```powershell
$env:KB_BASE_URL='http://127.0.0.1:8080'
# KB_USERNAME、KB_PASSWORD沿用已配置账号；模型/对象存储/worker须就绪。
python -X utf8 scripts/knowledge-base/maintenance_acceptance.py
```

脚本覆盖上传→可靠入库→GET Chunk→新增/编辑→检索新正文→独立禁用→文档关闭/重启用→显式批量范围→覆盖确认→重新入库→文档删除→回执SUCCEEDED→父库删除。可用KB_MAINTENANCE_EXAMPLE_OUTPUT指定保存实际调用JSON；KB_ACCEPTANCE_WAIT_SECONDS默认300。模型依赖真实服务配置；本地集成测试使用明确HTTP桩，不代表真实语义效果。

维护已有资源的PowerShell示例（先设置KB_DOCUMENT_ID，读取版本后修改；这是真实写入）：

```powershell
$kbHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:KB_USERNAME+':'+$env:KB_PASSWORD))}
$kbDocUrl=$env:KB_BASE_URL+'/api/documents/'+$env:KB_DOCUMENT_ID
$kbDoc=(Invoke-RestMethod -Uri $kbDocUrl -Headers $kbHeaders).data
$kbChunks=(Invoke-RestMethod -Uri ($kbDocUrl+'/chunks') -Headers $kbHeaders).data.records
$kbEdit=@{version=$kbDoc.version;content='人工确认：备份保留七天。'}|ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri ($kbDocUrl+'/chunks/'+$kbChunks[0].id) -Headers $kbHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($kbEdit))
```

诊断任务/阶段仍沿用后文命令。人工维护遇KB_JOB_ACTIVE先查询入库任务并等待或cancel；409版本冲突重新GET版本和Chunk，不盲目带旧确认重试。重新分块会覆盖人工增改删/启停，必须审阅后POST带confirmOverwriteEdited=true与expectedDocumentVersion，具体JSON见API。

删除回执的Location可持续查询；已删文档GET404是预期，不能据此判断对象已清理。检查持久化队列与审计（在目标知识库DB中，替换UUID）：

```sql
SELECT id,document_id,status,stage,attempts,max_attempts,next_run_at,lease_until,error_code,error_message
FROM kb_document_cleanup ORDER BY created_at DESC LIMIT 50;
SELECT id,document_id,actor_id,action,chunk_ids,before_summary,after_summary,created_at
FROM kb_content_audit WHERE document_id='11111111-1111-1111-1111-111111111111'::uuid ORDER BY created_at,id;
SELECT id,status,claim_token,lease_until,error_code FROM kb_ingestion_job
WHERE document_id='11111111-1111-1111-1111-111111111111'::uuid;
```

RUNNING租约未到先等待；过期时任一开启worker的实例自动接管，同一key重复DELETE安全。DATABASE失败检查数据库权限/外键/连通；OBJECT失败检查bucket、GetBucketVersioning/DeleteObject权限、网络和版本留存。修复后FAILED通过POST /api/document-cleanups/{id}/retry（无body）重排；PENDING等待nextRunAt，无须手改token；RUNNING的retry409，先等租约恢复。最多尝试的过期RUNNING也会转FAILED，避免永远卡住。默认worker关闭时所有清理意图仍保留，开启后恢复。

关系数据/向量在短PG事务内一起删除；对象在事务外删除，成功后凭有效claim确认。对象删除成功但进程崩溃时重启会重复删除并确认，不会复活文档。审计/任务/墓碑保留，清理失败也不恢复检索。父KB在存在未完成或FAILED清理时409，清理SUCCEEDED后可按当前base version删除。备份/WAL/供应商版本保留不属于本应用本轮在线物理回收范围。

以下内容保留此前阶段记录；涉及人工维护和删除时，以本节及最新 API 契约为准。

已实现管理、TXT/Markdown上传、授权原文读取、结构入库内核、可靠后台任务及结构化检索。上传保持PENDING，显式POST任务后快速202返回；默认启用数据库扫描和有限工作线程。重新入库失败保留旧证据，成功原子切换generation，再延迟清理旧代。API细节见[API.md](API.md)，真实验证与限制见[PROGRESS.md](PROGRESS.md)。

## 结构化检索与验收

POST /api/knowledge/search，使用现有Basic凭据；$kbId/$documentId来自已入库资源。证据需要已有成功activeGeneration，上传PENDING本身不会产生检索结果。

```powershell
$search=@{query='PostgreSQL connection';kbIds=@($kbId);topK=5;minScore=0.7} | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "$base/api/knowledge/search" -Headers $headers -ContentType 'application/json' -Body $search
$scoped=@{query='PostgreSQL connection';kbIds=@($kbId);documentIds=@($documentId);topK=5} | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "$base/api/knowledge/search" -Headers $headers -ContentType 'application/json' -Body $scoped
```

data为证据数组；scoreType=COSINE_SIMILARITY、score=1-distance可为负，不是概率。未设置minScore时最近邻可以主题无关，[]只表示可见候选不足/阈值无匹配/空索引。返回当前文档名、Chunk ID/序号/generation、正文和真实chapterPath/行号等sourceMetadata；fileUrl仍须授权，无签名密钥或objectKey。不同模型/本地配置/已发布AI修订混用409，分请求查询；本轮不做跨空间融合。

| 环境变量 | 默认 / 边界 |
|---|---|
| KB_SEARCH_MAX_TOP_K | 50，可配置5..200；请求省略topK=5 |
| KB_SEARCH_MAX_KB_IDS | 16，可配置1..64 |
| KB_SEARCH_MAX_DOCUMENT_IDS | 100，可配置1..500 |
| KB_SEARCH_MAX_QUERY_CHARS | 4000 Unicode code point，可配置1..16000；另受HTTP模型字节预算限制 |
| KB_SEARCH_MODEL_DEADLINE | 40s，正且≤2m；包含等待现有模型并发许可和调用，单次仍受KB_AI_CALL_TIMEOUT约束 |

无新增迁移/依赖；使用现有pgvector(1024)和精确public.<=>，未添加HNSW。过滤在排序与LIMIT之前完成，不靠回表后丢候选。模型HTTP在DB事务外，最终SQL语句读取当前代/启用/删除状态；文档PENDING/FAILED但仍有旧active时可检索旧证据。大库精确扫描受原数据库statement_timeout（默认10s）约束，须在真实数据规模评估后再决定近似索引。

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am '-Dtest=KnowledgeSearchTest,AiEmbeddingClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
python scripts/knowledge-base/search_acceptance.py
```

Java验收使用真实PG/MinIO/AWS SDK、结构解析、查询HTTP协议和余弦SQL，模型向量是明确的本地主题桩。源文件services/backend/src/test/resources/knowledge-base/search/database.md、backup.md、cooking.md，覆盖相近数据库主题和无关烘焙主题；导出target/knowledge-base/search-example.json，交付副本[SAMPLE_SEARCH.json](SAMPLE_SEARCH.json)。不把主题桩的排序当成真实供应商召回质量。

HTTP脚本需KB_BASE_URL/KB_USERNAME/KB_PASSWORD、开启worker和可用模型，可能产生真实模型费用。它实际上传三个来源、提交并等待持久化入库，再验证检索、文档范围、阈值、空库、授权原文、无效参数；保留专用fixture库/文档。KB_ACCEPTANCE_WAIT_SECONDS默认300，KB_SEARCH_EXAMPLE_OUTPUT可指定保存完整调用示例。模型质量阈值需实服评估，脚本只检查结构、范围、排序与分数含义。

排查：404检查每个KB/document归属；400/KB_SEARCH_DOCUMENT_SCOPE说明自有文档没包含在kbIds；409检查模型/local/AI修订且分开不兼容库，禁止手改绑定拼空间。502/504/503检查AI descriptor/批量协议、超时和DB连接，不要将故障按零命中处理。原阶段1–4的启动、提交与恢复命令继续有效。

## 提交、重新分块与阶段查询

使用上传示例得到的$documentId和认证$headers；完整自动验收见后文。每次逻辑提交生成一个requestId，网络超时重发时复用它：

```powershell
$base='http://127.0.0.1:8080'
$pair="$($env:KB_USERNAME):$($env:KB_PASSWORD)"
$headers=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))}
$request=@{requestId='ingest-'+[guid]::NewGuid().ToString()} | ConvertTo-Json
$submitted=Invoke-RestMethod -Method Post -Uri "$base/api/documents/$documentId/ingestion-jobs" -Headers $headers -ContentType 'application/json' -Body $request
$jobId=$submitted.data.id
Invoke-RestMethod -Uri "$base/api/ingestion-jobs/$jobId" -Headers $headers
Invoke-RestMethod -Uri "$base/api/ingestion-jobs/$jobId/stages?current=1&size=100" -Headers $headers
Invoke-RestMethod -Uri "$base/api/documents/$documentId/ingestion-jobs?status=FAILED&size=100" -Headers $headers
```

首次202；同requestId重放200返回同一个job，终态也不会重跑。同文档其他活跃任务409并提示jobId。GET任务失败状态仍HTTP200，须检查data.status/errorCode。日志按attempt+stage排列，真实耗时与计数持久化；错误不含源文本、密钥或供应商原始消息。

修改入库配置后显式新提交：

```powershell
$document=Invoke-RestMethod -Uri "$base/api/documents/$documentId" -Headers $headers
$patch=@{version=$document.data.version;ingestionSpec=@{schemaVersion=1;parser='AUTO';splitter=@{kind='STRUCTURAL';maxChars=800;overlapChars=120;tableRows=50;toleranceChars=512}}} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Patch -Uri "$base/api/documents/$documentId" -Headers $headers -ContentType 'application/json' -Body $patch
$request=@{requestId='reingest-'+[guid]::NewGuid().ToString()} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/documents/$documentId/ingestion-jobs" -Headers $headers -ContentType 'application/json' -Body $request
```

当前有活跃任务时，先等待或取消。自动重试沿用原任务快照/目标代；人工retry读取当前配置并创建新job/新代，只允许FAILED/CANCELLED：

```powershell
Invoke-RestMethod -Method Post -Uri "$base/api/ingestion-jobs/$jobId/cancel" -Headers $headers
$retry=@{requestId='retry-'+[guid]::NewGuid().ToString()} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/ingestion-jobs/$jobId/retry" -Headers $headers -ContentType 'application/json' -Body $retry
```

取消使旧token立即失效，后台中断是尽力而为；迟到结果不能发布。PATCH同样使旧输入失效。processStatus=FAILED且activeGeneration非空表示重新入库失败、旧证据仍可用，不应清空旧generation或chunk_count。

## 工作线程、模型与资源配置

| 环境变量 | 默认 / 约束 |
|---|---|
| KB_WORKER_ENABLED | true；false暂停领取/续租/GC，HTTP仍可持久化提交；维护时先停进程或取消任务，别误认为已有执行线程会被立即停止 |
| KB_WORKER_CONCURRENCY / KB_WORKER_QUEUE_CAPACITY | 2 / 0；并发1..16、队列0..64，0为无缓冲；满时任务留数据库 |
| KB_WORKER_POLL_DELAY_MS / KB_WORKER_INITIAL_DELAY_MS | 1000 / 1000 |
| KB_WORKER_LEASE / KB_WORKER_HEARTBEAT | 30s / 5s；租期1s..5m，续租周期正且≤租期1/3；不要小于数据库连接/常规短事务延迟 |
| KB_WORKER_MAX_ATTEMPTS | 3，1..10；提交时捕获，不影响已有job |
| KB_WORKER_BACKOFF / KB_WORKER_MAX_BACKOFF | 2s / 5m；指数退避加≤25%抖动、最终封顶；最大不超过1h |
| KB_GENERATION_CLEANUP_DELAY / KB_CLEANUP_POLL_DELAY_MS | 10m / 10000；清理延迟0..7d |
| KB_CLEANUP_MAX_ATTEMPTS | 5；失败清理每maxBackoff后重试，耗尽FAILED，人工修复后重排 |
| KB_INGESTION_DEADLINE | 120s，正值且≤15m；每次尝试总预算，续租不能延长此截止 |

定时线程池3个线程，领取、续租、GC独立；不在调度线程中执行模型。并发与KB_AI_CONCURRENCY、S3连接数、数据库池（默认10）配合设置；增大工作并发不会突破模型HTTP全局并发上限。大文档若持续耗尽deadline，先查阶段耗时，调预算/分块后人工重试。线程中断/进程关闭保留租约，过期后恢复；数据库状态是执行权依据。

backend必须访问已提供descriptor/expectedConfigRevision的新版AI服务。KB_AI_BASE_URL默认http://127.0.0.1:8081；Docker Desktop示例为http://host.docker.internal:8081（Linux需指向可达AI地址或自行配置host-gateway）。KB_AI_TOKEN可选，仅发受控网关，不转发用户Basic；模型供应商密钥仍配置在AI服务。

KB_AI_CONNECT_TIMEOUT=5s、KB_AI_CALL_TIMEOUT=30s（包含完整响应体）、KB_AI_CONCURRENCY=2、KB_AI_BATCH_SIZE=10（受descriptor批次限制）、KB_AI_MAX_BATCH_BYTES=65536、KB_AI_MAX_RESPONSE_BYTES=4194304。按真实AI错误协议区分RATE_LIMIT/AUTH/INVALID_RESPONSE；无fallback。首次发布原子钉住AI修订，其后变化须新库重建。真实供应商联调尚未完成，测试使用HTTP协议桩。

解析/写入预算保持：KB_MAX_PARSED_CHARS=1000000（UTF-16）、KB_MAX_CHUNKS=1000、KB_HARD_CHUNK_CHARS=8192、KB_MAX_EMBEDDING_CHARS=16384（code point）、KB_INDEX_WRITE_BATCH_SIZE=100。分块由文档spec控制；WHOLE_DOCUMENT仍受硬上限。S3阻塞由现有连接/socket/API超时限制，不能强制终止任意CPU解析；失权者即使继续计算也不能发布。

## 卡住任务和失败阶段恢复

先GET job与stages。PENDING且nextRunAt在未来为退避；长期到期PENDING检查worker开关、进程存活、线程池饱和及DB/S3/AI可达。RUNNING且leaseUntil过期，正常扫描会关闭旧attempt并退避恢复；达到maxAttempts则FAILED。RUNNING租约持续更新但阶段长时间不变，检查该阶段外部超时与attempt_deadline；不能无限续租。可用下列只读SQL定位（在目标数据库执行，所有者仅排查自己的scope）：

```sql
SELECT id,document_id,status,stage,attempts,max_attempts,next_run_at,lease_until,attempt_deadline,error_code
FROM kb_ingestion_job WHERE tenant_id='tenant-local' AND owner_id='user-local'
ORDER BY created_at DESC LIMIT 100;
SELECT attempt,stage,status,started_at,ended_at,duration_ms,input_count,input_unit,output_count,output_unit,error_code,error_message
FROM kb_ingestion_stage_log WHERE job_id='b167deae-568d-4270-a13e-ff734e43ce9c'
ORDER BY attempt,started_at;
```

正常恢复无需修改数据库：修复连接/配置后开启worker或重启进程，等待租约和退避到期；失败/取消则用retry API新requestId。可先cancel再retry，安全替代“手工解锁”。禁止手改claim_token、lease_until、active_generation或把RUNNING直接改PENDING；这些字段共同构成执行权。多实例可同时启动，领取和发布统一base→document→job锁序。

同库Chunk/向量/active/job原子发布；事务外模型失败不覆盖旧代。GC在文档锁内再次检查active和活跃目标代，删除Chunk时pgvector外键级联，job/stage审计和原文件保留。查询GC：

```sql
SELECT c.id,c.document_id,c.generation,c.status,c.attempts,c.next_run_at,c.error_code,c.completed_at
FROM kb_generation_cleanup c JOIN kb_document d ON d.id=c.document_id
WHERE d.tenant_id='tenant-local' AND d.owner_id='user-local' ORDER BY c.created_at DESC;
```

GC FAILED表示有限尝试已耗尽，先修复数据库/约束/权限；授权数据库运维人员仅重排指定清理记录（不修改active或执行租约）：

```sql
UPDATE kb_generation_cleanup c SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),error_code=NULL,completed_at=NULL
FROM kb_document d WHERE c.id='b167deae-568d-4270-a13e-ff734e43ce9c' AND c.document_id=d.id
AND d.tenant_id='tenant-local' AND d.owner_id='user-local' AND c.status='FAILED';
```

到期PENDING清理会自动处理；崩溃事务回滚仍PENDING。非active/非活跃目标检查始终执行，不能通过重排删除当前证据。上传外部对象补偿仍沿用storage-operations人工恢复，见后文，不属于generation GC。

## 可靠后台任务验收（第4阶段回归）

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' -pl services/backend -am '-Dtest=DocumentUploadTest,IngestionWorkerPropertiesTest,ChunkingServiceTest,AiEmbeddingClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
python scripts/knowledge-base/ingestion_acceptance.py
```

Java验收使用真实PostgreSQL17/pgvector和MinIO，模型为本地HTTP桩；涵盖并发提交/领取、重复执行、过期晚到、有限重试、永久失败、取消、执行器饱和/拒绝、GC失败与重新发布。实际启动两个开启调度的Java进程，在EMBED时强制结束第一进程，再由第二进程恢复同job；日志和证据在target/knowledge-base/process-*.log、process-restart-evidence.json。[样例Chunk](SAMPLE_CHUNKS.json)保留展示/向量化文本差异。无Docker时集成明确跳过，不能算通过。

Python脚本只用标准库；先注入KB_BASE_URL/KB_USERNAME/KB_PASSWORD并启动已配置模型的backend（可能产生真实模型费用）。它实际上传、202提交、幂等重放、轮询、查看阶段、PATCH重新分块并等待新代，默认最多300s（KB_ACCEPTANCE_WAIT_SECONDS可调1..3600）。输出并保留专用验收库/文档，本阶段尚无文档删除接口。公开检索API已在第5阶段交付，见本手册检索部分；检索代过滤同时有真实DB集成验收。

## 构建与测试

当前机器在.cache/stage5-build完成全量verify（2026-10-22），2026-10-24核对171个构建源文件并对最终OpenAPI声明重新package；业务源码与全量测试版本一致。隔离构建避免覆盖旧AI进程占用的JAR。backend已复制回services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar；AI副本另存services/ai-service/target/rag-ai-service-0.1.0-SNAPSHOT-stage5.jar。产物校验和、HTTP调用与统计见[STAGE5_VALIDATION.json](STAGE5_VALIDATION.json)。一般发布也应在独立构建目录产出。

JDK17、Boot3.5.16；从仓库根执行，可用现有IDEA Maven替代Wrapper：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/maven/repository' verify
```

仅后端及依赖可增加 `-pl services/backend -am`；Linux/macOS使用 `sh ./mvnw`。产物：services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar。Docker可用时运行一次性PostgreSQL17/pgvector、MinIO，验证AWS SDK真实读写、补偿、V1/V3升级和任务恢复。Docker不可用时容器测试明确跳过，不能将构建成功当成集成验证。

LocalPostgresTest可用KB_TEST_JDBC_URL/KB_TEST_DB_USERNAME/KB_TEST_DB_PASSWORD启用第二环境；**只连接可丢弃数据库**，每个用例清空文档、操作和知识库表。Testcontainers已独立覆盖数据库，不需要连接业务库。

## 迁移、本地设施与启动

V4由Flyway在启动时自动执行；不改V1–V3校验和。先停止旧版本入库调用/worker再升级，迁移账户须能操作既有表且已安装public.vector；V3遗留运行任务保留maxAttempts=1，过期后失败可人工retry。备份数据库与bucket后按既有发布流程上线，不支持新旧任务协议混跑。

复制deploy/knowledge-base/.env.example为同目录.env，替换数据库、账号、MinIO凭据与归属；该文件被Git忽略。MinIO根身份仅供本地，真实S3使用限定bucket/key权限的身份或工作负载角色。

```powershell
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml up -d postgres minio minio-init
```

默认127.0.0.1端口：数据库54329、S3 9000、MinIO控制台9001。minio-init创建私有knowledge-base bucket，成功退出后启动应用。应用不自动建bucket或公开访问。

在终端注入配置，占位值须替换：

```powershell
$env:KB_DB_URL='jdbc:postgresql://127.0.0.1:54329/knowledge_base?connectTimeout=5&socketTimeout=30'
$env:KB_DB_USERNAME='kb'
$env:KB_DB_PASSWORD='<与 .env 一致>'
$env:KB_USERNAME='kb-admin'
$env:KB_PASSWORD='<16–72 UTF-8字节密码>'
$env:KB_TENANT_ID='tenant-local'
$env:KB_USER_ID='user-local'
$env:KB_STORAGE_ENABLED='true'
$env:KB_S3_ENDPOINT='http://127.0.0.1:9000'
$env:KB_S3_REGION='us-east-1'
$env:KB_S3_BUCKET='knowledge-base'
$env:KB_S3_PATH_STYLE='true'
$env:AWS_ACCESS_KEY_ID='<与 .env 的 KB_S3_ACCESS_KEY 一致>'
$env:AWS_SECRET_ACCESS_KEY='<与 .env 的 KB_S3_SECRET_KEY 一致>'
java -jar services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar
```

.env是Compose输入，直接启动JAR不自动读取。完整容器部署：

```powershell
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml up --build -d
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml logs --tail 100 backend
```

Dockerfile使用非root Temurin17 JRE，需要能拉取基础镜像；JAR与设施实测不等于应用镜像构建成功，结果分别记录。`down`保留两个持久卷；生产不要附加`-v`。修改环境变量不会轮换已初始化数据库角色密码。远程访问使用HTTPS，本地HTTP仅供本地设施。

## 配置

| 环境变量 | 默认 / 作用 |
|---|---|
| KB_PORT | 8080 |
| KB_DB_URL / KB_DB_USERNAME / KB_DB_PASSWORD | 本机54329/knowledge_base、kb；无默认密码 |
| KB_DB_POOL_SIZE / KB_DB_CONNECTION_TIMEOUT_MS / KB_DB_STATEMENT_TIMEOUT_MS | 10 / 5000 / 10000 |
| KB_USERNAME / KB_PASSWORD / KB_TENANT_ID / KB_USER_ID | 必填服务端账号和归属，运行时BCrypt |
| KB_STORAGE_ENABLED | false；未启用时上传503，元数据独立可用 |
| KB_S3_ENDPOINT | 空使用AWS区域endpoint；MinIO填写http(s)，不含userinfo/query/fragment |
| KB_S3_REGION / KB_S3_BUCKET / KB_S3_PATH_STYLE | us-east-1 / 无默认bucket / false |
| AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN | SDK默认凭据链，也支持配置文件或工作负载角色；不进业务表 |
| KB_MAX_UPLOAD_BYTES | 20971520（20MiB），实际流限制，允许1..1GiB |
| KB_MAX_UPLOAD_SIZE / KB_MAX_REQUEST_SIZE | 20MB / 21MB，Spring DataSize的MB为MiB；同步调整，multipart须留元数据开销 |
| KB_UPLOAD_TEMP | 空为系统temp，自定义目录需可写，部署方限制临时卷容量 |
| KB_MODEL_ENABLED | true，允许选择默认profile |
| KB_EMBEDDING_CONFIG_VERSION | bailian-text-embedding-v4-1024-v1，空间/版本改变须用新配置标识，不覆盖旧绑定 |
| KB_BASE_URL | 验收脚本默认http://127.0.0.1:8080，非本机要求HTTPS |
| KB_ACCEPTANCE_MAX_BYTES | 可选上传脚本超限检查，须等于部署上限；脚本支持≤64MiB |

可通过Spring外置YAML配置S3超时与并发：

```yaml
kb:
  storage:
    connect-timeout: 5s
    socket-timeout: 30s
    attempt-timeout: 40s
    api-timeout: 90s
    upload-lease: 5m
    max-connections: 8
```

用 `--spring.config.additional-location=file:./kb-local.yml` 加载。超时须正值，租约必须大于S3总超时+30秒；最多2次S3尝试，连接池获取使用connect-timeout。Servlet直接落盘，业务按8KiB流计数/哈希。并发受Tomcat线程与S3连接池限制，可配置server.tomcat.threads.max。进程崩溃后残留的本地spool，由部署方在**停服后**清理专用临时目录，勿清理运行中的文件。

服务端模型目录kb.models.default-profile/profiles：enabled/model-id/provider/model-name/dimension(固定1024)/embedding-space/config-version。默认与现有AI qwen-embed/bailian/text-embedding-v4一致；GET /api/kb-models查看。创建保存快照与SHA-256，不访问模型API，允许配置不等于供应商已联调；后续入库前必须验证真实身份/维度/空间/修订，禁止静默fallback。

认证沿用stateless Basic，没有默认通用账号。多账号可外置kb.security.accounts列表，每项username/password/tenant-id/user-id，列表整体替换；用户名大小写不敏感唯一，归属保持稳定，重启生效。不重建IAM。原文须经应用鉴权，bucket保持私有，不提供持久签名URL。

## 迁移

自动执行V1/V2/V3，历史表kb_flyway_schema_history；validate开启、clean/baseline禁用。V1不改，V2添加模型审计、文档和上传操作。旧库保留UNBOUND，GET获取version后显式绑定：

```powershell
curl.exe -u "$($env:KB_USERNAME):$($env:KB_PASSWORD)" -X PUT 'http://127.0.0.1:8080/api/kbs/<旧库ID>/embedding-binding' -H 'Content-Type: application/json' --data-binary '{"version":1,"embeddingProfileId":"qwen-embed-1024"}'
```

V3需pgvector已安装于public（镜像内已提供扩展文件），迁移账户需CREATE EXTENSION权限或由管理员预先CREATE EXTENSION vector WITH SCHEMA public；运行账户只需表DML和类型/函数访问。向量固定1024维，暂不建近似索引。默认PostgreSQL17；角色需schema建表/索引及DML权限，生产可单独配置spring.flyway.user/password。升级前备份，失败保留历史与数据，不能修改已执行迁移或手工标记成功。

## 可执行验收与请求

在另一个已配置应用凭据的终端：

```powershell
curl.exe http://127.0.0.1:8080/actuator/health
python scripts/knowledge-base/acceptance.py
$env:KB_ACCEPTANCE_MAX_BYTES='20971520'
python scripts/knowledge-base/upload_acceptance.py
```

元数据脚本创建并软删除空库；上传脚本输出并**保留**唯一知识库与文档，因为本阶段无文档删除且禁止删非空库。在专用环境运行，勿用python -O。跨身份隔离、数据库失败和补偿删除失败由集成测试验证，不向生产库注入故障。

手动调用示例（PowerShell）：

```powershell
$base='http://127.0.0.1:8080'
$pair="$($env:KB_USERNAME):$($env:KB_PASSWORD)"
$headers=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))}
$kb=Invoke-RestMethod -Method Post -Uri "$base/api/kbs" -Headers $headers -ContentType 'application/json' -Body '{"name":"upload-demo","embeddingProfileId":"qwen-embed-1024"}'
[IO.File]::WriteAllText("$PWD/sample.txt",'Local knowledge base evidence.',[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$PWD/metadata.json",'{"name":"sample","ingestionSpec":{"schemaVersion":1,"parser":"AUTO"}}',[Text.UTF8Encoding]::new($false))
$upload=curl.exe -sS -u $pair -X POST "$base/api/kbs/$($kb.data.id)/documents" -H "Idempotency-Key: $([guid]::NewGuid())" -F 'file=@sample.txt;type=text/plain' -F 'metadata=<metadata.json;type=application/json' | ConvertFrom-Json
$documentId=$upload.data.id
Invoke-RestMethod -Uri "$base/api/documents/$documentId" -Headers $headers
curl.exe -f -u $pair "$base/api/documents/$documentId/file" -o sample-downloaded.txt
Invoke-RestMethod -Uri "$base/api/kbs/$($kb.data.id)/documents?current=1&size=20&status=PENDING&name=sample" -Headers $headers
```

示例创建保留数据，重名须更名。JSON使用UTF-8文件避免旧PowerShell向curl传参转义差异；自动验收不依赖shell转义。

## 失败与恢复

- 400/413/415：核对UTF-8、非空、扩展名、真实内容、两个上传上限、严格spec（overlap小于maxChars）。对象写入前拒绝。
- 409：按code处理名称、version、非空库、模型锁定等；迁移模型须新库重建，禁止直接改表。PATCH只保存元数据/spec并置PENDING，保留旧activeGeneration供检索；再次显式提交入库任务成功后才READY。
- PUT失败可能已写入对象，所有异常均尝试清理；数据库提交结果不确定时先检查引用，数据库不可用则保留意图，不能盲删。恢复后查看操作；COMPLETED可原请求重放，其余先清理后用新幂等键。
- GET storage-operations遍历所有分页，按idempotencyKey定位请求，查看status/attempts/nextRunAt/leaseUntil/errorCode。活跃租约409，过期UPLOADING或CLEANUP_PENDING可重试。COMPLETED/已被文档引用的对象409，禁止清理。

```powershell
curl.exe -u "$($env:KB_USERNAME):$($env:KB_PASSWORD)" 'http://127.0.0.1:8080/api/kbs/<kbId>/storage-operations?size=100'
curl.exe -u "$($env:KB_USERNAME):$($env:KB_PASSWORD)" -X POST 'http://127.0.0.1:8080/api/kbs/<kbId>/storage-operations/<operationId>/retry-cleanup'
```

上传对象补偿仍无自动扫描器。人工重试按数据库行锁、租约和claim_token协调多实例；nextRunAt为建议时间，成功CLEANED，重复删除幂等。数据库恢复不依赖内存队列。

下载头发送后流中断不能再返回JSON，客户端按长度/哈希核对并重新GET。404涵盖错误归属，503不暴露内部异常。健康仅检查进程/数据库，不证明S3或模型在线。

同时备份数据库和私有bucket，按一致时间窗口演练恢复，核对引用和上传操作。外部管理员删对象或延迟到达的S3写入需存储审计与后续对账，补偿不是跨系统原子事务。
