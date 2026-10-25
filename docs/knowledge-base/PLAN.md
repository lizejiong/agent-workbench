# 知识库建设方案

## 当前实施状态：第 10 阶段 / P9 API 管理入口与交付（2026-10-25）

实际复核：web只有.gitkeep，没有前端框架、路由、组件库或构建配置。本轮采用约定的无前端交付：完整OpenAPI、Postman调用集合、API管理导航和可执行HTTP验收，不引入新的前端服务。用户进一步明确不建设复杂用户管理；继续沿用服务端账号映射与tenant/owner私有归属，不增加用户/角色/租户CRUD。operator仅为现有账号的可选服务端布尔配置，默认false，用于保护全局指标；不改变业务资源权限。

| 交付职责 | 仓库根相对路径 |
|---|---|
| API导航、当前参数默认值、分页审计 | services/backend/src/main/java/com/lzj/rag/backend/operations/ManagementController.java |
| Micrometer持久状态指标与模型调用统计 | 同目录KnowledgeBaseMetrics.java、ObservedModelClients.java |
| 提交后关联日志 | 同目录OperationLog.java；ingestion/IngestionJobService.java、IngestionKernel.java及refresh/RefreshState.java复用 |
| 审计扩展、最近成功入库时间 | services/backend/src/main/resources/db/migration/V8__management_audit.sql |
| API与集合 | services/backend/src/main/resources/static/openapi/knowledge-base.yaml、knowledge-base.postman_collection.json |
| 集合生成与一键HTTP验收 | scripts/knowledge-base/build_collection.py、delivery_acceptance.py；复用此前阶段独立脚本 |
| 交付/权限/审计/日志/指标回归 | services/backend/src/test/java/com/lzj/rag/backend/ManagementDeliveryTest.java |
| 部署 | deploy/knowledge-base/compose.yml、.env.example；services/backend/Dockerfile |

管理范围通过现有API完成：库搜索/分页/模型信息/文档数，文档上传/URL导入/状态/启停/大小/块数/最近入库时间，解析档位与分块参数，任务与阶段，Chunk正文/embeddingText/来源/人工维护，刷新设置和检索测试。集合明确409版本/并发冲突、人工覆盖确认及显式applyAll；真实任务状态从后端查询。此交付没有图形管理页面，不宣称前端构建已完成。

可观测性沿用Actuator/Micrometer及现有内容审计：有界、无UUID标签的指标；PG任务/阶段/清理/刷新/关键词状态每10秒缓存读取，阶段FunctionTimer由持久阶段日志计算，重启后仍可查询。Embedding/Rerank逻辑调用失败计数与耗时按进程累计。全局/actuator/metrics仅operator可读，其他敏感Actuator路由拒绝。日志在提交后关联kbId/documentId/jobId/generation/stage，不打印正文、模型原始响应或异常堆栈。

V8扩展同一kb_content_audit：documentId允许空以记录库操作；库/文档元数据、入库状态、刷新设置/状态由同事务trigger写入脱敏摘要，现有Chunk维护摘要保留。私有资源操作人来自服务器认证身份，后台操作保留持久owner，不从请求接受actor。名称仅摘要哈希，正文仅既有contentHash/数量。审计目前无自动归档；运维需按保留要求备份/归档，不能当完整合规审计系统。

首版仍只使用pgvector(1024)作为向量后端，默认精确余弦；ES仅为可选BM25。关闭Boot的多余ES自动客户端，所有关键词网络访问由自有可选适配器控制，避免未配置ES影响基础健康。数据库、S3、ES各自一致性边界及失败回收规则保持前述阶段设计。最终真实验证、模拟边界和未完成外部依赖以PROGRESS与RUNBOOK为准。

本阶段代码/API与本地整体验收已完成：250项测试、238通过/12可选跳过，8份实际JAR HTTP脚本和故障/权限/重启验证通过；无前端构建可执行。应用镜像基础层下载失败及有界重试未完成，镜像构建/启动为明确未验证项；真实供应商与生产设施待部署方配置联调，不计作已完成的质量验收。

2026-10-25提交整理：按用户要求清除4个Java文件中的6处无用导入，业务逻辑和本阶段范围不变；编译打包复核通过。知识库及必要AI联动、文档/迁移/部署/验收资料纳入本地Git提交；缓存、运行产物、IDE文件和凭据排除。

## 第 9 阶段 / P8 可配置混合检索（2026-10-24 至 25）

本轮交付结构化检索，继续使用唯一pgvector和既有权限/解析/任务；项目没有独立知识问答需求，不新增answer接口或LLM调用。现有AI服务已有原生百炼/SiliconFlow精排适配，因此backend的RerankClient调用/ai/rerank，不另假设OpenAI通用协议。

| 职责 | 实际路径（services/backend/src/main/ 下，Java包前缀com/lzj/rag/backend/） |
|---|---|
| 参数、通道和漏斗 | java/com/lzj/rag/backend/search/HybridProperties.java、KeywordProperties.java、SearchChannel.java、VectorSearchChannel.java、KeywordSearchChannel.java |
| 并行、失败隔离、权限预检 | java/com/lzj/rag/backend/search/HybridRetrievalService.java；原KnowledgeSearchService复用为向量通道 |
| RRF去重及精排 | java/com/lzj/rag/backend/search/SearchResultPostProcessor.java、RrfRerankPostProcessor.java；model/RerankClient.java、AiRerankClient.java |
| 关键词BM25及真实中文分词 | java/com/lzj/rag/backend/search/KeywordIndex.java、ElasticsearchKeywordIndex.java |
| 同步与恢复 | java/com/lzj/rag/backend/search/KeywordSyncService.java、KeywordSyncWorker.java、KeywordSyncController.java |
| 回表及有效修订过滤 | java/com/lzj/rag/backend/search/EvidenceRepository.java |
| 事务Outbox迁移 | resources/db/migration/V7__keyword_index_outbox.sql |

公共API仍在search/KnowledgeSearchController.java与KnowledgeSearchTypes.java。测试为services/backend/src/test/java/com/lzj/rag/backend/HybridRetrievalTest.java，HTTP验收为scripts/knowledge-base/hybrid_acceptance.py。

1. 没有正在运行或在仓库配置的全文服务。本机缓存另一个项目的ES8.19.17/IK镜像，只用全新隔离容器验证，不复用其数据/卷。客户端沿用Boot3.5.16 BOM的Elasticsearch Java Client8.18.8，对8.19服务按官方前向兼容策略实测；生产提供可选ES8.19.17 profile，不加ES向量功能。[兼容规则](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/8.19/introduction.html)。
2. 默认关键词/精排关闭，AUTO选择VECTOR，保持原/search数组和余弦分数/错误语义；启用关键词后AUTO为HYBRID，也可请求VECTOR/KEYWORD/HYBRID。HYBRID两个通道用零缓冲有界线程池并行；独立deadline，失败/超时丢弃该通道，另一路仍可返回，全部失败503而非伪装[]。实际策略、各通道状态与精排状态在/retrieve的data和/search响应头中明确返回。
3. 默认真实中文分析器cjk（内置CJK二元分词），可显式选smartcn/ik_max_word/ik_smart；启用时检查服务版本、_analyze中文tokens、专用索引schema/analyzer/BM25映射，不支持则通道和同步明确失败，不换普通substring或暗降standard。新索引默认1分片，生产副本和资源由运维评估。[BM25](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/index-modules-similarity.html)、[SmartCN](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)。
4. V7的kb_keyword_sync为每文档一条可合并Outbox。Chunk INSERT/UPDATE/DELETE及文档新增/名称/启停/activeGeneration/墓碑变化通过数据库trigger在原事务内递增revision、标记PENDING；全部维护、入库和物理GC路径均覆盖。关闭ES仍持久化意图，启用后补建既有文档。worker持有base→document→outbox锁领取，租约/claimToken/attemptDeadline校验，事务外批写；失败有限退避、FAILED可授权retry。
5. ES记录包含tenant/owner/kb/document/Chunk、generation、正文、enabled、真实来源及syncRevision/contentHash。记录ID=doc:revision:chunk，不覆盖其他修订。写完后短PG事务只在仍持租约且revision未变时切换syncedRevision/indexedTarget；失权或旧输入不能声明新版本可见。配置更换索引/分析器须新专用索引并统一实例配置，目标指纹不同会重新排同步；不混跑不同索引配置。
6. 关键词查询先从PG读取当前有权、启用、未删且revision=syncedRevision的doc:revision集合，以ES terms过滤versionKey后再BM25排序；默认最多2000个已同步文档，超出需缩小documentIds。这样旧代/旧修订不占召回配额。随后按当前PG Chunk/activeGeneration/归属/正文hash再次回表，拒绝索引伪造或过期内容；竞态过滤不足时最多2轮、每轮最多200候选，限制达到会标记，不放行无效内容。[terms过滤](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/query-dsl-terms-query.html)。
7. kb_keyword_cleanup保存旧修订回收watermark、租约/claimToken/次数/失败。确认新修订后延迟40s删除syncRevision<watermark，旧执行者和旧清理都不能删新修订；失败可恢复/重排，成功后每小时再巡检该水位，覆盖迟到旧写。DB权威过滤立即生效，物理ES删除是另一一致性边界；已索引且有未完成同步/清理时阻止删父KB。
8. 初始漏斗每通道50→融合20→可选精排→topK默认5；topK更大时有界扩大召回/融合预算，不悄悄截成20。合并按Chunk ID去重，保留通道原rank/score/scoreType；RRF=sum(weight/(k+rank))，k默认60、两路权重默认1，禁止把余弦/BM25直接相加。平分按Chunk UUID稳定排序。
9. AiRerankClient复用共享有界AI传输和既有/ai/rerank，请求固定服务端模型、query、候选id/text、topN=全部候选；校验返回model、index范围/唯一、chunkId映射、有限score及完整数量，按score/index排序。响应乱序可正确映射；缺分/重复/越界/不足/超时/换模型均整体回落融合顺序。原供应商差异仍由AI ProviderClient负责，真实密钥仅环境配置。[百炼原生精排](https://help.aliyun.com/zh/model-studio/text-rerank-api)。
10. scoreType=RRF时score与fusionScore是排名融合值，rerankScore独立，最终可按精排分排序，不伪装概率。minScore仍只用于显式VECTOR余弦；混合请求拒绝歧义阈值。minRerankScore可请求设置或服务端默认，要求启用精排；精排失败且有阈值时fail closed返回空证据/THRESHOLD_UNAVAILABLE，不把融合分当精排分。精排前后回表，模型等待期间的修改/禁用/删除不泄露旧正文，来源始终来自当前PG白名单。

同步/GC、外部HTTP和模型均有配置的超时、并发、批量和次数上限。实际能力/联调结果以PROGRESS为准，未配置ES或模型时不能宣称真实语义/精排质量已验收。

## 历史实施状态：第 8 阶段 / P7 URL 导入与自动刷新（2026-10-24）

本轮复用原Spring MVC/Bean Validation、可信tenant+owner、MyBatis-Plus、Flyway、S3、唯一pgvector及数据库入库任务。没有既有远程文档/调度平台/网络规则服务，新增受控下载和数据库schedule，不引入MQ或第二套解析/向量内核。原有AI/MinerU传输保持既有协议；新增OkHttp4.12.0只用于用户来源下载。

| 职责 | 实际实现路径（services/backend/src/main/ 下） |
|---|---|
| 策略、DNS固定、逐次重定向、超时、流式SHA-256 | java/com/lzj/rag/backend/refresh/RemoteDownloader.java、RemoteProperties.java |
| URL导入、共享上传补偿、幂等完成 | refresh/UrlImportService.java；document/DocumentService.java的uploadSource（Java包前缀同上） |
| API与契约 | refresh/RemoteController.java、RemoteTypes.java |
| 持久化触发、领取、恢复与续租 | refresh/RefreshService.java、RefreshWorker.java |
| 发布及文档生命周期校验 | refresh/RefreshState.java；chunk/ChunkIndexWriter.java、ChunkMaintenanceService.java；document/DocumentCleanupService.java |
| 旧对象/失败候选清理 | refresh/RemoteObjectCleanup.java；parser/ParserSpoolCleanup.java |
| 增量迁移 | resources/db/migration/V6__remote_documents_and_refresh.sql |
| 关键验证 | services/backend/src/test/java/com/lzj/rag/backend/RemoteRefreshTest.java、RemoteSourceStub.java、refresh/RemoteNetworkPolicyTest.java（仓库根相对路径） |
| 可执行HTTP验收 | scripts/knowledge-base/refresh_acceptance.py（仓库根相对路径） |

1. 默认关闭remote；服务端明确配置精确allowedHosts、ports，HTTPS默认允许、HTTP默认拒绝。无通配域名、URL认证信息、fragment、Cookie、自传鉴权头或系统代理。默认拒绝私网/回环/链路/保留/文档地址及IPv6过渡地址，内部来源须同时列入独立trustedPrivateHosts；链路本地/云元数据地址仍拒绝。DNS使用有界解析线程池，逐次检查全部结果，再以OkHttp Dns固定该次连接地址；禁用自动重定向/重试，逐跳重新校验、拒绝HTTPS降级，跨origin不转发validators。依据：[OkHttp4.12 Dns](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dns.kt)、[客户端配置](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt)。
2. 下载连接5s/读10s/总30s、默认3次重定向、并发2、上限min(remote20MiB,upload20MiB)，都可配置。强制identity编码，流式临时文件和SHA-256，不将来源或大块内容写日志。显式filename通过同一扩展名/Tika MIME/UTF-8/ZIP边界/ParserRegistry校验，不信任HTTP文件名和Content-Type。常规关闭立即删临时文件，崩溃残留每5m扫描超过30m的本组件spool，失败保留可重试。
3. 导入为有界同步下载+共享上传，不在HTTP内解析/向量化。kb_url_import先持久化RUNNING意图，按可信KB/tenant/owner/Idempotency-Key唯一并绑定完整请求哈希；COMPLETED重放不再下载。同一短事务插入URL文档、完成import、初始化禁用schedule；ingest=true时同时提交既有持久化job。对象先写S3，数据库失败沿用上传补偿；提交结果不确定时先查已提交引用，禁止盲删。失败/10m过期import标记FAILED，修复/清理后使用新key；不隐式重放变化的远程字节。
4. kb_document新增source_url，source_type允许UPLOAD/URL，其余原文件字段仍代表当前已保存版本。kb_refresh_schedule为URL文档一对一，保存cron/timezone/enabled/version/nextRunAt、成功HTTP基线、lastStatus/reason/runId/failureCount和owner/until/token；首次导入保存不建立成功基线，只有相应文件第一次激活后写入。schedule版本独立于文档版本。
5. kb_refresh_run保存触发类型、幂等身份、documentVersion/configRevision/scheduleVersion、人工覆盖确认、PENDING/RUNNING/WAITING_INGESTION/SUCCESS/FAILED/SKIPPED、attempts/deadline/nextRunAt/lease/token、候选objectKey/文件信息/validators、jobId、HTTP状态/耗时/脱敏原因。部分唯一索引保证一个文档一个活跃refresh；它与原入库活跃任务在base→document锁下互斥。自动触发遇人工改动或手动入库记录SKIPPED；人工触发遇活跃任务409，有人工变更须显式确认当前文档版本。仅内容变化时重建，不把“刷新未变”当“重新分块”。
6. CronExpression.next在数据库当前Instant转换到显式ZoneId后计算，再保存UTC TIMESTAMPTZ；错过多个时刻合并成一次到期执行，结束后从当前时间计算下一次，不补跑积压。默认租约45s，5s续租；run总预算15m、下载/租约恢复最多3次、2s指数退避至60s。模型部分继续用原job的有限重试。领取先预留本地容量，SKIP LOCKED按base→document→run/schedule锁序；每实例同时在途RUNNING/WAITING不超过配置并发。重复投递通过execution_started_at CAS阻止重复下载/改候选。依据：[Spring6.2 CronExpression](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/support/CronExpression.html)。
7. 条件请求只使用已激活字节对应的ETag/Last-Modified。304直接SKIPPED；200下载后hash与当前已激活文件相同也SKIPPED，允许为**同一已激活版本**轮换validators，不解析、不Embedding。任一重定向（包括同origin不同URI）都清除条件头；首版未存effective URI，因此重定向来源不持久化目标validators，下次继续完整下载比较hash。无条件支持时同样比较hash。元数据缺失不虚造；HTTP错误、重定向/大小/空文件/超时均明确失败或有限重试。
8. 内容变化先在kb_remote_object_cleanup保留WAITING对象意图，再写新稳定key。短事务为原IngestionJobService创建job，只替换其不可变来源快照，并存refreshRunId/refreshClaimToken；doc原objectKey/hash与activeGeneration继续指旧版本。WAITING期间由refresh续租，IngestionWorker使用同一ParserRegistry→Chunking→Embedding→ChunkIndexWriter执行。激活同时验证job和refresh/schedule的有效token/owner/lease/deadline、三种版本、启用/墓碑/人工确认；同PG事务切换Chunk/向量代、原文件元数据、成功HTTP基线和SUCCESS，再排旧generation/对象GC。不会返回新旧代混合证据。
9. 崩溃后过期run可重领：取消/失效旧job，旧候选排清理，同run有限退避后以新token重新下载并提交新job/目标代；迟到执行者不能激活或覆盖阶段/基线。最终解析/模型/发布失败保留旧版本，失败基线不前移，下次仍获取待处理内容。刷新关联job的人工retry明确409，改用新refresh请求，避免按旧live文件快照错误重试。
10. 文档禁用/删除同事务停用schedule、失效refresh、取消job并记录SKIPPED；重新启用文档不擅自重开schedule，Chunk独立enabled仍保留。配置/版本变化令旧run不能激活。对象删除跨PG/S3边界：WAITING→RETAINED（成功新对象）或PENDING；旧对象PENDING，事务外删除后按token确认。预留至少2×S3 API timeout+30s宽限覆盖失权后已启动PUT；活跃文档/活跃候选引用受保护。清理PENDING/RUNNING/SUCCEEDED/FAILED、有界次数/退避/租约，失败可按RUNBOOK修复后重排；未完成/FAILED阻止删除父KB。仍要求专用未版本化bucket。

验收使用真实PG/pgvector、MinIO、受控HTTP来源与明确Embedding协议桩；生产出口、防火墙、真实文档站点和供应商联调另行记录。阶段8不扩展混合检索、回答模型或管理界面。实际验证结果见PROGRESS。

## 历史实施状态：第 7 阶段 / P6 多格式统一入库（2026-10-24）

仍复用同一个 IngestionKernel、数据库任务/租约、ChunkingService、EmbeddingClient、ChunkIndexWriter 与唯一 pgvector。没有引入URL导入、刷新平台、回答模型或第二向量后端；本阶段不新增业务迁移，parseProfile保存在既有JSONB ingestionSpec，旧快照缺省fast。

- 请求增加 ingestionSpec.parseProfile=fast|fidelity，默认fast；parser仍AUTO/TEXT/MARKDOWN。ParserRegistry按parseProfile × 解析器类别 × 真实MIME注册；AUTO按已检查的格式分类，重复认领启动失败。TXT/Markdown/CSV/Excel的fidelity明确别名到相同本地路径，图片两档都用VLM；Office fidelity无能力415；PDF fidelity只调用指定MinerU，不因服务失败切回fast。
- PreparedUpload/SupportedFiles继续流式落盘、哈希、严格UTF-8文本检查；新用TikaInputStream检测Office容器真实MIME，再检查扩展名匹配。拒绝宏格式、SVG、通用压缩包和伪扩展名；ZIP检测前限制4096条目/64MiB展开总量/16MiB单条目/大条目100倍压缩比。
- FETCH下载由String改为校验过大小/哈希/MIME的临时二进制文件，PARSE得到同一ParsedDocument后立即关闭删除临时文件，再SPLIT/EMBED/PUBLISH。耗时I/O与模型仍在激活事务外，失败不改变旧代。任务输入快照包含parseProfile，重新入库人工覆盖确认继续有效。
- 本地二进制/CSV解析由LocalFormatParser启动**同一应用包**中的ParserProcessMain，非新服务。默认并发2、独立-Xmx256m、30s硬超时，并受原任务deadline约束；超时/中断杀掉进程。子进程也监测父进程和绝对deadline，父崩溃后自行退出。请求JSON不含MinerU/模型配置密钥；标准输出/错误丢弃，不将源文件或解析器异常细节写普通日志。
- 为新增解析模块升级Tika到同3.x维护线3.3.3，POI与其依赖对齐5.5.1，PDFBox3.0.8；新增PDF/Microsoft两个Tika模块、POI OOXML/scratchpad、Commons CSV1.14.1、Commons IO2.22.0、Jsoup1.21.2。沿用Java17/Boot3.5.16；不切换Tika4.x/POI6.0大版本。兼容性以真实样例和最终依赖树/测试验证，版本依据：[Tika3.3.3](https://tika.apache.org/3.3.3/index.html)。
- LocalFormatExtractor/StructuredContentHandler把Tika XHTML的可获得标题、段落、列表、代码、规则表格转换为结构块；PDF明确NO_OCR、不抽嵌入图片/附件/XFA表单，不执行外部程序。PDF仅保留真实页号，PPT仅实际幻灯片号，Word不推测分页。扫描PDF无正文422/KB_OCR_REQUIRED；跨页/幻灯片段落不合并，表格跨单元格不任意截断，复杂合并Office表格明确报错。
- Excel用POI WorkbookFactory/DataFormatter/FormulaEvaluator，首个非空行作表头，多sheet分别成表，保留格式化数值/日期、空单元格和真实数据行号；不下载外部工作簿。公式不支持或计算错误422，不能变空值。CSV严格UTF-8/RFC4180，保留引号内换行/空字段、校验每行宽度；以记录号而非物理行号定位，=开头仅作为字面文本。Chunk表头重复，embeddingText保留字段：值；跳过Excel空行不丢真实行对应。
- PDF fidelity适配显式协议mineru-2.7.6-file-parse：先在有界子进程校验页数，再检查部署的/openapi.json确有/file_parse和所需字段，流式multipart files传原PDF，return_content_list=true/return_images=false。严格核对返回version=2.7.6/backend/results及JSON字符串content_list；解析text/text_level、page_idx、bbox、HTML规则/合并表格、公式和图片caption，不存img_path/base64。矩形表格展开rowspan/colspan受行列/字符上限。当前官方4.x V1是另一协议，不冒充兼容：[固定2.7.6源码](https://github.com/opendatalab/MinerU/blob/release-2.7.6/mineru/cli/fast_api.py)、[当前V1差异](https://opendatalab.github.io/MinerU/usage/http_api/)。
- MinerU为同步有界RPC，默认90s且不超过任务deadline；只接受200完成结果，202/重定向/协议不同明确失败，**不进入轮询（轮询次数0）**。503/限流/网络/外部超时使用既有持久化有限退避；未配置、永久格式/协议错误直接FAILED。没有构造一个无法恢复的内存远程任务队列，服务端临时资源按已适配版本BackgroundTasks契约回收；不支持V1异步ID恢复或Word/PPT直接发旧MinerU。
- PNG/JPEG用ImageIO只读头部尺寸，默认≤2MiB/16Mi像素/每边10000，再由VlmClient复用现有AI的/ai/vision协议和共享有界HTTP客户端/超时/信号量。指定服务端模型别名、提示词要求描述+可见文字；空内容、截断、换模型均失败。文本进入同一分块/Embedding；来源明确generated/VLM_DESCRIPTION_AND_OCR，不把描述行号伪造成图片原文位置。未启用返回KB_VLM_UNAVAILABLE，不宣称本地已OCR。
- ParserSpoolCleanup每5m最多处理256个过30m的本组件临时文件；按数据库job有效租约保护源文件，不追随符号链接。正常路径立即删除，父进程崩溃后临时文件仍在磁盘、可在重启后扫描回收。设置独占可写KB_UPLOAD_TEMP，数据库审计保留，二进制不进Chunk/日志。

实际路径均位于services/backend/src/main/java/com/lzj/rag/backend/parser、model/{VlmClient,AiVlmClient}，并小幅扩展document/PreparedUpload/DocumentTypes/DocumentService、ingestion/IngestionKernel、chunk/ChunkingService与search来源白名单。GET /api/knowledge/formats公开已认证调用者可见的配置支持矩阵，不泄露内部URL/token，也不把configured当真实服务验证。完整范围、样例和实测分类见[FORMATS.md](FORMATS.md)；交付状态见PROGRESS。

以下为此前阶段记录；格式支持与解析档位以本节和支持矩阵为准。

## 历史实施状态：第 6 阶段 / P5 人工维护与生命周期（2026-10-24）

沿用 Java17/Spring MVC、MyBatis-Plus、既有可信 principal、EmbeddingClient 和唯一 pgvector(1024)。本轮不引入新依赖、MQ、远程刷新或其他格式。所有业务写入遵循 base → document → job/cleanup 锁序。

| 职责 | 实际路径（services/backend/src/main/java/com/lzj/rag/backend/ 下） |
|---|---|
| Chunk ORM、请求/响应、维护接口 | chunk/StoredChunk.java、StoredChunkMapper.java、MaintenanceTypes.java、ChunkMaintenanceController.java |
| 人工内容、独立启停、版本、审计 | chunk/ChunkMaintenanceService.java、MaintenanceProperties.java |
| 文档墓碑和可恢复物理清理 | document/DocumentCleanupService.java |
| 覆盖确认及自动代发布 | ingestion/IngestionJobService.java、IngestionJobController.java、chunk/ChunkIndexWriter.java |
| S3 删除边界 | storage/S3ObjectStorageClient.java |
| 增量迁移 | services/backend/src/main/resources/db/migration/V5__content_maintenance_and_document_cleanup.sql |
| 集成测试与 HTTP 验收 | services/backend/src/test/java/com/lzj/rag/backend/ContentMaintenanceTest.java；scripts/knowledge-base/maintenance_acceptance.py |

1. 新增/编辑先检查归属、文档 version、活跃任务和已发布 activeGeneration；调用模型位于事务外。随后短事务锁住父库/文档，再检查相同 version/generation、未删/启用及没有 PENDING/RUNNING 入库，原子提交 Chunk、pgvector、计数与审计。索引失败回滚全部更新，不存在新正文配旧语义的静默状态。首版人工添加要求文档至少成功入库一次，不为未解析文件伪造 generation/job。
2. 人工正文统一 LF，非空、无 NUL/孤立代理字符，默认最多8192 code point（同时受 maxEmbeddingChars 限制）。embeddingText 就是人工正文，不再追加旧章节或表格语义。更新 SHA-256、字数、edited=true；Token 使用 ASCII ceil(n/4)+非ASCII code point 数的粗估，tokenCountEstimated=true，自动解析块未知 Token 仍为 NULL/false。来源标注 MANUAL/manual/originalFileContainsContent=false，删除失准的原文件章节/行号；原文件本身不被人工编辑覆盖。
3. ID 服务端 UUID；新增序号由 generation.next_chunk_index 单调分配，删除最后一个也不复用。V5 允许 generation.chunk_count=0。document.chunkCount/generation.chunk_count 表示当前代现存总块数（包括单独禁用块）；实际可检索数另受 doc.enabled 与 chunk.enabled 过滤。
4. Chunk 禁用只改独立 enabled，pgvector 同库过滤立即生效，保留物理向量；启用必须文档启用，缺向量才重新调用模型恢复。文档禁用/删除取消活跃任务、增加版本并使 claimToken 失效；文档重新启用保留每个 Chunk 原 enabled，只修复其中启用块的缺失向量。Chunk 内容维护遇活跃入库返回409，模型等待期间出现入库同样拒绝。
5. 批量必须非空唯一 chunkIds（默认最多200）或 applyAll=true 且省略 chunkIds；空数组、重复ID、两种范围同时指定均400。所选块必须全部属于当前文档当前代，任一不符404且全批不生效。applyAll 显式作用于当前代，最大总块数沿用 kb.ingestion.maxChunks。
6. V5 为 document 增加 manual_revision/has_manual_changes；人工增改删和 Chunk 启停均留下不可遗漏的覆盖标志，包括已删除人工块。重新入库和 retry 必须显式 confirmOverwriteEdited=true + 审阅后的 expectedDocumentVersion，否则409。两个参数参与 requestId 幂等身份；已确认同键重放返回原任务。成功新代覆盖全部人工正文/新增/删除/Chunk启停后清除 hasManualChanges；失败保留旧代及标志。manualRevision 单调保留。
7. 无既有通用审计设施，因此新增 kb_content_audit：可信 tenant/owner/actor、动作、chunkIds、前后版本/哈希/字数/启用状态摘要，和变更同事务；不存正文/密钥/供应商错误。Chunk 增加 created_by/updated_by，旧记录回填 owner；自动写入同步补齐审计字段。
8. DELETE 文档短事务写墓碑、enabled=false、activeGeneration=NULL、chunkCount=0、取消/失效运行任务，并创建唯一 kb_document_cleanup 意图。立即不可检索、查询/原文件404；202返回可查询清理回执。同归属重复删除返回同一回执。停止刷新：本仓库尚无刷新表/平台，本阶段不提前新建；未来刷新领取必须核对墓碑/启用/版本。
9. 清理状态 PENDING/RUNNING/SUCCEEDED/FAILED，阶段 DATABASE/OBJECT，持久化次数/下次执行/租约/owner/token/脱敏错误。短事务删除所有 generation 的 Chunk（向量外键级联）与代，并结束旧代 GC；阶段提交后事务外删除稳定对象key，再按仍有效 token/租约确认。定时扫描复用 worker 开关及3线程调度器，每次最多领取1项，候选最多32，SKIP LOCKED；进程崩溃靠过期租约恢复。默认最多5次、有限退避；到上限的过期 RUNNING 转 FAILED，可授权 POST retry。删除失败或清理未完成（含FAILED）阻止删父KB。
10. 清理租约默认4m，必须至少为2×S3 API timeout+10s，覆盖 GetBucketVersioning 和 DeleteObject 两个有界调用；失权执行者不能写回。稳定key永不复用，即使迟到删除也不会删到新资源。源读取使用 try-with-resources，无持久化入库临时对象；上传 spool 在既有关闭路径回收，失败上传沿用原 upload_operation 补偿。
11. 当前 S3 适配要求专用未开启版本控制的私有 bucket，删除需 GetBucketVersioning 与 DeleteObject 权限。已启用/暂停版本控制返回 KB_STORAGE_VERSIONED_BUCKET，持久化重试且不假报物理删除成功；需要保留版本的生产设施须先设计显式版本清除/留存策略再扩展适配器。依据：[AWS DeleteObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html)；锁依据：[PostgreSQL17](https://www.postgresql.org/docs/17/explicit-locking.html)。

配置从既有 application.yml/env/Compose 装配：kb.maintenance.maxBatchIds/modelDeadline/cleanupLease、kb.worker.documentCleanupPollDelay；复用模型全局并发和原有限重试上限。执行与恢复命令见 RUNBOOK，契约见 API 的 H 节。后续多格式 → URL刷新 → 混合检索/回答 → 管理界面按单轮授权推进。

以下内容保留此前阶段记录；涉及人工维护和删除时，以本节及最新 API 契约为准。

## 历史实施状态：第 5 阶段 / P4 结构化知识检索（2026-10-24）

新增POST /api/knowledge/search；复用既有MVC/Bean Validation、EmbeddingClient、可信principal和唯一PgvectorVectorStore。不引入搜索框架、第二向量后端、回答模型、近似索引或新的数据库迁移；V3/V4的数据结构与范围索引可直接服务本轮查询。

| 职责 | 实际路径（services/backend/src/main/java/com/lzj/rag/backend/下） |
|---|---|
| 请求/证据契约与控制器 | search/KnowledgeSearchTypes.java、KnowledgeSearchController.java |
| 权限范围、模型分组、查询向量、来源组装 | search/KnowledgeSearchService.java |
| 上限和模型调用预算 | search/SearchProperties.java、application.yml的kb.search |
| 多库/文档范围的全局精确查询 | vector/VectorStore.java、PgvectorVectorStore.java |
| 真实DB/S3/HTTP测试 | services/backend/src/test/java/com/lzj/rag/backend/KnowledgeSearchTest.java |
| 多主题来源样例 | services/backend/src/test/resources/knowledge-base/search/{database,backup,cooking}.md |
| HTTP验收 | scripts/knowledge-base/search_acceptance.py |

1. query非空、strip后默认最多4000 Unicode code point，拒绝NUL/未配对代理字符；kbIds必填、默认最多16；可选documentIds默认最多100，提供时不允许空数组；两者必须唯一、无null。topK默认5，上限默认50（可配置5..200）；minScore为可选有限[-1,1]余弦相似度。未知字段、自传tenant/namespace/provider/vector拒绝400。
2. 先校验全部知识库归属/墓碑、全部文档归属/墓碑及其属于所选KB，再进行任何模型调用；不悄悄忽略越权项。可信tenant/owner仅来自principal。未知/越权404；自有文档不在选中库400/KB_SEARCH_DOCUMENT_SCOPE；未绑定模型409/KB_MODEL_UNBOUND。
3. 按EmbeddingClient.Binding中的modelId/provider/modelName/dimension/embeddingSpace/configVersion/localRevision分组，再核对所有已钉住的AI修订。本版**一个请求限一个分组、一个非空AI修订**，不兼容时在向量化前409/KB_SEARCH_MODEL_MISMATCH，客户端分请求查询；不进行跨空间数值混排。相同绑定的空库可与已发布库一起查；所有库尚未钉住AI修订时直接[]，不调用模型。
4. 同一空间只对query调用一次现有EmbeddingClient，expectedAiRevision为已发布索引修订；沿用身份、维度、数量、非有限数值、超时/并发限制和无fallback规则。模型调用在数据库事务外，默认总模型等待预算40s（正且≤2m，单次HTTP仍服从KB_AI_CALL_TIMEOUT），不自动重试同步搜索请求。模型/数据库失败是明确错误，不伪装空结果。
5. PgvectorVectorStore保留旧单库query入口，统一到新增多Scope查询。NamedParameterJdbcTemplate展开并绑定UUID集合、向量、修订、阈值和LIMIT；没有拼接用户SQL。查询前重验KB归属/绑定；SQL在计算距离与全局LIMIT前JOIN并过滤parent墓碑、可信tenant/owner、指定KB/document、doc.enabled、chunk.enabled、active_generation、namespace/model/local/AI修订。保留PENDING/FAILED文档的旧active证据，不用processStatus排除它们。
6. 对所有有效候选精确执行public.<=>；按距离升序、同距离chunkId升序，跨兼容KB只截取一次全局topK，避免每库配额截断。minScore在LIMIT前过滤。没有外部向量回表过滤窗口或近似索引召回缺口，无须补召回；代切换/禁用发生在模型调用期间时，以最终SQL语句快照为准，返回后资源可能继续变化。
7. 返回data证据数组，空结果为[]；字段chunkId/kbId/documentId/documentName/chunkIndex/generation/content/score/scoreType/sourceMetadata/fileUrl。score=1-cosineDistance（仅浮点边缘钳制[-1,1]），scoreType=COSINE_SIMILARITY，不是概率，不与BM25/精排混用。content只取数据库展示正文，不用embeddingText或生成答案冒充证据。
8. 来源返回解析器真实提供的chapterPath、sourceBlocks/行号、表格/字符位置等白名单，若有页码可透出；TXT/Markdown没有页码就不造值。fileUrl为服务端生成的/api/documents/{id}/file，读取仍需认证，不含objectKey、存储密钥或供应商URL。来源至少通过documentId+chunkId+generation+chunkIndex定位本次证据；人工Chunk管理接口留待下一轮。

真实来源样例与完整输入/输出交付见[SAMPLE_SEARCH.json](SAMPLE_SEARCH.json)，重新生成与真实验证统计见PROGRESS。结构/权限验收使用真实PG/MinIO和明确标注的本地HTTP主题向量桩，真实供应商召回质量仍需接入后评估。分数定义与精确查询依据：[pgvector官方文档](https://github.com/pgvector/pgvector#querying)。

以下第4阶段及更早内容为历史快照；当前查询契约以本节和API的G节为准。后续按人工维护与删除→多格式→URL刷新→混合检索/回答→管理界面交付推进，每轮须明确授权。

## 历史实施状态：第 4 阶段 / P3 可靠后台任务与重新入库（2026-10-22）

本轮沿用第3阶段同一同步内核、Spring MVC、已有认证归属、PostgreSQL/pgvector、Flyway和AI协议；没有MQ设施，因此采用数据库队列 + @Scheduled + 有界ThreadPoolTaskExecutor。上传仍仅保存PENDING，由显式提交接口触发后台入库。首版向量后端仍只有pgvector(1024)，本轮没有公共检索、人工Chunk维护或URL刷新。

| 职责 | 实际路径（services/backend/src/main/java/com/lzj/rag/backend/下） |
|---|---|
| 提交、幂等、输入快照、领取与恢复 | ingestion/IngestionJobService.java |
| 新API与旧只读路由兼容 | ingestion/IngestionJobController.java、IngestionQueryController.java |
| 扫描、容量、独立续租 | ingestion/IngestionWorker.java、IngestionWorkerConfiguration.java、IngestionWorkerProperties.java |
| 固定内核与受租约保护的原子发布 | ingestion/IngestionKernel.java、IngestionContext.java、chunk/ChunkIndexWriter.java |
| 可重试旧代/失败代清理 | ingestion/GenerationCleanupService.java、GenerationCleanupScheduler.java |
| 迁移 | services/backend/src/main/resources/db/migration/V4__reliable_ingestion_jobs.sql |
| 验收 | DocumentUploadTest、model/AiEmbeddingClientTest、ingestion/IngestionWorkerPropertiesTest；scripts/knowledge-base/ingestion_acceptance.py |

### 数据、状态与并发决定

1. V4增量迁移，不改V1–V3。任务新增created_at、input_snapshot、attempt_deadline、execution_started_at、retry_of；lease/start字段允许PENDING为空；状态扩展PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED，attempts=0..max_attempts、上限10。UNIQUE(tenant_id,owner_id,request_id)，同文档PENDING/RUNNING部分唯一索引。旧V3快照按已有关联回填，旧job的max_attempts=1与原deadline保留；停止旧版本worker后升级，不支持V3/V4混跑。
2. POST只做短事务，捕获document.version/config_revision、原object_key/hash、完整ingestion_spec、不可变模型绑定与namespace。requestId为body必填8..128位安全ASCII；作用域为服务端可信tenant/owner，同键同document+retryOf返回同一job当前视图，输入后续改变也不悄悄替换快照；同键不同目标409。同文档不同键已有活跃任务409并提示jobId。先按调用者/键取得事务advisory锁，再按base→document→job行锁序处理；数据库唯一约束兜底。
3. PENDING到期扫描，先锁base再document再job，均SKIP LOCKED且重新检查状态。领取增加attempt与单调claim_token、设置lease_owner/lease_until/每次attempt_deadline。阶段begin/end、AI修订、续租、释放、失败和最终发布全部校验本次token/owner/attempt及数据库真实时钟；重复投递同一领取凭证被execution_started_at的一次CAS挡住，不影响合法执行。没有依赖内存队列恢复业务任务。
4. 默认并发2、队列0（SynchronousQueue）；配置可设有界队列0..64。先预留本地容量再领取，满时任务留PENDING且attempts不变；执行器拒绝后按token归还未开始领取，attempt不消耗。3个调度线程隔离扫描、续租与GC；每5s续租、租期30s（1s..5m，续租周期≤租期1/3），不得复活过期租约或超过单次120s总deadline（≤15m）。内存表只记录当前进程的容量与中断句柄，不是业务队列。
5. RUNNING过期由扫描器关闭本attempt的运行中阶段，记录KB_INGESTION_EXPIRED并失效旧token；尚有尝试则PENDING退避，耗尽则FAILED。默认最多3次，指数退避2s起、25%以内抖动、封顶5m。网络/超时、模型429或AI RATE_LIMIT、暂时存储/数据库故障可重试；非法格式、空内容、维度/响应错误、模型身份漂移/认证拒绝不可重试。AI真实错误信封的RATE_LIMIT、AUTH、INVALID_RESPONSE按其现有协议映射，不假设任意供应商等价；只保留固定安全错误码/消息。取消在短事务中置CANCELLED、关闭阶段并递增token；取消后的模型异常不重试。进程中断保留持久化租约，等待恢复，不当成人工取消。
6. 每次新提交分配单调target_generation；同job自动重试沿用这一目标代和不可变输入（同库原子写入，在提交前不存在部分可见的Chunk/向量）；人工retry创建新job/新generation，retry_of指向原终态job，读取当前配置。job_type首版仍INGEST。先PATCH配置再POST新任务即可重新分块；当前仍有活跃任务时先取消或等待结束。
7. FETCH/PARSE/SPLIT/EMBED在激活事务外。PERSIST/PUBLISH按base→doc→job锁序再次核对未删除、enabled、latest_job_id、document.version/config_revision、模型/AI修订和有效token/租约；Chunk/vector/generation、active_generation、READY和SUCCEEDED在一个短事务内提交，提交前再次按真实时钟校验。失败保留旧active_generation和chunk_count。检索只用文档当前代且doc.enabled与chunk.enabled分别过滤，不更新人工Chunk的独立enabled。
8. kb_generation_cleanup持久化id/document_id/generation/status/attempts/next_run_at/error_code/时间，按文档/代唯一。成功发布原子排队旧代，终态失败/取消排队目标代；自动重试中的目标代禁止清理。默认延迟10m，每10s扫描；同库清理以base→document→cleanup锁保护整个短事务，重新检查非active、非活跃job目标，删除Chunk（向量外键级联）/generation并标记成功。崩溃回滚后仍可领取；失败另事务记录，默认5次、5m间隔后FAILED，人工修复后可按RUNBOOK重排。原文件不因入库失败/清理被删除，job/stage审计保留。V4顺便排队已存在的非active历史代。

新增依赖：无；调度/线程池/JdbcTemplate已在现有Spring依赖内。kb.worker与kb.ingestion、kb.ai通过既有application.yml/环境变量装配。生产默认开启worker；测试profile默认关闭定时竞争，测试显式驱动同一领取/执行路径，并另启动真实开启调度的Java进程验证重启。

技术依据：[PostgreSQL行锁与一致锁序](https://www.postgresql.org/docs/17/explicit-locking.html)、[Spring 6.2调度与线程池](https://docs.spring.io/spring-framework/reference/6.2/integration/scheduling.html)。详细接口见API；配置、状态查询和恢复命令见RUNBOOK。下一轮仍须显式授权，顺序为向量检索→人工维护与删除→多格式→URL刷新→混合检索/回答→管理界面交付。

以下第3阶段及更早内容为历史快照，冲突时以上述当前状态为准。

## 历史实施状态：第 3 阶段 / P2 同步入库内核（2026-10-21）

本轮只实现同步内核与只读观测，不安装调度器、工作线程池、消息队列或公开长请求接口。既有P1上传仍仅写PENDING；由内部调用者明确调用IngestionKernel。后续P3复用同一内核和持久化记录。

| 职责 | 实际路径（services/backend/src/main/java/com/lzj/rag/backend/下） |
|---|---|
| 解析协议、注册及TXT/Markdown | parser/DocumentParser.java、ParsedDocument.java、ParserRegistry.java、TextDocumentParser.java、MarkdownDocumentParser.java |
| 分块与写入组织 | chunk/Chunk.java、ChunkingService.java、ChunkIndexWriter.java |
| 服务间向量化 | model/EmbeddingClient.java、AiClientProperties.java、AiEmbeddingClient.java |
| 单一向量后端 | vector/VectorStore.java、PgvectorVectorStore.java |
| 同步管线与持久化状态 | ingestion/IngestionKernel.java、IngestionContext.java、IngestionProperties.java、IngestionJobService.java、IngestionQueryController.java |

1. 解析使用Tika Core 3.2.3检测内容，严格UTF-8（允许BOM，CRLF/CR统一LF），重新核对S3原始字节大小/哈希/MIME；最大解析量默认100万UTF-16单元。CommonMark 0.27.1 + GFM表格AST提取标题、段落、列表、代码和表格。标题栈生成chapterPath，来源保存真实行号、blockIndex、代码语言或表格数据行范围；普通文本不虚构页码。注册按profile+MIME唯一，重复认领启动失败；AUTO据已验证原名扩展名区分同为text/plain的TXT/Markdown。
2. 新默认spec：STRUCTURAL、maxChars=1024 Unicode code point、overlapChars=128、tableRows=50、toleranceChars=512。旧文档已保存的1200/120保持原值，不批量改写。自然段只合并顺序相邻且同章节的段落，长段按自然边界/字符窗口继续拆；重叠不跨代码、表格或章节。代码/列表优先完整块或整行；表格重复表头、最多50数据行并按长度提前收组，embeddingText用“字段：值”。软上限target+tolerance，单行/单表格行允许超过软限但不超过硬限8192；超硬限明确失败，不切单元格。WHOLE_DOCUMENT显式整篇单块，同样受硬限；不静默拆分。embeddingText最多16384 code point，章节前缀只加一次。
3. Chunk ID由document/generation/index/contentHash生成UUID，重试同一代输入稳定、跨代不同；保存content、embeddingText、SHA-256、code point数、source metadata和本地/AI两种配置修订。token_count未知为NULL，不用估算冒充真实分词。
4. 复用现有AI服务，不引入OkHttp或供应商第二套实现。AI ApiTypes/AiController/ModelRegistry/AiService新增只读embedding descriptor、expectedConfigRevision和实际provider/space/revision；Model增加configVersion。显式指定model（KB始终显式）禁止任何fallback，原未指定model的旧客户端保留既有兼容行为。供应商层继续现有model/input/dimensions及index适配。
5. backend使用**共享JDK17 HttpClient**访问AI原生协议（较D1的RestClient计划作窄化调整）：有界BodySubscriber与future超时覆盖完整慢响应体，禁止重定向，响应最大4MiB，连接5s/调用30s，全局并发2。按min(服务端批次、配置10)和编码后JSON 64KiB拆批；逐项按index重排，拒绝空结果、数量缺失、重复/越界index、非有限/溢出/下溢成零向量、实际维度错误、身份或版本变化。首次成功发布在同一事务钉住kb_knowledge_base.embedding_ai_config_revision；后续运行的descriptor必须与之相同，发布和向量外键再次校验，避免跨运行混入新修订。无静默换空间；密钥留在AI服务，backend可向内网网关附加配置token，不转发用户Basic凭据。
6. V3__synchronous_ingestion_and_pgvector.sql创建public.vector扩展、kb_ingestion_job/stage_log/generation/chunk/vector(1024)，扩展文档状态/next_generation/latest_job_id，复合归属外键。首版只有pgvector与精确cosine查询，普通范围索引；暂不建HNSW，不混建Milvus/ES。1024维由固定列类型约束，应用再次验证；后续索引须按实际维度能力和召回验收引入。
7. job先短事务保留输入spec/哈希/模型修订、单调target_generation、截止租约和claim_token；FETCH→PARSE→SPLIT→EMBED在事务外；PERSIST/PUBLISH由ChunkIndexWriter单事务批写关系Chunk和pgvector并切换active_generation/job=SUCCEEDED。默认最多1000块，每批100，写入事务30s；采用同库原子写入，**没有外部向量索引的最终一致性缺口**。历史成功代保留，失败/配置变更不覆盖旧代。发布按base→document→job锁序检查版本、输入、模型修订、最新job、token与数据库真实时钟有效租约；日志使用clock_timestamp测量，避免事务固定时间误报耗时。
8. 同步job仅RUNNING/SUCCEEDED/FAILED，attempt=maxAttempts=1，无自动重试。失败stage/count/code/固定脱敏message落库，旧证据仍可检索；再次调用创建新job/新代，不复用失败代。进程中断后可内部显式recoverExpired，下一次ingest也会先处理过期租约；旧worker无法发布。业务不依赖内存队列。只读GET jobs/单job/stages复用认证归属；HTTP POST提交仍405，留给后续异步阶段。

真实验收样例：services/backend/src/test/resources/knowledge-base/sample.md；DocumentUploadTest复用既有真实PostgreSQL/MinIO设施并调用同步内核，导出target/knowledge-base/sample-chunks.json，交付副本见[SAMPLE_CHUNKS.json](SAMPLE_CHUNKS.json)。向量由**本地HTTP协议桩**生成，真实模型供应商联调尚未完成；数据库vector(1024)、S3 SDK、解析/分块/写入/查询均是真实实现。CommonMark版本与pgvector能力依据：[CommonMark发布说明](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.27.1)、[pgvector官方文档](https://github.com/pgvector/pgvector)。

以下P1/D1为历史快照；其中“拟新增”“尚未实现”须以本节和最新进度为准。下一阶段仍须显式授权。


## 历史实施状态：第 2 阶段 / P1（2026-10-21）

本轮授权基础管理与本地上传，下面D1调研与P2–P9设计保留为后续依据。已实现路径为 `services/backend/src/main/java/com/lzj/rag/backend/{knowledgebase,model,document,storage,persistence,api}`；迁移新增 `services/backend/src/main/resources/db/migration/V2__documents_and_upload_operations.sql`，不改V1。

- 新默认 `/api/kbs` 与旧管理路由共用业务服务；模型目录 `/api/kb-models`。新增document目录的Document/Mapper/Service/Controller/Types/PreparedUpload/UploadOperations；storage目录为ObjectStorageClient/StorageProperties/StorageConfiguration/S3ObjectStorageClient。
- 本阶段不需要模型调用，采用后端受控 `kb.models` 目录并保存快照与SHA-256，默认映射现有AI的qwen-embed/bailian/text-embedding-v4/1024。**这是服务端允许配置，不代表已探测供应商在线可用性**。D1拟定的AI不可变元数据协议延至同步入库内核阶段；开始调用前必须校验服务端真实模型、空间、维度与修订，禁止静默fallback。
- 模型绑定一经建立保持不可变，即使空库也不直接换模型；旧V1数据保留UNBOUND，显式PUT embedding-binding后才能上传。namespace以知识库UUID生成并在tenant/owner范围唯一，墓碑不复用。首版仍只建设pgvector向量后端，本轮不创建向量列/索引或要求扩展权限。
- V2扩展kb_knowledge_base模型绑定、审计；创建kb_document（包含本阶段原名/文件/归属/spec/PENDING/generation预留）和kb_upload_operation。文档名采用**库内存活lower(name)唯一**，较D1允许重名更严格，使本轮重复名称验收可预测。文档SHA-256不设唯一约束。
- kb_upload_operation是上传外部写入意图，不冒充kb_ingestion_job：id与未来文档ID一致；kb_id/tenant_id/owner_id、idempotency_key、payload_hash、object_key、status、lease_until、claim_token、attempts、next_run_at、error_code、created_at/updated_at持久化；唯一(kb_id,tenant_id,owner_id,idempotency_key)，有复合归属外键。状态UPLOADING→COMPLETED或CLEANUP_PENDING→CLEANED。内部错误只存固定安全code。此表不执行解析，不引入入库任务/日志/刷新表。
- 上传先校验并有界落盘，再提交意图；S3 PUT在事务外；文档+COMPLETED原子提交。补偿先检查引用并领取带claim_token租约，事务外DELETE，条件确认结果。数据库不可用/提交结果不确定时禁止盲删，数据库恢复后从意图查询和重试。父库行锁协调上传、文档修改和空库删除。未完成操作也阻止删库。
- 本轮人工查询与重试清理，不提前实施P3定时扫描器。配置超时/连接池/大小限制可调，应用默认不开存储，缺配置返回503；S3使用AWS SDK v2.55.8默认凭据链，Tika Core 3.2.3仅做检测。原文UTF-8/TXT/Markdown，本轮不做Markdown AST或切块。
- 验证入口：DocumentUploadTest（真实PostgreSQL+MinIO及注入失败）、PreparedUploadTest（实际流超限）、原KnowledgeBaseContract；`scripts/knowledge-base/upload_acceptance.py`为真实HTTP上传验收，`acceptance.py`继续验证元数据兼容。部署扩展原Compose而非创建第二个服务。具体实测结果以PROGRESS为准。

当前最短链路：认证→模型允许目录绑定→知识库→校验文件/spec→持久化上传意图→S3稳定key→同事务文档PENDING+操作COMPLETED→授权读原文。到此停止；后续P2同步内核、P3可靠任务、P4向量检索才会形成可检索证据。


## 0. D1历史调研范围与事实边界

更新日期：2026-10-21。本轮是用户新定义的“第 1 阶段：调研、设计与文档”，只更新 PLAN.md、PROGRESS.md、API.md，不修改业务代码、POM、迁移、配置、OpenAPI 源文件或验收脚本，不启动服务或调用付费模型。

上一轮已经交付的“元数据基础”保留为实施基线，不等于本文九个实施阶段均已完成。本文使用 D1 表示本轮文档工作，P1–P9 表示后续实施阶段。RUNBOOK.md 和当前静态 OpenAPI 仍仅描述已经可运行的元数据 API。下文“拟新增”路径、配置、表、接口均尚未实现。

检查根目录、父目录与仓库文件清单未发现 AGENTS.md。当前工作区含上一轮未提交代码，必须保留；每轮先读本文件与 PROGRESS.md，再核对实际代码。项目是 Java，无需跨语言重建服务。

## 1. 仓库盘点与差距

表内路径均相对仓库根目录，精确到当前存在的文件；空目录不视为已有服务或设施。

| 能力 | 当前实现路径及可复用接口 | 缺失部分与处理 |
|---|---|---|
| 根构建 | pom.xml；packages/java-starters/pom.xml；mvnw、mvnw.cmd | JDK 17、Boot 3.5.16、Maven 多模块已确立；不升级父版本或另建 Java 工程 |
| 业务启动 | services/backend/pom.xml；services/backend/src/main/java/com/lzj/rag/backend/BackendApplication.java；services/backend/src/main/resources/application.yml | MVC/Validation/Actuator 已装配；知识库业务直接扩展此模块 |
| AI 启动 | services/ai-service/pom.xml；services/ai-service/src/main/java/com/lzj/rag/ai/AiApplication.java；services/ai-service/src/main/resources/application.yml | 独立 WebFlux 服务、默认 8081；backend 默认 8080；部署 Compose 尚未接入 AI |
| 认证授权 | services/backend/src/main/java/com/lzj/rag/backend/security/KnowledgeBaseSecurityConfiguration.java、KnowledgeBaseSecurityProperties.java、KnowledgeBasePrincipal.java | Basic + BCrypt + 无会话；principal.tenantId()/userId() 来源于服务端账号映射；无 IAM、共享权限、角色管理或 JWT 平台；本次不扩建 IAM |
| 现有知识库 | services/backend/src/main/java/com/lzj/rag/backend/knowledgebase/KnowledgeBaseController.java、KnowledgeBaseService.java、KnowledgeBaseMapper.java、KnowledgeBaseTypes.java、KnowledgeBase.java | 复用 create/list/get/update/delete、归属过滤、版本 CAS、名称去重和墓碑；缺模型绑定、文档、Chunk、任务、检索 |
| 数据库与 ORM | services/backend/pom.xml；services/backend/src/main/java/com/lzj/rag/backend/persistence/UuidTypeHandler.java；application.yml | PostgreSQL + MyBatis-Plus 3.5.17 + JDBC；没有 JPA/MySQL/Milvus 业务接入；继续沿用，不迁移现有业务库 |
| 迁移 | services/backend/src/main/resources/db/migration/V1__knowledge_base_metadata.sql；application.yml 中 kb_flyway_schema_history | 只有 kb_knowledge_base；Flyway core/PostgreSQL 模块由 Boot 管理为 11.7.2，驱动 42.7.11；V1 不可改写，后续新增 V2 起的迁移 |
| 向量设施 | deploy/knowledge-base/compose.yml；services/backend/src/test/java/com/lzj/rag/backend/ContainerPostgresTest.java | 镜像为 pgvector/pgvector:0.8.7-pg17-bookworm，但没有 CREATE EXTENSION、向量列、索引或 VectorStore；首版只建设 pgvector |
| 文件存储 | 仓库无 ObjectStorageClient/S3/OSS/MinIO 业务适配、bucket 或上传接口 | 拟新增 ObjectStorageClient 与 AWS SDK v2 S3 适配；不能把本机缓存镜像当成项目已有存储 |
| HTTP 客户端 | services/ai-service/src/main/java/com/lzj/rag/ai/provider/ProviderClient.java：WebClient + Reactor Netty，连接超时/响应大小限制 | backend 没有通用 HTTP Bean；用 MVC 已有的 Spring RestClient + JDK HTTP transport 访问 AI；远程下载单独配置安全客户端，不引入第二套供应商 SDK |
| 模型能力 | services/ai-service/src/main/java/com/lzj/rag/ai/api/AiController.java、ApiTypes.java；service/AiService.java；config/ModelRegistry.java、AiProperties.java | 已有 /ai/embed、/ai/rerank、/ai/chat、/ai/vision 及流式接口；复用其协议、路由、批处理与错误分类；缺 backend 客户端、不可变模型描述/修订号协议 |
| 模型降级 | services/ai-service/src/main/java/com/lzj/rag/ai/service/AiService.java：embeddingSpace + dimensions 过滤；resilience/RequestGate.java | 现有熔断/并发是进程内状态，不是任务持久化；KB 首版 embedding 显式固定 qwen-embed，不启用 fallback，禁止维度相同就混用空间 |
| 解析 | services/ai-service/src/main/java/com/lzj/rag/ai/util/TextUtilities.java 只有估算 tokens 和围栏处理 | 不是文件解析器，也不是真实模型 tokenizer；缺 Tika、Markdown AST、Excel/CSV、OCR；按阶段增加 |
| 后台任务 | 主代码无 @Scheduled、持久化任务、claim、租约或恢复扫描 | packages/java-starters/common/src/main/java/com/lzj/rag/framework/common/toolkit/ThreadUtil.java 只有线程工厂/休眠；测试中的 Executors 不构成生产执行器 |
| MQ/Outbox | 仓库无 Rabbit/Kafka/RocketMQ 接入或生产 Outbox | 首版使用 PostgreSQL 持久化任务；不为本项目额外部署 MQ；S3/后续 ES 使用 Outbox/补偿 |
| 缓存 | packages/java-starters/cache/src/main/java/com/lzj/rag/framework/starter/cache/DistributedCache.java、RedisDistributedCache.java、config/CacheAutoConfiguration.java | backend 未引用缓存 starter；可后续按需复用，不能依赖 Redis 锁替代数据库任务事实 |
| Web 契约 | packages/java-starters/convention/src/main/java/com/lzj/rag/framework/convention/result/Result.java、同包根下 page/PageResponse.java；packages/java-starters/web/src/main/java/com/lzj/rag/framework/starter/web/result/Results.java | 复用 code/message/data/requestId 和 current/size/total/records |
| 输入与错误 | services/backend/src/main/java/com/lzj/rag/backend/api/KnowledgeBaseErrors.java、StrictJsonConfiguration.java、RequestTracingConfiguration.java | 已有真实 HTTP 状态码、严格 JSON 类型和请求 ID；新增业务错误应扩展该层，不改变公共 starter 默认语义 |
| 搜索/回答编排 | AI 有模型调用，但仓库无搜索客户端、BM25 索引或带引用回答服务 | P8 才增加可关闭的 Elasticsearch BM25 + RRF/重排/回答；ES 不作为第二个向量后端 |
| 前端/Agent | web/.gitkeep；services/agent-service/.gitkeep；packages/python-common/pyproject.toml 与 src/rag_common/ | 没有 React 工程、组件库或 LangGraph 服务；Python 仅公共 FastAPI 契约与可选 Redis，不承接新业务后端 |
| 测试 | services/backend/src/test/java/com/lzj/rag/backend/KnowledgeBaseContract.java、ContainerPostgresTest.java、LocalPostgresTest.java、KnowledgeBaseFailureTest.java；security/KnowledgeBaseSecurityConfigurationTest.java | JUnit 5 + MockMvc + 真 PostgreSQL 已有；扩展行为测试，不用 H2 冒充 pgvector |
| 模型协议测试 | services/ai-service/src/test/java/com/lzj/rag/ai/AiServiceTest.java：本地 Reactor Netty 假供应商 | 复用现有方式覆盖模型元数据与 expectedConfigRevision；backend 用本地 HTTP stub 验证服务间契约，不强制再加 MockWebServer |
| 部署/验收 | services/backend/Dockerfile、Dockerfile.dockerignore；deploy/knowledge-base/compose.yml、.env.example；scripts/knowledge-base/acceptance.py；docs/knowledge-base/RUNBOOK.md | 已有元数据健康/CRUD 验收；尚无 S3、AI、解析任务、ES 联调环境及完整应用容器部署成功证据 |

### 1.1 现有功能复用结论

kb_base 是需求中的逻辑名称，物理上继续使用现有 kb_knowledge_base，不创建重复的 kb_base 表或第二组 CRUD。保留 /api/v1/knowledge-bases 路由、原有名称唯一范围、PUT 完整替换语义和 version。已有无文档知识库在新增模型字段时保持 UNBOUND，须显式绑定模型后才能入库；禁止在迁移中悄悄绑定默认供应商。

现有 DELETE 只写知识库墓碑；P1 一旦引入文档，就必须同时扩展删除协议：有存活文档返回 409/KB_NOT_EMPTY，直到 P5 提供显式 cascade 删除；不能沿用当前删除后留下文档可操作的行为。新表的任何入口都必须向上校验存活的知识库。

现有 AI 的 EmbedResult 只返回 embeddings/dimensions/model/usage/metrics，其中 model 是逻辑别名，未返回 provider、embeddingSpace 或 configRevision。不能凭 alias + 1024 就认定两次调用使用同一空间，这是 P2 前置差距。

## 2. 本项目技术映射与装配

| 职责 | 确定方案、理由与装配 | 必要依赖 |
|---|---|---|
| 关系库 | 原 backend datasource 的 PostgreSQL 17；MyBatis-Plus 管理实体，JdbcTemplate/JDBC 处理向量与任务 claim；短事务 | 已有 MyBatis-Plus starter、PostgreSQL JDBC、Flyway core + flyway-database-postgresql；不引入 JPA |
| 对象存储 | ObjectStorageClient + S3ObjectStorageClient；AWS SDK for Java v2 S3，支持 S3 兼容 endpoint/path-style；保存稳定 objectKey，由受权下载接口临时签名 | P1 增加 software.amazon.awssdk:bom、s3 及 apache-client；SDK API 总超时/单次尝试/HTTP 超时与重试全部配置 |
| 向量 | 唯一实现 PgvectorVectorStore；与关系记录同 PostgreSQL，JDBC 绑定向量参数，固定 vector(1024) + cosine；P2 写入、P4 开放精确检索，HNSW 是同后端的可选性能模式 | P2 安装/启用现有镜像的 vector 扩展；不引入 Milvus/Qdrant/ES dense_vector，不另建向量服务 |
| MIME/解析 | P1 Tika 检测 MIME；P2 UTF-8 TXT 与 CommonMark AST Markdown；P6 扩展 Tika 通用解析、POI 工作簿、Commons CSV；OCR/MinerU/VLM 按需单独开启 | org.apache.tika:tika-core；org.commonmark:commonmark；P6 按需 tika-parsers-standard-package、org.apache.poi:poi-ooxml、org.apache.commons:commons-csv |
| 模型 | backend 的 EmbeddingClient/RerankClient/LlmClient/VlmClient 适配现有 AI HTTP；供应商原生差异继续留在 ProviderClient | MVC 已带 Spring RestClient，JDK 17 HttpClient；不把 ai-service 可执行 JAR 作为 backend 依赖、不再包一套供应商 OkHttp 客户端 |
| 任务执行 | P2 同步内核也持久化 job/stage log；P3 通过数据库 claim + @Scheduled + 有界 ThreadPoolTaskExecutor 异步执行；任务不是内存 Future | 已有 Spring scheduling/transaction 能力；不引入 Quartz/MQ；执行器拒绝策略不吞任务、不在扫描线程执行重活 |
| 刷新调度 | P7 用 CronExpression.parse().next(ZonedDateTime) 计算 UTC next_run_at，数据库租约协调；定时触发只入库任务 | 已有 Spring Framework；无额外调度平台 |
| 混合/回答 | P8 Elasticsearch Java Client + BM25，默认关闭；开启时 Outbox 同步；检索证据先做权限和 generation 校验，再交给现有 AI 重排/生成 | co.elastic.clients:elasticsearch-java，只在 P8 锁定与实际 ES 服务版本匹配的客户端；不加 ES 向量检索 |

新增 SDK/解析依赖在各实施阶段固定具体版本并核对 Java 17/Boot BOM、漏洞与传递依赖；本轮不改 POM，也不声称尚未引入的依赖已经兼容。Tika 只负责检测/抽取，不能替代上传白名单、超时和解压资源限制。

### 2.1 模型与维度决定

首版唯一可绑定 profile：逻辑别名 qwen-embed → provider=bailian → 物理模型 text-embedding-v4 → 1024 维 → embeddingSpace=bailian-text-embedding-v4。这是现有 YAML 的配置值；供应商官方同步接口说明也支持该模型 1024 维、每批最多 10 条。真实账户、区域、权限和返回仍须联调核验，不能把配置或文档当作已成功调用的证据。[供应商协议](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)

修订分两层：AI 的 ai_config_revision 是 alias、provider kind/ID、物理模型版本名、endpoint/region 标识、dimensions、embeddingSpace、供应商输入指令与协议版本的非敏感快照 SHA-256；KB 的 embedding_config_revision 是 ai_config_revision + backend 输入归一化/适配器版本的 profile 快照 SHA-256。两者都不包含 API key，密钥轮换不改变空间修订。公共文档/向量存 KB profile 修订，调用 AI 的 expectedConfigRevision 则传 profile 中保存的 ai_config_revision，不能把两种 hash 混传。配置修订不能检测供应商在同一版本名下静默更换权重，生产应使用供应商可固定的版本策略。

P1 为绑定准备现有 AI 服务的 GET /ai/embedding-models/{alias}?dimensions=1024 和配置修订描述；P2 扩展 /ai/embed 的可选 expectedConfigRevision 与对应响应字段。处理请求时对同一不可变 Registry 快照校验，失配返回 409/MODEL_CONFIG_CHANGED，拒绝先查元数据后仍存在配置漂移的调用。KB 调用始终携带期望修订，核对实际 model/provider/space/revision、每条维度、数量、索引、float32 有限值与非零范数。老 AI 调用不传新增参数可保留兼容，KB 不得降级到不校验修订的旧协议。

知识库模型绑定后不可原地更换模型、provider、dimension、revision 或 namespace。新 profile 需要新知识库及显式重建/迁移流程；恢复旧配置或重新部署版本化 alias 是旧知识库恢复调用的途径。用户不能通过重处理请求覆盖模型身份。首版不开放 silicon-embed，即使它同样是 1024 维。

pgvector 的 vector HNSW 索引支持上限为 2000 维，因此 1024 可使用 vector_cosine_ops；不误把 text-embedding-v4 的 2048 维配置塞入当前列或索引。[pgvector 索引说明](https://github.com/pgvector/pgvector#hnsw)

### 2.2 配置清单（已存在与拟新增分开）

| 范围 | 配置与首版设计默认值 | 状态/约束 |
|---|---|---|
| 当前 DB/认证 | KB_DB_URL、KB_DB_USERNAME、KB_DB_PASSWORD、KB_DB_POOL_SIZE=10、KB_DB_CONNECTION_TIMEOUT_MS=5000、KB_DB_STATEMENT_TIMEOUT_MS=10000；KB_USERNAME/PASSWORD/TENANT_ID/USER_ID | 已存在，配置中心/环境注入；租户/操作人不能由正文或任意 Header 指定 |
| 当前 AI | AI_PORT、AI_ENABLED、BAILIAN_API_KEY、BAILIAN_BASE_URL、BAILIAN_EMBED_MODEL、ai.models.qwen-embed.*、ai.embedding.fallbacks=[] | 已存在；密钥只进入 AI 服务；当前 AI 无服务间认证过滤器，只能置于受控内网 |
| 服务间 HTTP | kb.ai.base-url=http://ai-service:8081；connect-timeout=5s、request-timeout=100s、max-response-bytes=4MiB、concurrency=4、embedding-batch-size=10 | 拟新增；100s 要大于 AI 当前总期限 90s；有界 permit。backend 不转发终端用户 Basic 凭据；生产通过内网 ACL/网关 mTLS，若设施需 token 则配置中心注入 |
| 模型 profile | kb.embedding.profile-id=qwen-embed-1024；model-id=qwen-embed、provider=bailian、dimension=1024、expected-revision | 拟新增服务端允许列表；revision 来自元数据协议，不是用户自由填写；查询和入库同一 profile |
| S3 | kb.storage.endpoint、region、bucket、prefix=knowledge-base/、path-style-access=false；AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY 或工作负载角色；可选 AWS_SESSION_TOKEN | 拟新增；endpoint/region/bucket 无臆造生产值；永久表不存签名 URL；禁止公网 bucket |
| S3 边界 | connect-timeout=5s、socket-timeout=30s、attempt-timeout=40s、api-timeout=90s、max-attempts=2、max-connections=8、signed-url-ttl=5m | 拟新增；总期限包含 SDK 重试，不能叠无限重试 |
| 上传/解析 | max-upload-bytes=20MiB、max-text-chars=2,000,000、max-chunks=5000、parse-timeout=60s、max-expanded-bytes=100MiB、max-archive-entries=1000 | 拟新增；P1 白名单 TXT/MD；后续格式按阶段添加；临时目录配额与资源回收，超限失败不能静默截断入库 |
| Servlet上传边界 | spring.servlet.multipart.max-file-size=20MB、max-request-size=21MB、file-size-threshold=0，location为受控临时目录；metadata JSON<=64KiB、单请求只允许一个file部分 | P1拟新增；框架总包体限制与业务字节限制对齐，不能沿用默认1MB文件限制导致接口不可用 |
| 切块 | chunk-max-chars=1200、overlap-chars=120、embedding-max-utf8-bytes=12000、token-policy=estimate-v1 | 拟新增，写入 ingestion_spec；字符按 Unicode code point 计数，需保证边界不切断 surrogate；不是词法 tokenizer |
| 文本预算 | 单条 embedding_text UTF-8 字节保守不超过 12000；实际 token_count 可 null，估算单独标记 tokenCountEstimated=true | 拟新增；现有估算工具不能证明满足供应商 token 上限。明确上游上下文拒绝后按持久化的新 revision 缩小切块重建，有限一次自动调整；禁止无限重试或丢尾 |
| 任务执行 | poll-interval=2s、workers=4、queue-capacity=0、claim-batch-size<=空闲 permit、lease=180s、heartbeat=30s、max-attempts=3、job-timeout=15m | 拟新增；DB 时钟；所有外部阶段有更短 deadline；拒绝提交立即归还 claim 或待租约恢复，不丢任务 |
| 向量查询 | kb.vector.backend=pgvector（仅此枚举）、dimensions=1024、search-mode=exact、top-k=10/max50、candidates=50/max200、query-timeout=5s | 拟新增；dimension 与 DDL/运行 profile 三方一致；可选 HNSW ef_search/max_scan_tuples 受限配置，召回不足可走同后端精确过滤查询 |
| URL/刷新 | kb.import.enabled=false、allowed-hosts=[]、download-timeout=60s、max-bytes=20MiB、max-redirects=3；refresh.poll=10s、min-interval=60s、timezone=Asia/Shanghai | P7 拟新增；先有明确出站域名策略才启用；六段 Spring cron，使用 IANA ZoneId |
| 后续搜索/回答 | kb.search.keyword.enabled=false、endpoint/credentials/index-prefix；kb.answer.enabled=false、context-budget、rerank-enabled=false | P8 拟新增；关闭关键词服务不影响 pgvector；不开隐式降级，配置要求混合但服务不可用则错误返回 |

配置分成 ObjectStorageConfiguration、AiClientConfiguration、IngestionExecutorConfiguration、RefreshConfiguration 等 Bean，由 backend 装配；调度线程只 claim/入队，不承担解析。上述类尚不存在。超时预算以 job deadline 为总上界，AI 的候选重试与任务 attempt 是不同层级，均需计入耗时/成本。

HTTP响应大小限制必须在实际流读取层执行；不能只定义max-response-bytes却对RestClient无界readTree。上传首版仅严格UTF-8（可含UTF-8 BOM）的TXT/MD；.md检测为text/plain时可按服务端白名单走Markdown AST，二进制伪装文件仍按内容MIME拒绝。

## 3. 核心数据设计

### 3.1 通用规范与关联

现有 kb_knowledge_base = 逻辑 kb_base；新增实体仍放入 backend。UUID 继续复用 UuidTypeHandler；时间为 timestamptz/OffsetDateTime，存储 UTC、精度微秒；业务 revision/generation/version 为正 bigint，计数非负；JSON 使用 jsonb 并由 Java TypeHandler 显式映射，不能依赖任意 Object 的隐式转换。

归属字段为 tenant_id/owner_id varchar(128)，操作人 created_by/updated_by 来自可信 principal，异步任务持久化 requested_by 并以服务身份执行，不能从 worker 线程局部“当前用户”推断。子表冗余归属用于权限过滤，并用复合唯一键/外键强制一致：

- base UNIQUE(id, tenant_id, owner_id)；
- document FK(kb_id, tenant_id, owner_id) → base，UNIQUE(id, kb_id, tenant_id, owner_id)；
- generation/chunk/job/schedule 经 document 的复合键关联；vector 必须同时关联 chunk 的 id/kb/document/generation/归属，禁止同租户跨库串接；
- job/log、refresh 由父记录继承权限；索引 namespace、对象 key 和模型 alias 本身都不是授权凭据。
- 保留父墓碑直至受控清理完毕；默认外键 RESTRICT，避免级联物理删除掩盖未完成补偿。

V2可先创建document的可空active_generation/latest_job_id占位列，指向generation/job的外键必须等V3相应父表建立后再添加；不得在V2引用尚不存在的表。向量侧增加与base的(kb_id,namespace,profileRevision,tenant,owner)一致性约束、与profile的(modelId,provider,dimension,revision)复合键约束；UNBOUND库无法形成有效外键。JDBC可用CAST(? AS vector(1024))绑定已校验序列，维度不是请求中的SQL片段。

version 是管理写入 CAS；config_revision 是源对象/URL或 ingestion_spec 的目标配置版本；manual_revision 是人工 Chunk 维护版本；generation 是一次不可变内容发布的序号，三者不能互换。active_generation 可为空；next_generation 单调递增，失败或 304 消耗的号码不复用。配置变化时旧 active_generation 可继续提供证据，但 API 要明确 activeConfigRevision 与目标 configRevision 的差异。

### 3.2 kb_base（现有物理表 kb_knowledge_base 的增量字段）

| 字段 | 类型/约束与语义 |
|---|---|
| id、name、description、version | 复用 V1；name 最多 128 UTF-16 单元，经 strip 校验；不另建重名实体 |
| embedding_model_id | varchar(128)，服务端 AI 逻辑 alias，首版仅 qwen-embed；UNBOUND 时可 null |
| embedding_provider | varchar(64)，首版 bailian；与 profile 快照一致，不由公共请求自由指定 |
| embedding_dimension | integer，绑定后 CHECK = 1024；与 vector 列维度一致 |
| embedding_config_revision | char(64)，不可变 profile SHA-256；FK 到 kb_embedding_profile |
| vector_namespace | varchar(160)，服务端生成，如 kb_<UUID>；不可变、不可由调用者指定 |
| binding_status | UNBOUND/BOUND；新增字段必须“全部为空且 UNBOUND”或“全部非空且 BOUND”，用 CHECK 保证 |
| tenant_id、owner_id | 沿用 V1；存活名称唯一仍为 (tenant_id, owner_id, lower(name)) WHERE deleted_at IS NULL |
| created_by、updated_by | varchar(128)；新增时现有行按 owner_id 确定性回填，不伪造历史操作人 |
| created_at、updated_at、deleted_at | 沿用 V1；namespace 唯一为 (tenant_id, owner_id, vector_namespace)，包括墓碑，不重用旧 namespace |

kb_embedding_profile 是辅助不可变表：revision 主键，ai_config_revision、model_id/provider/model_name/endpoint_region/dimension/embedding_space/normalization_version/protocol_version/snapshot_json/verified_at/created_at。snapshot_json 无密钥；verified_at 仅在真实探测成功后写入，不以假供应商测试填充生产验证标记。现有空库可通过拟新增 bind 接口设置一次；绑定后的变更返回 409/KB_EMBEDDING_LOCKED。

### 3.3 kb_document

| 字段 | 类型/约束与语义 |
|---|---|
| id、kb_id | UUID；引用存活且属于本人的 base；同库允许同名文件，以 id 区分 |
| name | varchar(255)，去除路径段/控制字符，展示用途，不能充当文件路径 |
| source_type、source_url | UPLOAD/URL；source_url varchar(2048)，仅 URL 类型需要；拒绝用户信息和凭据 URL，禁止文件协议 |
| object_key | varchar(1024)，当前“目标源文件”稳定 key；生成于服务端归属前缀；PENDING_UPLOAD 可预留，STORED 时必须有效 |
| mime_type、file_size | varchar(128)、bigint>=0；MIME 由内容检测，不信任扩展名与 Content-Type |
| content_hash | char(64)，原始字节 SHA-256，不以 S3 ETag 代替；跨用户不做存在性泄露式去重 |
| enabled | boolean 默认 true；只控制文档整体检索门控，不级联覆盖 Chunk.enabled |
| storage_status | PENDING_UPLOAD/PENDING_FETCH/STORED/FAILED/DELETED，独立于解析状态；URL首次等待下载为PENDING_FETCH，此时mime_type/file_size/content_hash可null；STORED时三者必须确定且object_key有效 |
| process_status | NOT_STARTED/QUEUED/PROCESSING/READY/FAILED/WAITING_REVIEW/CANCELLED/DELETING/DELETED；表示最新目标处理，不能代替 active_generation |
| ingestion_spec | jsonb NOT NULL，schemaVersion + parser/splitter/embeddingText/tokenPolicy；存规范化的服务端解析结果及默认值，禁止任意类名或可执行模板 |
| config_revision | bigint，初始 1；源对象、源 URL、解析/切块/拼接策略改变时 +1，改名/文档启停只增加 version |
| manual_revision | bigint 默认 0，人工启停/编辑/删除 Chunk 时 +1，用于阻止旧入库任务覆盖维护结果 |
| active_generation、active_config_revision | bigint nullable，仅成功完整发布后切换；无可用内容时为空 |
| next_generation、chunk_count | bigint 默认 1/0；后者为 active generation 中未删除 Chunk 总数，包含人工禁用者；可检索数量另行计算，不用 chunk_count 推断权限 |
| version、latest_job_id、last_error_code | version 用于API管理字段CAS，后台进度/计数不自行增加；Chunk维护另同时校验generation；latest_job_id 引用最近目标任务；last_error_code 是安全摘要，详细历史在 job/log |
| created_at/updated_at/deleted_at、created_by/updated_by、tenant_id/owner_id | 通用审计与归属；索引 (kb_id, created_at DESC, id DESC) WHERE deleted_at IS NULL |

文件替换必须创建新的不可变 objectKey，旧 key 在 generation 中保留引用，不能覆盖旧对象导致旧证据页码对应错误原文。content_hash 描述目标源文件；实际可检索证据的原始文件来自 active generation 的快照。

### 3.4 kb_document_generation（发布账本，辅助表）

主键 (document_id, generation)，同时记录 kb/归属、document_config_revision、document_manual_revision、embedding_config_revision、object_key/content_hash、ingestion_spec 快照、chunk_count、vector_count、state、job_id、created_at/published_at。state=STAGING/PREPARED/PUBLISHED/RETIRED/ABANDONED；只有完整预备后才可发布。document.active_generation 使用可延迟复合外键指向本表，不能指向其他文档。

P2 完整内核即要求 vector_state=COMPLETE 才能发布；生成过程可有 NOT_BUILT/BUILDING 暂存态，但没有向量的半成品不能标为 READY。P4 开放检索 HTTP，届时 searchable 还要求检索功能启用。临时开发预览不能切换 active_generation。

### 3.5 kb_chunk

| 字段 | 类型/约束与语义 |
|---|---|
| id、kb_id、document_id、generation | UUID/UUID/UUID/bigint；UNIQUE(document_id,generation,chunk_index)；组合 FK 到 generation/归属 |
| chunk_index | integer>=0，在 generation 内稳定；不是跨代身份 |
| logical_chunk_key | char(64)，原始来源 anchor + 原始内容 hash + 重复段 occurrence 的版本化指纹，用于保留人工策略；不能只按 index 或内容 hash 匹配 |
| content、embedding_text | text；前者是可展示/引用正文，后者按 ingestion_spec 拼接原文标题/章节等模型输入；标题来自源文档/冻结的源元数据，不使用会被重命名的展示name；修改 content 必须重算后者与向量 |
| content_hash | char(64)，规范化 content 的 SHA-256；另存 original_content_hash 作为人工匹配依据 |
| char_count、token_count | integer>=0、integer nullable；char_count 按 Unicode code point；未知真实 token 数不伪填估计，估算另放metadata_json.tokenEstimate={count,method,estimated:true} |
| enabled、edited | boolean 默认 true/false；enabled 是独立的人工意图；edited 表示展示内容偏离解析原文 |
| metadata_json | jsonb，结构化 chapterPath、pageStart/pageEnd、sheetName、rowStart/rowEnd、cellRange、csvRecordRange、sourceAnchor、parserVersion；无来源位置时 null/省略，不能编造页码 |
| created_at、updated_at、deleted_at | 内容发布后不原地改正文；人工 enabled/删除门控可短事务更新，受 document.version/manual_revision 保护 |
| tenant_id、owner_id | 冗余归属 + 复合 FK；索引 (document_id,generation,chunk_index)，并支持 current generation 管理查询 |

P5 新增 kb_chunk_override：主键 (document_id, logical_chunk_key)，保存 enabled_override、edited_content、deleted、version、updated_by/at；这是跨代人工状态账本。重新启用文档只写 document.enabled，不更新 Chunk 或 override。新代精确匹配到的策略必须继承；存在无法定位的已编辑/禁用/删除策略时进入 WAITING_REVIEW，不默默把它们恢复。显式人工重置策略必须由 owner 通过专门 API、CAS 和指定 key 执行。

### 3.6 kb_vector（唯一物理向量实现）

| 字段 | 类型/约束与语义 |
|---|---|
| chunk_id | UUID 主键；Chunk id 每代唯一，保证一条当前模型向量；FK 包含下列 kb/document/generation/归属 |
| kb_id、document_id、generation | 与 chunk 完全匹配，供隔离过滤和代发布 |
| embedding_model_id、embedding_provider、embedding_config_revision、embedding_dimension | 与 base/profile 快照一致，维度 CHECK = 1024；不能只核对维度 |
| vector_namespace | 与 base 的服务端 namespace 一致；复合 FK/受约束写入，客户端不能提交 |
| embedding | vector(1024) NOT NULL；应用校验有限 float32、1024 长度及非零范数，禁止 padding/truncate；用已校验参数绑定，不能拼接原始向量 SQL |
| metadata_json、tenant_id/owner_id、created_at | 仅最少的来源定位副本；以 Chunk/generation 来源为权威，不复制临时签名 URL |

P2 的向量列迁移与 P4 的可选索引设计示意（本轮未创建/执行 SQL）：

~~~sql
CREATE EXTENSION IF NOT EXISTS vector;
-- kb_vector 的 embedding 列固定为 vector(1024)，不是无维度 vector。
-- 完整 DDL 还须包含上述复合外键、CHECK 和普通归属索引。
CREATE INDEX kb_vector_scope_idx
  ON kb_vector (tenant_id, owner_id, kb_id, document_id, generation);
-- 初期 exact 默认不需要此索引；启用 HNSW 的单独受控迁移：
CREATE INDEX kb_vector_embedding_hnsw_idx
  ON kb_vector USING hnsw (embedding vector_cosine_ops);
~~~

查询对租户、owner、授权 KB、namespace、profile、文档/Chunk enabled、deleted_at 和 active_generation 同时过滤，再排序/limit。精确检索与 HNSW 都在 PgvectorVectorStore 内，不是两种后端。HNSW 过滤可能造成候选不足，受限迭代扫描或同库精确回退须做召回验收；不得先全局 topK 再由 Java 删除越权结果。查询无结果时返回 200 空集合，不是“造证据”。

search-mode=exact必须执行过滤后物化候选集再排序的精确SQL（如MATERIALIZED CTE），不能仅设置一个标志却任由查询规划器使用已有HNSW近似索引。query阶段的最终回查还需覆盖Embedding/重排耗时期间的删除或代切换；保证最终校验时点可见，不承诺响应发送后的永久授权。

### 3.7 kb_ingestion_job

| 字段 | 类型/约束与语义 |
|---|---|
| id、request_id、requested_by | UUID；安全请求 ID varchar(128)；可信操作人。request_id 只用于追踪，不是幂等键、无唯一约束 |
| document_id、kb_id、tenant_id/owner_id | 文档类指向固定归属的 document；仅 DELETE_BASE/库级 GC 允许 document_id 为空；普通任务查询校验存活父，删除/清理任务按持久化归属快照查询 |
| document_config_revision、document_manual_revision、embedding_config_revision | 提交时的输入快照，用于 fencing；不可在 attempt 内悄悄改输入 |
| target_generation | 内容发布任务受 document 行锁分配，同文档单调不复用；REFRESH源检查和清理类可空；变化的源由不可变输入INGEST子任务分配非空target |
| job_type、input_json | INGEST/REPROCESS/EDIT_CHUNKS/REFRESH/DELETE_DOCUMENT/DELETE_BASE/GC；必要输入、来源对象快照、编辑补丁/触发时间持久化，不仅存内存闭包 |
| status、stage | status=PENDING/RUNNING/RETRY_WAIT/WAITING_REVIEW/SUCCEEDED/FAILED/CANCELLED/SUPERSEDED；stage=FETCH/PARSE/SPLIT/EMBED/PERSIST/PUBLISH/CLEANUP |
| attempts、max_attempts、next_run_at | attempts 初始 0，每次 claim +1；max_attempts 从服务端配置快照，默认 3；重试待执行时间用 DB 时钟，终态为空 |
| lease_owner、lease_until、claim_token | owner 为实例/worker 唯一 ID，until=timestamptz，token=bigint 初始 0；每次 claim +1，续租/写进度/发布/失败记录都匹配 token 和有效租约 |
| started_at、completed_at、created_at、updated_at | started_at 首次开始，completed_at 仅终态；每次 attempt 的时间由 stage log 保留 |
| error_code、error_message | varchar(64)/varchar(2048)，白名单脱敏、限长，不存原始响应/密钥/完整源 URL 查询/正文；可增加 retry_after 秒数 |
| idempotency_record_id、parent_job_id、scheduled_for、result_json | 关联操作去重、手工重试/刷新派生子任务与触发时间；result_json记录候选源validator/hash/key、childJobId或UNCHANGED；不把历史失败记录改成新任务 |

约束：UNIQUE(document_id,target_generation) 对有 target 的处理任务；同文档最多一个非终态内容变更任务（部分唯一索引覆盖 PENDING/RUNNING/RETRY_WAIT/WAITING_REVIEW）。纯清理任务走独立去重键。替换目标时先在同一事务将旧任务 SUPERSEDED、递增其 token，再插入新任务。due 索引覆盖 (status,next_run_at,id)，另有租约过期索引。已达 max_attempts 的过期 RUNNING 必须转 FAILED，不能永久停留 RUNNING。

### 3.8 kb_ingestion_stage_log

id bigint/UUID 主键；job_id、attempt、stage、sequence；started_at、ended_at、duration_ms；status=STARTED/SUCCEEDED/FAILED/ABANDONED/SKIPPED；input_count/input_unit、output_count/output_unit（各自为bytes/blocks/chunks/vectors）；error_code、error_message（最多 1024）、request_id。UNIQUE(job_id,attempt,stage,sequence)。

stage 开始/结束用短事务持久化，duration_ms 来自该 attempt 的实际 elapsed，并保留时间戳；不同单位不直接相减。崩溃恢复将旧 STARTED 标为 ABANDONED，而不是伪造成功；旧 worker 不能覆盖新 token 的日志终态。查询按父任务归属；不把解析原文或模型 prompt 写入阶段日志。

### 3.9 kb_refresh_schedule（P7 才迁移）

| 字段 | 类型/约束与语义 |
|---|---|
| document_id、kb_id、tenant_id/owner_id | document_id 主键，一份 URL 文档最多一个计划；复合 FK。上传文档不允许启用刷新 |
| cron_expression、timezone、enabled | varchar(128)/varchar(64)/boolean；六段 Spring cron + IANA ZoneId，默认 Asia/Shanghai，创建时验证未来可执行时间及最小间隔 |
| next_run_at、last_run_at | timestamptz；按 ZonedDateTime 计算后存 UTC；enabled=false 时 next_run_at 为空 |
| last_etag、last_modified、last_content_hash | varchar(1024)、HTTP-date validator varchar(128)、char(64)；只代表最近成功接受/发布的源版本；ETag 不等于内容 hash |
| last_status、consecutive_failures | IDLE/QUEUED/RUNNING/UNCHANGED/SUCCEEDED/FAILED/PAUSED；失败次数非负，成功/未变更归零 |
| pending_refresh | boolean默认false；忙文档触发时持久化一次待刷新，不依赖内存标记；闲置后消费一次 |
| lease_owner、lease_until、claim_token | 短调度租约；只协调抢占与持久化 REFRESH job，不覆盖整个下载；下载任务另有 job 租约 |
| version、created_at、updated_at、updated_by | CAS 管理；改变计划或禁用/删除文档时递增 token，使陈旧调度器不能继续入队 |

刷新入队与 next_run_at 推进在同一短事务，任务带 scheduled_for，UNIQUE(document_id,scheduled_for,job_type) 做去重；忙文档合并一次待刷新标记，不能永久漏掉后续变化。作业运行记录在 job/log，不用 schedule.last_status 代替历史。

### 3.10 必要辅助账本

- kb_idempotency：tenant/owner/operation/resource_scope/key 唯一；payload_hash、reservation 状态、resource_id/job_id、原始响应状态/安全快照、created_at/expires_at；键默认保留至少 24h。文件请求指纹使用完整字节 SHA-256 + 规范化表单参数，不能只比文件名。
- kb_outbox：id、归属、aggregate_id/revision、event_type、payload_json、status、attempts、next_run_at、lease/token、last_error、created_at/completed_at；(aggregate,event_type,revision) 去重。P1 用于 S3 PUT 意图确认/孤儿补偿，P3 接入统一有界派发，P8 扩展 ES 事件；不要与数据库事务混淆。
- 不新增全套用户/租户/角色表，外部存储、队列和搜索均不作为归属的权威来源。

## 4. 状态机、事务与恢复规则

### 4.1 上传和同步内核（P1/P2）

上传请求在受控临时空间流式收取、计算 hash/MIME/大小；短事务校验 owner/base、预留幂等键、document=PENDING_UPLOAD 和固定 objectKey、写 S3 PUT 意图。事务外写对象；成功后短事务验证意图并置 STORED/NOT_STARTED，P2 才允许接着创建入库任务。对象写成功而第二事务失败时由已持久化意图 HEAD/校验 metadata 中的 SHA-256 后恢复确认或清理；不能把 S3 PUT 放进数据库事务假称原子。

P1 不建设异步执行平台：提供受控人工 reconcile runner（持久化每次补偿结果），临时对象 TTL 与有引用对象严格区分。P3 才自动扫描相同 Outbox，复用同一补偿逻辑。客户端断开、重启、上传失败都能通过预留记录定位，未确认文件不能进入解析。

P2 的 IngestionKernel 依次 FETCH/PARSE/SPLIT/EMBED/PERSIST/PUBLISH，用同一持久化 job 和 stage log，覆盖完整向量写入与发布。同步内核先由集成测试/受控验收 harness 调用，不新增公开同步切换参数或生产同步 HTTP 入口；P3 才开放统一返回 202 的公共任务提交。同步 deadline 默认 120s，超时留下 FAILED/可恢复状态，重启不得出现“内存丢失但文档永久处理中”。P3 的长作业可按 15m 总预算执行，但外部单次调用仍受更短期限约束。

### 4.2 异步 claim 与 fencing（P3）

1. 先取得本地空闲 permit；短事务从 due PENDING/RETRY_WAIT 或可恢复租约选取 FOR UPDATE SKIP LOCKED，原子递增 attempts/claim_token、设置 RUNNING/lease。可跳过被锁行适用于此队列式扫描，不用于正常分页一致性。[PostgreSQL 说明](https://www.postgresql.org/docs/17/sql-select.html)
2. 提交后交给有界执行器；拒绝提交则 CAS 释放为可重试状态，permit 归还。定时器/内存队列不是任务事实来源。
3. 网络下载、解析、模型调用都在事务外，有阶段 deadline；独立心跳只更新当前 owner+token+有效租约，续租失败即停止后续持久化并尽力取消外部调用。
4. 写每批 STAGING Chunk/vector 前短事务锁 job 并检查当前 token/lease/目标快照；旧 worker 无法在新 attempt 取代后覆盖同 generation 的暂存数据。重试先受 token 保护重置未完成 staging 或校验 hash 后续做；不能靠幂等 upsert 掩盖陈旧写。
5. 最终发布按 base → document → schedule（涉及刷新时）→ job 的固定锁顺序，确认父未删除、job 仍最新、config/manual/profile 修订相符、租约有效、预备数量与有效向量齐全，在一个 DB 事务内切换 active_generation、计数、状态和 job=SUCCEEDED，并确认刷新validator/写后续Outbox。
6. 失败：网络/429/5xx/暂时存储或 DB 错误采用有上限退避与 jitter；非法文件、不可支持格式、模型身份/维度不匹配不盲目重试。lease 过期恢复旧 stage 日志、清除不可发布数据，attempt 用尽进入 FAILED；人工重试新建 job/新 generation 并关联 parent_job_id。
7. doc 删除、取消、配置或人工维护修改递增相应 revision/token；旧任务可遗留暂存记录，但绝不能发布。旧 generation 在保留期后用持久化 GC 清理，不能误删 active generation。

### 4.3 检索可见性与人工维护

逻辑可检索 = base 未删除且 BOUND AND document 未删除且 enabled AND active_generation 存在且 vector_state=COMPLETE AND chunk 属于 active generation 且 enabled 且未删除 AND vector profile/namespace/归属一致。process_status=FAILED 或 PROCESSING 不必隐藏旧有效代；首次失败 active_generation 为空则不可检索。显式禁用立即隐藏，重启/重试不恢复人工禁用。

content/embedding_text 的人工改写在 P5 以新 generation 全量发布；未改 Chunk 的向量可在确认输入/profile hash 完全相同时复制到新 id，否则重算。旧正文与新向量不能同时可见。人工启停/删除还写 override、manual_revision；若存在在途旧快照任务，原子标记 SUPERSEDED，并按最新配置/人工 revision 创建替代任务，防止维护动作被丢弃。无法对齐的人工策略 WAITING_REVIEW，旧 active generation 保留且其禁用仍生效。

文档或知识库删除先事务内设墓碑/关闭刷新/使任务失效，查询立即不可见；对象删除与后续 ES 删除靠 Outbox 幂等重试。外部删除失败不撤销墓碑，不报告“所有物理数据已删除”。GET 删除任务可按清理资源的归属快照读取，不重新要求已删除父为存活状态；普通读写仍拒绝墓碑父。

### 4.4 远程导入与刷新（P7）

仅服务端允许的 HTTP(S) 域名，默认关闭且允许列表为空。禁止 URL userinfo/凭据、file/ftp/gopher，逐跳检查重定向、DNS 的所有 A/AAAA 与实际连接目标；拒绝回环、私网、链路本地、保留地址和云元数据。JDK 客户端不能仅靠“先解析再请求”解决 DNS 重绑定，首版通过受控出站代理在连接时执行 IP/DNS 策略，并以网络 ACL 兜底；未配置该能力时保持 URL 导入关闭。测试专用本地 stub 例外只能在测试配置启用。

下载大小、总期限、重定向次数、解压限制与上传一致；不转发终端凭据，不自动抓取 Markdown/HTML 外链或执行 JS。远程原文件也写不可变 objectKey 后进入相同内核。

调度租约先短事务抢占并提交，后续按 document → schedule → job 锁顺序核对归属/删除/token、创建 REFRESH job、推进 next_run_at；避免持 schedule 锁做网络。使用数据库 UTC 当前时间 + 配置时区的 CronExpression.next，返回 null 拒绝计划；停机漏触发合并为一次，不补发海量历史任务。夏令时按 ZonedDateTime 的 next 结果、UTC scheduled_for 去重，重复本地钟点可对应两个不同合法瞬间，测试必须覆盖并记录这一语义。[CronExpression](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/support/CronExpression.html)

REFRESH（含首次URL导入的fetch）只承担源检查与候选对象持久化，不在attempt内悄悄改document_config_revision后继续解析。下载新版本后，短事务核对token与当前目标，更新document的object/hash/config_revision，将REFRESH置SUCCEEDED并在result_json写INGESTION_ENQUEUED/childJobId，同时创建具有固定新源输入的INGEST子任务；document.latest_job_id指向子任务。REFRESH完成不代表证据已发布，schedule状态仍QUEUED，直到子任务成功发布才确认validator/最终SUCCEEDED。子任务失败保留候选对象以便重试，不能重新下载后混进同一generation。

last_etag/last_modified只对最近成功接受的源版本有效。HTTP304或200且SHA-256相同只在已有完整有效代、配置未变化且没有失败待处理源版本时跳过重建；若配置变化/存在失败候选，则用已存源创建新INGEST子任务。失败新内容不更新“已发布”validators，避免下一次304把失败版本永久跳过。刷新失败保留旧证据，错误与连续失败次数持久化。计划禁用则next_run_at清空；文档禁用时暂停自动触发，计划意图不丢失，重新启用从当前时刻重算next，不回放历史触发，也不恢复人工禁用Chunk。

## 5. 依赖有序的实施阶段

以下是未来工作，不在本轮执行。所有迁移版本以当前唯一 V1 为基线；实施时若发现新增迁移，先重新编号，禁止覆盖既有文件。每个阶段完成后停止、更新三份文档和对应 RUNBOOK/OpenAPI，再由下一轮继续。

### P1 基础管理与上传（复用已有 CRUD）

文件：扩展现有 services/backend/src/main/java/com/lzj/rag/backend/knowledgebase/KnowledgeBase.java、KnowledgeBaseTypes.java、KnowledgeBaseService.java、KnowledgeBaseController.java；拟新增 services/backend/src/main/java/com/lzj/rag/backend/storage/ObjectStorageClient.java、S3ObjectStorageClient.java、StorageRecoveryService.java；document/DocumentController.java、DocumentService.java、DocumentMapper.java、Document.java；idempotency/IdempotencyService.java；outbox/OutboxMapper.java；config/ObjectStorageConfiguration.java；现有 backend 包名前缀均为 com.lzj.rag.backend。

P1同时拟新增services/backend/src/main/java/com/lzj/rag/backend/persistence/JsonbTypeHandler.java、model/AiModelMetadataClient.java、config/AiClientConfiguration.java；JSONB可用经过Jackson校验的JSON字符串以JDBC Types.OTHER绑定，避免直接依赖运行期驱动类型做隐式转换。P2复用元数据客户端和HTTP配置，不重复实现。

迁移：拟新增 services/backend/src/main/resources/db/migration/V2__documents_upload_and_model_binding.sql，扩展现有 base、创建 profile/document/idempotency/outbox；V1 不改。AI 元数据增量放 services/ai-service/src/main/java/com/lzj/rag/ai/api/EmbeddingModelController.java、config/EmbeddingProfileDescriptor.java，并扩展现有 ModelRegistry.java/ApiTypes.java。现有后端与 AI application.yml/POM 只按本阶段增加必要依赖/配置。

步骤：1）安全回填审计、旧库 UNBOUND；2）可信 profile 元数据与一次性 bind；3）实现存储流式 put/get/head/delete/presign 接口；4）TXT/MD 上传 MIME/长度/哈希与幂等；5）双事务上传确认和持久化补偿；6）文档查询/下载授权，删除非空库先拒绝；7）维护实际 OpenAPI 与验收脚本。

依赖：S3 endpoint/bucket/凭据可暂用测试容器；AI 元数据可用本地 stub，真实 profile 仍待核验。P1 不启动自动任务扫描。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/storage/S3ObjectStorageContractTest.java、document/DocumentUploadTest.java、idempotency/IdempotencyTest.java，以及 AI 元数据协议测试。覆盖上传成功/超限/伪 MIME/跨租户/同 key 不同 payload、对象成功 DB 失败、DB 预留后崩溃、补偿重跑、无权签名、旧 CRUD 兼容；真 S3 兼容环境缺失时明确未联调。

### P2 同步入库内核

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/ingestion/IngestionKernel.java、IngestionContext.java、IngestionJobService.java；parser/DocumentParser.java、TextDocumentParser.java、MarkdownDocumentParser.java；chunk/ChunkSplitter.java、ChunkMapper.java；model/EmbeddingClient.java、AiEmbeddingClient.java、AiModelMetadataClient.java；vector/VectorStore.java、PgvectorVectorStore.java；generation/GenerationPublisher.java；现有 AI ApiTypes.java/AiService.java/ModelRegistry.java 扩展 expectedConfigRevision 和返回身份。

迁移：拟新增 services/backend/src/main/resources/db/migration/V3__ingestion_generations_and_pgvector.sql：generation、chunk、vector(1024)、ingestion_job、stage_log、复合外键及 job 最小租约字段，启用 vector 扩展。迁移账户需扩展权限，应用账号仅 DML。

步骤：1）持久化配置与输入快照；2）实现统一 FETCH→PARSE→SPLIT→EMBED→PERSIST→PUBLISH；3）按 10 条/受限字节拆批，验证模型身份与向量；4）暂存批写+同库原子 active_generation 切换；5）同步 harness 失败/超时落库，旧 active 继续有效；6）开放授权后的 job/stage 只读查询。不是先 @Async 再补数据表。

依赖：P1 已确认文件及 profile；沿用 AI 原生映射，不复制供应商协议；TXT/MD 可完成端到端。无真实模型密钥时用生产适配器对本地 HTTP stub 测试，不能把假向量写入生产或标记真实联调完成。

验收：拟新增 ingestion/IngestionKernelTest.java、generation/GenerationPublicationTest.java、model/AiEmbeddingClientTest.java、vector/PgvectorPersistenceTest.java（均在 services/backend/src/test/java/com/lzj/rag/backend/）。真 pgvector 检查维度拒绝、NaN/Infinity/零向量、批次乱序/缺失、旧配置回包、半批失败不发布、旧代保留、Markdown AST 来源、阶段日志与错误脱敏。更新 services/ai-service/src/test/java/com/lzj/rag/ai/AiServiceTest.java 覆盖协议兼容与配置漂移。

### P3 异步可靠性

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/ingestion/IngestionJobController.java、JobClaimer.java、IngestionWorker.java、LeaseHeartbeat.java、JobRecoveryScanner.java；config/IngestionExecutorConfiguration.java；outbox/OutboxDispatcher.java、OutboxWorker.java；迁移 services/backend/src/main/resources/db/migration/V4__job_claim_and_outbox_indexes.sql。

步骤：1）公共提交 202 + Idempotency-Key；2）扫描/claim/有界 permit；3）续租、claim_token、防陈旧批写与发布；4）有上限重试/取消/人工重试新 job；5）租约耗尽终止与 stage ABANDONED 恢复；6）将 P1 人工补偿接到自动 Outbox；7）持久化任务查询/过滤。直接复用 P2 内核，不写第二套解析逻辑。

依赖：P2 的 job/stage/generation 数据与发布事务；无需 MQ、Redis 或独立调度服务。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/ingestion/JobRecoveryTest.java、ClaimFencingTest.java、ExecutorSaturationTest.java、outbox/OutboxRecoveryTest.java。双实例抢占只执行一份、旧 worker 租约过期后仍回包不得写入、重启恢复、DB 短暂中断、提交拒绝无丢单、max_attempts 耗尽、取消与发布竞争、日志不可被旧 token 完结；同时核对线程/连接/队列上限。

### P4 向量检索

文件：扩展已有（P2 新建的）services/backend/src/main/java/com/lzj/rag/backend/vector/PgvectorVectorStore.java；拟新增 retrieval/RetrievalController.java、RetrievalService.java、RetrievalTypes.java；config/VectorSearchConfiguration.java。可选受控 HNSW 迁移 services/backend/src/main/resources/db/migration/V5__vector_search_indexes.sql；初期 exact 可仅使用普通范围索引。

步骤：1）查询使用同一个固定 profile；2）授权 KB 列表后在 SQL 内做归属/墓碑/启用/active generation/profile/namespace 联合过滤；3）cosine distance 与 evidence 返回；4）限 topK、候选、查询 deadline；5）选择 HNSW 时做过滤召回对照与精确回退；6）数据库/模型不可用返回明确错误，不能返回伪成功证据。

依赖：P2/P3 已可靠生成完整向量代；没有其他 VectorStore 实现。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/retrieval/RetrievalIsolationTest.java、RetrievalRecallTest.java。含跨租户/跨 owner/跨 KB、同维异空间、禁用文档/Chunk、未发布代、墓碑、0 命中、零/非法查询向量、exact 金标和 ANN 过滤召回；使用有可解释近邻的固定向量 fixture，真实模型质量另行评测。

### P5 人工维护与删除

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/chunk/ChunkController.java、ChunkMaintenanceService.java、ChunkOverrideMapper.java；deletion/DeletionService.java、DeletionWorker.java；扩展 generation/GenerationPublisher.java、document/DocumentService.java；迁移 services/backend/src/main/resources/db/migration/V6__chunk_overrides_and_deletion.sql。

库级删除任务查询/重试拟新增services/backend/src/main/java/com/lzj/rag/backend/deletion/DeletionJobController.java，按冻结归属校验；不能要求已墓碑的base恢复存活才能查询清理状态。

步骤：1）独立 Chunk 启停 + override/manual_revision；2）编辑正文异步发布新代、未变输入可复制向量；3）跨代保留策略与 WAITING_REVIEW；4）文档/非空库显式级联墓碑及任务失效；5）S3/向量/历史代/Outbox 幂等清理和删除任务查询。

依赖：P3 fencing 与 Outbox、P4 可见性过滤。P1 的非空库拒绝策略到此才扩展，不提前留下孤儿资源。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/chunk/ManualMaintenanceTest.java、deletion/DeletionConsistencyTest.java。关键回归是“人工禁用 Chunk→禁用文档→重启→启用文档→该 Chunk 仍被禁用”；另测人工修改与旧 job 竞争、编辑向量失败旧正文/旧向量一致、无法匹配维护策略不静默重置、对象删除失败可恢复、重复删除、级联计数与历史权限。

### P6 多格式

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/parser/TikaDocumentParser.java、ExcelDocumentParser.java、CsvDocumentParser.java、ParserRegistry.java、ParserSandbox.java；需要时新增 MineruClient.java、model/AiVlmClient.java；在 services/backend/src/test/resources/knowledge-base/fixtures/ 放自有/可分发样本。

步骤：按 MIME 策略路由；Tika PDF/DOCX/HTML 等通用文本；POI 保留 sheet/row/cell，CSV 按 record 解析引号/换行/BOM；表格按行语义切块并重复表头；代码块/章节上下文保留。限制 ZIP 展开、条目、解析时间与临时磁盘；重型解析用隔离子进程并限制资源，不使主 worker 被无限阻塞。密码文档、损坏文档和无文本扫描件返回明确结果；OCR 默认关闭，需 MinerU 的实际部署版本/协议再实现，不假定其兼容 AI 接口。

依赖：P2 parser/splitter SPI；新增解析依赖版本在此锁定。独立图片仅按需通过现有 /ai/vision，受当前单图 2MiB 等限制；不把任意 S3 URL 交给供应商下载。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/parser/MultiFormatContractTest.java、ParserFailureTest.java。含 Markdown 标题/代码块、Excel 空单元格/公式不求值/多表、CSV 内嵌换行、PDF 页码、乱码、加密、解压炸弹、超时/进程终止；位置无法确认时不能捏造来源。OCR/VLM 无设施时只验证适配与本地协议 stub，明确未真联调。

### P7 URL 导入与刷新

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/importing/UrlImportController.java、SafeRemoteFetcher.java、UrlPolicy.java；refresh/RefreshScheduleController.java、RefreshScheduler.java、RefreshService.java；扩展 IngestionKernel/Outbox；迁移 services/backend/src/main/resources/db/migration/V7__remote_import_and_refresh_schedule.sql。

步骤：配置受控出站策略后才启用；HTTP 下载不可变源对象→相同内核；schedule CRUD、CronExpression/ZoneId 验证、租约/token、同事务入队与推进 next_run_at；304/hash 去重、失败不推进已发布 validators、漏触发合并、关闭/删除时停止调度。

依赖：P3 恢复与 P5 删除；生产允许域名与安全出站代理/ACL 必须提供，不能仅用 URL 正则宣称完成 SSRF 防护。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/importing/RemoteFetchPolicyTest.java、refresh/RefreshLeaseTest.java、RefreshConditionalRequestTest.java。域名重绑定/IPv6/重定向到私网、大小/超时、ETag 与 Last-Modified、304 但配置变更、下载成功入库失败、双实例/租约过期/禁用、UTC 与 Asia/Shanghai 及 DST 时区、停机补触发去重。

### P8 混合检索/回答生成

文件：拟新增 services/backend/src/main/java/com/lzj/rag/backend/search/KeywordSearchClient.java、ElasticsearchKeywordSearchClient.java、HybridRetrievalService.java；answer/AnswerController.java、AnswerService.java、CitationVerifier.java；model/AiRerankClient.java、AiLlmClient.java；迁移 services/backend/src/main/resources/db/migration/V8__keyword_outbox_and_answer_audit.sql（仅实际需要新增持久化列/账本时），扩展 Outbox 事件处理。

步骤：默认关闭 BM25；开启时 Outbox 按 generation 幂等索引、发布标记与重建；keyword 命中必须回查 DB 当前可见性后才返回/交给模型；RRF 融合 + 可选重排，声明实际使用的模式；引用只允许本次受权 evidence ID，0 证据拒答；提示注入文档作为不可信数据，不能改变授权和工具执行。

依赖：P4/P5 证据/删除语义；复用现有 /ai/rerank、/ai/chat，不使用 ES 向量字段；真实 ES 版本决定 Java Client 版本。

验收：拟新增 services/backend/src/test/java/com/lzj/rag/backend/search/HybridRetrievalTest.java、answer/AnswerCitationTest.java。ES 关闭向量独立工作；ES 滞后/失败/重复事件/删除回查；伪引用、空命中、跨租户、来源删除、重排异常、模型幻觉引用必须拒绝或明确无依据；真实模型回答质量和费用另行评测。

### P9 管理界面与交付

文件：web 当前只有 .gitkeep；此阶段才拟新增 web/package.json、web/src/main.tsx、web/src/api/knowledgeBase.ts、web/src/pages/KnowledgeBases.tsx、Documents.tsx、Chunks.tsx、Retrieval.tsx、Jobs.tsx。在该阶段按届时仓库实际框架/组件库复核；如仍空，以 React + TypeScript + Vite 为设计默认，不在此前阶段预装依赖。扩展 deploy/knowledge-base/compose.yml、docs/knowledge-base/RUNBOOK.md、services/backend/src/main/resources/static/openapi/knowledge-base.yaml、scripts/knowledge-base/acceptance.py。

步骤：知识库/文档/任务/Chunk 维护与来源预览；严格显示 target/active revision、失败恢复/删除清理进度；UI 不直接接受 tenant/operator 或对象 key。由于当前为机器 Basic API、CSRF 关闭，浏览器接入需在此先落实同源反向代理、凭据生命周期和 CSRF/Origin 防护，不能把密码硬编码或放入 localStorage；不扩建完整 IAM。交付 API 文档、完整 Compose、环境模板、备份/恢复与验收脚本。

依赖：前面服务契约稳定；真实 S3/模型/ES 可用性决定哪些验收能标为已联调。不存在前端组件库时才选组件，不能误称复用已存在的 React 项目。

验收：按实际前端设施新增端到端用例与可访问性检查；完整“上传→任务恢复→证据检索→人工禁用→重新启用文档不恢复 Chunk→删除”链路；冷启动迁移、滚动重启/双实例、凭据无泄露、备份恢复、容器构建与服务健康，记录真实而非模拟结果。

## 6. 完整调用链与待补充信息

后续完整链路：

受权用户 POST 知识库/绑定服务端 profile → multipart 上传（Idempotency-Key）→ 限额/MIME/SHA-256 → DB 预留 document+固定 objectKey+补偿意图 → 事务外 S3 PUT → DB 确认 STORED → 提交 ingestion job、分配 target_generation、返回 202 → worker 用 claim_token/租约取件 → S3 流式读取/原文校验 → Parser 产出带章节/页码/表格定位的 blocks → Splitter 生成 content/embedding_text 与 logical key → 应用人工 override → AiEmbeddingClient 携带 expectedConfigRevision 调用现有 /ai/embed → 校验实际身份、1024 维与批次 → JDBC 写同代 STAGING Chunk/vector → 短事务 fencing、数量校验并原子切换 active_generation/job 成功 → 受权 query 以同 profile 向量化 → PgvectorVectorStore 在 SQL 内限定归属/启用/墓碑/active generation 并 cosine 排序 → 返回含 chunkId/documentId/generation/sourceMetadata 的 evidence；P8 再将这些证据重排/生成受校验引用。

无须补充信息即可完成本轮设计。真正需要在后续相应设施联调前提供的是：

| 信息 | 何时需要/缺失时的处理 |
|---|---|
| S3 兼容服务 endpoint、region、bucket、path-style 要求，工作负载角色或密钥的环境/配置中心引用 | P1 真实联调前；可先用本地兼容存储测试，不把密钥写进文档或聊天 |
| 百炼实际开通区域/工作空间、text-embedding-v4 的可用性与固定版本策略、AI 服务地址和密钥注入方式 | P1 profile 真实核验/P2 付费联调前；沿用 qwen-embed/1024 默认设计，不擅自改 provider 或开第二个后端 |
| URL 导入允许域名与可提供的安全出站代理/网络 ACL | P7 开启前；未提供保持 URL 功能关闭，不阻塞 TXT/MD 上传和向量闭环 |
| 仅在启用可选功能时提供 ES endpoint/版本/凭据引用、MinerU 协议版本/部署地址或 OCR/VLM 开通信息 | P6/P8 对应功能开始前；关闭这些可选功能仍可交付核心向量检索 |

账号/租户范围直接沿用现有可信绑定，首版私有访问，不要求额外设计 IAM。常规限额已有可调整的设计默认值，不为尚未测量的规模追加本轮阻塞问题。文档与模型数据允许进入哪一区域/供应商应在实际部署配置时确认，不能由代码猜测。

## 7. 调研依据

仓库事实以本轮读取的文件为准；历史运行证据见 PROGRESS.md，不能把它当作本轮重新运行了测试。实现依赖的外部能力仅参照官方协议：

- [pgvector 官方](https://github.com/pgvector/pgvector)：固定维度、cosine 算子与 HNSW 限制。
- [百炼文本向量同步 API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)：所选模型的维度/批量与实际请求协议。
- [PostgreSQL 17 SELECT](https://www.postgresql.org/docs/17/sql-select.html)：队列式 SKIP LOCKED 使用边界。
- [Spring CronExpression](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/support/CronExpression.html)：六段表达式与 next(Temporal)。
- [AWS Java SDK 超时配置](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)：API/尝试/HTTP 各层预算。
- [Tika](https://tika.apache.org/)、[CommonMark](https://commonmark.org/)、[Apache POI](https://poi.apache.org/components/spreadsheet/)、[Commons CSV](https://commons.apache.org/proper/commons-csv/)：分别承担检测/通用抽取、结构化 Markdown、工作簿与 CSV record 解析。
