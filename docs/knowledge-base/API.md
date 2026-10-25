# 知识库 API 契约

## M. Web 管理台接入（2026-10-25，当前）

没有新增业务 API 或认证协议。Web 使用以下已有契约；原 OpenAPI/Postman 请求仍兼容。/api/knowledge/management 的历史 delivery=API_ONLY 字段保持兼容，它描述该 API 导航，不用于探测独立 Web 是否已部署。开发入口 http://127.0.0.1:5173/#/kbs，生产示例 http://127.0.0.1:8088/#/kbs。Hash 只用于浏览器路由，代理不把它传给服务端。

| 页面 | 主要接口 |
|---|---|
| 登录 | GET /api/knowledge/management，内存 Basic；401 回登录 |
| /#/kbs | /api/kbs CRUD、/api/kb-models 服务端模型选择 |
| /#/kbs/{id} | 文档分页、multipart 上传、import-url、授权 file、删除回执 |
| /#/documents/{id}?tab=config | 文档 PATCH version/name/ingestionSpec |
| ?tab=chunks | 当前代 Chunk 分页及 POST/PATCH/DELETE、独立启停、显式 batch-enabled |
| ?tab=jobs&job={jobId} | 提交/分页 job、详情/stages、retry、cancel |
| ?tab=refresh | URL 文档 schedule GET/PUT、refresh、refresh-runs |
| /#/search?kb={id} | search-capabilities、POST /api/knowledge/retrieve |

请求以 X-Request-Id 关联，标准 Result.data 解包；错误只展示友好说明、code、requestId，隐藏原始服务 message/堆栈。请求重定向被拒绝，凭据不发送到响应提供的 URL；file 按 documentId 构造已有授权接口下载，不展示 objectKey。上传/导入的 Idempotency-Key 在同一对话框相同提交内容重试时复用；任务 requestId 为 UUID，服务端仍负责最终幂等与文档并发约束。

人工维护带版本 CAS；冲突不静默重放。重新入库/刷新检测 hasManualChanges 后展示覆盖说明，并显式发送 confirmOverwriteEdited 与 expectedDocumentVersion。Chunk 空选项不触发批量操作；applyAll 是另一个需确认的按钮，不与 chunkIds 同传。文档启停不批量改写 Chunk 状态。

检索 UI 显示 strategy、channels、rerankStatus、refillLimitReached；scoreType、fusionScore、rerankScore、channelScores 保持各自含义。仅按来源真实存在的章节/页码/行/sheet 字段显示定位。范围选择器请求全部分页，上限 100 页后明确报错；UI 默认上限 16 库/100 文档/topK50/问题4000字符，服务端配置更严格时以校验响应为准。

Web 接入实测见 [WEB_VALIDATION.json](WEB_VALIDATION.json)：19项前端测试、35项浏览器/HTTP场景；未新增后端业务或迁移。

## L. 第 10 阶段：API 管理入口、审计与指标

原 API 管理交付保留。独立 Web 管理台见上节；API 导航仍为GET /api/knowledge/management（认证后返回Result.data）：delivery=API_ONLY、OpenAPI/调用集合路径、capabilities/知识库入口、真实ingestionDefaults、工作流和冲突提示。它不返回S3/ES/模型内部地址或密钥，不创建用户管理功能。

| 方法与路径 | 返回与权限 |
|---|---|
| GET /api/knowledge/management | 200，认证后的API导航和分块默认值 |
| GET /openapi/knowledge-base.yaml | 200，OpenAPI3.0.3/version1.0.0 YAML；需认证 |
| GET /openapi/knowledge-base.postman_collection.json | 200，52条Postman2.1请求；凭据留空、交互选择操作，需认证 |
| GET /api/kbs/{kbId}/audit?current=1&size=20&action= | 200，当前owner私有范围的提交后摘要审计；未知/越权库404 |
| GET /actuator/metrics | 运维账号200原生Actuator JSON；未认证401、普通账号403 |
| GET /actuator/metrics/{name}?tag=status:PENDING | 运维账号200指标详情，tag可重复；不存在指标404 |

审计沿用current/size（1起，size最多100），createdAt/id降序；action可空或1..64位大写字母/下划线精确过滤。记录为id、documentId（库操作可空）、actorId、action、chunkIds、beforeSummary、afterSummary、createdAt；不包含正文、来源URL、objectKey或凭据。新增BASE_INSERT/BASE_UPDATE、DOCUMENT_METADATA、INGESTION_STATE、REFRESH_SETTINGS、REFRESH_STATE，保留既有CHUNK_*与DOCUMENT_*维护动作。版本、数量、状态、名称哈希和内容哈希构成摘要；心跳和未改变这些摘要的写操作不新增审计。失败事务不留下成功变更记录；这是变更审计，不记录所有失败请求。

KnowledgeBase响应新增documentCount：当前未删文档数量，包含被禁用文档。Document响应新增lastIngestedAt：上次成功发布generation的UTC时间，尚未成功则null，人工修改/失败入库不推进。其他字段和权限契约不变。

调用集合中的kbVersion/documentVersion/scheduleVersion按JSON数字传递，成功响应更新变量；本地上传先选文件。先GET最新文档版本再维护，409时重新读取；人工重建需查看覆盖范围，再显式确认。保持jobRequestId不变可演示提交幂等，新任务手动更换。异步进度只读job/stages，建议活动时每2秒查询，终态停止、后台/隐藏时降至30秒；Web 管理台按此读取真实状态，不计算虚构进度百分比。

operator仅由服务器kb.security.accounts[].operator或示例单账号KB_OPERATOR配置，默认false；请求不能获得该权限。该标志只允许读全局指标，库/文档/审计仍受原tenant+owner校验。全局指标没有普通用户入口，/actuator/env、configprops、loggers、heapdump等路由均拒绝，不新增IAM或账号CRUD。

本版契约已通过最终JAR实际HTTP验收，OpenAPI为41路径/52操作/416引用，调用集合52条请求均核对方法、路径及数字版本。请求样例与测试临时UUID见PROGRESS链接；临时设施已关闭，不将示例ID视为常驻生产资源。真实供应商模型质量和容器镜像启动仍受RUNBOOK所列外部依赖限制。

## K. 第 9 阶段：可配置混合检索（当前有效）

原POST /api/knowledge/search继续返回Result.data证据数组；新增POST /api/knowledge/retrieve返回Result.data={evidence,requestedStrategy,strategy,rerankStatus,channels,channelCandidates,fusionCandidates,topK,refillLimitReached,minRerankScore}。二者使用同一实现，认证/归属/分页ID限制和基本请求字段沿用G节。全文和精排默认关闭，AUTO走原向量行为，余弦score、原错误码和topK上限保持原约定。

```json
{"query":"麒麟数据库 ZX-1472","kbIds":["00000000-0000-0000-0000-000000000001"],"strategy":"HYBRID","rerank":true,"topK":5,"minRerankScore":0.5}
```

新增strategy=AUTO|VECTOR|HYBRID|KEYWORD，省略用服务器默认AUTO。AUTO在关键词已配置时使用HYBRID，否则VECTOR；指定HYBRID/KEYWORD但未启用关键词400/KB_KEYWORD_DISABLED。rerank省略遵循服务器，true而未配置400/KB_RERANK_DISABLED。模型别名和供应商来自服务器，不能由请求随意选择。

minScore仍为有限[-1,1]余弦阈值，只在VECTOR合法，混合/关键词请求使用它会400/KB_AMBIGUOUS_THRESHOLD。minRerankScore是有限、依赖所选精排模型尺度的阈值，省略使用KB_RERANK_MIN_SCORE；要求启用精排，否则400/KB_RERANK_REQUIRED。精排失败有阈值时返回空证据+THRESHOLD_UNAVAILABLE，不用RRF替代分数；无阈值时保留融合顺序+FALLBACK_FUSION。精排输入超过服务端总字符预算也明确回落，不悄悄截正文。

Evidence保留chunkId/kbId/documentId/documentName/chunkIndex/generation/content/sourceMetadata/fileUrl，新增fusionScore、rerankScore、channelScores=[{channel,rank,score,scoreType}]。channel=VECTOR或KEYWORD，rank从1开始；原分数分别是COSINE_SIMILARITY或BM25。融合管线scoreType=RRF、score=fusionScore；rerankScore独立，最终按精排分排序时不保证score降序。RRF不是概率；不同通道原分数不直接相加。纯向量且无精排仍scoreType=COSINE_SIMILARITY，fusionScore/rerankScore=null。

实际strategy=VECTOR、KEYWORD或KEYWORD+VECTOR；表示成功执行的通道，包括成功但0命中的通道。channels各项={channel,status:SUCCEEDED|FAILED|TIMEOUT,candidates,errorCode}。单通道失败/超时丢弃该路，另一成功时200并声明实际策略；全部失败503/KB_SEARCH_UNAVAILABLE，不能伪装[]。精排状态DISABLED、SKIPPED_EMPTY、APPLIED、FALLBACK_FUSION、THRESHOLD_UNAVAILABLE。refillLimitReached表示关键词竞态回表过滤到达有界补召回上限，不允许放行无效内容。

原/search在空结果时也返回X-KB-Search-Strategy、X-KB-Rerank-Status和X-KB-Channel-Status，方便旧数组客户端识别实际策略。需要完整状态的新客户端使用/retrieve。GET /api/knowledge/search-capabilities返回配置的defaultStrategy、keywordConfigured/analyzer/scoreType、rerankConfigured/model、漏斗预算、answerEnabled=false；配置不等于外部服务已连通或质量已验证。

默认漏斗每通道50→RRF去重后20→可选精排→topK5。topK大于融合预算时有界扩大两个预算，受原maxTopK限制。向量通道仍不共用不同嵌入空间；HYBRID遇向量模型不兼容时该路失败，可返回独立BM25结果并标识KEYWORD，严格需要向量兼容校验时显式VECTOR。

关键词先按可信tenant/owner和PG已确认的doc:syncRevision过滤ES，再回表校验当前generation/启用/墓碑/正文hash；待同步文档暂不参与关键词召回，向量照常。默认查询最多2000个已同步文档，超过时该路KB_KEYWORD_SCOPE_LIMIT，需缩小documentIds。旧修订、单独禁用Chunk和已删除文档不因ES延迟被放行；返回来源取PG白名单，模型等待后再次校验，不信任ES正文或伪造来源。

| 方法与路径 | 作用 |
|---|---|
| GET /api/documents/{id}/keyword-index | 自有未删文档的同步状态 |
| GET /api/kbs/{kbId}/keyword-sync?current=1&size=20 | 自有库全部文档同步分页，包含墓碑文档；size 1..100 |
| POST /api/kbs/{kbId}/keyword-sync/{id}/retry | 授权重建/重试，文档可已删；RUNNING返回409/KB_KEYWORD_SYNC_ACTIVE |

Sync={documentId,revision,syncedRevision,status,attempts,nextRunAt,leaseUntil,errorCode,syncedAt,cleanupStatus,cleanupErrorCode}。status=PENDING|RUNNING|SUCCEEDED|FAILED；retry递增revision、重置有限次数并排队，不在HTTP内调用ES。未启用关键词时仍记录PENDING意图，稍后配置可恢复；所有时间UTC。已索引文档的同步/物理回收未完成时，删除父知识库仍409/KB_NOT_EMPTY。

本轮只返回结构化证据，不新增/answer或流式生成接口。权限仍来自服务端principal，未授权401、未知/跨用户/跨租户404；任何外部通道或精排调用都在全范围校验后发生。

## J. 第 8 阶段：URL 导入与刷新（当前有效）

继续使用Basic、可信tenant+owner私有归属、统一Result和严格字段/类型校验；未认证401，未知/越权/已删404，不在下载或模型调用后才查权限。Document.sourceType现可为UPLOAD或URL，新增sourceUrl（UPLOAD为null），其余原文件字段始终表示已保存的当前版本；检索来源不泄露远程URL查询参数。只有URL导入文档可刷新，本地文件返回409/KB_NOT_REMOTE_DOCUMENT。

| 方法与路径 | 输入 | 成功 data / 状态 |
|---|---|---|
| POST /api/kbs/{kbId}/documents/import-url | Idempotency-Key；JSON url,filename,可选name/ingestionSpec/ingest=false | {document,jobId,replayed}；仅保存201，提交入库202，重放200；Location、Idempotency-Replayed |
| GET /api/documents/{id}/refresh-schedule | 无 | Schedule；200 |
| PUT /api/documents/{id}/refresh-schedule | version,enabled,cron,timezone | Schedule；200 |
| POST /api/documents/{id}/refresh | requestId，可选confirmOverwriteEdited/expectedDocumentVersion | Run；202，幂等200 |
| GET /api/documents/{id}/refresh-runs | current=1,size=20（1..100） | Page<Run>；200，新到旧createdAt/id排序 |

导入示例（实际url必须在服务端白名单且可下载；显式filename用于选择格式，不信任响应文件名/MIME）：

```json
{"url":"https://docs.example.org/guide.md","filename":"guide.md","name":"远程指南","ingest":true,"ingestionSpec":{"parser":"AUTO","parseProfile":"fast"}}
```

Idempotency-Key为16..128安全ASCII字符，按KB/tenant/owner唯一，绑定规范URL、filename、name、spec和ingest；同键同参COMPLETED直接返回原doc/job且不再下载，不同参409/KB_IDEMPOTENCY_CONFLICT；运行或失败/过期的同键409/KB_IMPORT_NOT_READY，按存储操作恢复后换新key。下载/对象写入有界，入库不占用此HTTP请求；ingest=false仅PENDING，true返回持久化jobId。初次文档/import完成与可选job提交同事务；成功HTTP基线在首次激活后才存在。

Schedule={documentId,enabled,cron,timezone,version,nextRunAt,lastStatus,lastReason,lastRunId,lastContentHash,consecutiveFailures}。初始enabled=false、version=1、timezone=UTC、cron/nextRunAt/lastStatus可null。首次导入入库成功记录SUCCESS/INITIAL_IMPORT_ACTIVATED。version是**调度配置版本**；与文档version独立。cron使用Spring六字段，timezone使用明确ZoneId（如Asia/Shanghai或UTC），保存/返回UTC时间；非法或无未来时刻400/KB_INVALID_SCHEDULE。错过的多个时刻合并一次，完成后计算下一次。

```json
{"version":1,"enabled":true,"cron":"0 */30 * * * *","timezone":"Asia/Shanghai"}
```

启用schedule要求文档启用、remote配置可用；已有活跃refresh时变更为启用配置409，enabled=false则取消job并失效run。文档禁用/删除同步关闭schedule；重新启用文档不自动重开。未变化的检查也产生SKIPPED记录及下一执行时间；连续失败仅终态FAILED递增，SUCCESS/正常SKIPPED复位。

Run={id,documentId,requestId,triggerType,status,jobId,attempts,maxAttempts,httpStatus,reason,errorMessage,nextRunAt,startedAt,completedAt,durationMs}。triggerType=MANUAL|SCHEDULED，status=PENDING→RUNNING→WAITING_INGESTION→SUCCESS|FAILED|SKIPPED；网络/租约恢复可在有限次数内回到PENDING。终态durationMs含排队后实际开始以来的重试等待时间；未开始/无HTTP响应时相应字段null。内部token/owner/候选key不作为客户端参数。jobId出现后用原GET /api/ingestion-jobs/{jobId}及/stages查看失败阶段。

```json
{"requestId":"refresh-20261024-001","confirmOverwriteEdited":false}
```

requestId为8..128安全ASCII字符，按可信tenant/owner唯一；documentId、人工确认与expectedDocumentVersion参与幂等身份。相同命令返回同run，另一个活跃refresh409/KB_REFRESH_ACTIVE，已有手动入库409/KB_JOB_ACTIVE。反向提交入库或Chunk维护也会拒绝活跃refresh；不是仅依靠进程锁。人工改动需要confirmOverwriteEdited=true和审阅后的expectedDocumentVersion，否则409/KB_MANUAL_OVERWRITE_REQUIRED或KB_VERSION_CONFLICT。自动刷新遇人工改动记录SKIPPED/MANUAL_CHANGES_REQUIRE_CONFIRMATION，不自动覆盖。

304对应SKIPPED/HTTP_NOT_MODIFIED；200内容SHA-256相同对应SKIPPED/CONTENT_HASH_UNCHANGED。两者均不解析/Embedding，后者只更新同一已激活版本的validators。重定向的每个新URI均清除条件头，且首版不保存目标响应的validators，下次以完整下载/hash判断，避免跨URI误用ETag。内容变化后对象先作为候选，live文件/activeGeneration/成功HTTP基线在新代成功前保持旧值；成功同PG事务切换并记录SUCCESS/CONTENT_ACTIVATED。任务失败、取消、输入变化或租约失效不能推进基线，旧内容继续可读/可检索。刷新不是强制重新分块；未变时保留人工块，强制重建使用原ingestion-jobs及覆盖确认。

典型reason：CONTENT_ACTIVATED、HTTP_NOT_MODIFIED、CONTENT_HASH_UNCHANGED、INGESTION_ACTIVE、INPUT_CHANGED、SCHEDULE_DISABLED、DOCUMENT_DISABLED_OR_DELETED、MANUAL_CHANGES_REQUIRE_CONFIRMATION，失败时使用固定errorCode（含原job.errorCode），errorMessage只返回脱敏摘要。关联refresh的job显式retry返回409/KB_REFRESH_RETRY_REQUIRED；使用新requestId提交refresh，让源文件、成功基线和任务版本继续一致。

新增HTTP错误：KB_REMOTE_DISABLED（503）；KB_REMOTE_URL_REJECTED/KB_REMOTE_ADDRESS_REJECTED（400）；KB_REMOTE_REDIRECT_LIMIT（422）；KB_REMOTE_ENCODING_UNSUPPORTED（415）；KB_EMPTY_FILE（400）；KB_UPLOAD_TOO_LARGE（413）；KB_REMOTE_BUSY/KB_REMOTE_RATE_LIMITED（429）；KB_REMOTE_TIMEOUT（504）；KB_REMOTE_DOWNLOAD_FAILED/KB_REMOTE_HTTP_ERROR/KB_REMOTE_HTTP_REJECTED/KB_REMOTE_INVALID_RESPONSE（502）；已有MIME/解析器校验415/422。未授权资源在这些检查之前404。暂时网络/429/5xx可有限重试，4xx拒绝、地址/类型/大小/永久解析错误不反复尝试；不把失败伪装成未变化。

完整可执行流程见RUNBOOK和scripts/knowledge-base/refresh_acceptance.py；生产来源/出口必须自行配置，文档中的example.org只是占位。

## I. 第 7 阶段：文件格式及解析档位（当前有效）

上传、PATCH ingestionSpec、可靠任务与检索路径保持既有规范；支持矩阵接口 **GET /api/knowledge/formats**（需认证，200 Result.data数组）。每项={extensions:[string],fast:string,fidelity:string,notes:string}。LOCAL表示本地实现，LOCAL_TEXT_ONLY明确无OCR，*_CONFIGURED仅表示配置启用，*_DISABLED表示未启用，UNAVAILABLE表示未实现该格式/档位。不要把配置状态当外部连通或效果验收。

```json
{"ingestionSpec":{"schemaVersion":1,"parser":"AUTO","parseProfile":"fast","splitter":{"kind":"STRUCTURAL","maxChars":1024,"overlapChars":128,"tableRows":50,"toleranceChars":512}}}
```

上传metadata使用上面的multipart字符串part；PATCH文档时再带当前version。旧文档/job快照缺parseProfile时按fast。parser=TEXT仅TXT，MARKDOWN仅Markdown，其他格式使用AUTO。非法档位/字段400，格式或MIME/扩展名不匹配415，不会保存对象；请求的格式/档位无路由同样415/KB_PARSER_UNAVAILABLE。

PDF使用fidelity时把parseProfile改成fidelity，显式配置MinerU；扫描PDF的fast无文字会在PARSE阶段失败422/KB_OCR_REQUIRED。图片fast/fidelity均依赖VLM，不假装普通文本抽取。TXT/MD/CSV/Excel的fidelity是已声明的本地别名，Office fidelity目前415；不会在MinerU/VLM失败后隐式换fast。完整矩阵见FORMATS.md。

成功上传仍只PENDING，不自动解析/调用模型。格式/公式/尺寸/服务错误通过原ingestion job/stages查询，永久错误不自动重试；临时MinerU连接、限流和超时沿用有限重试。后台状态和旧activeGeneration保持既有契约。

新增错误码：KB_CONTAINER_LIMIT/KB_PARSE_LIMIT（422，上限）、KB_PARSE_FAILED（422，不可解析/内存结果预算）、KB_PARSE_TIMEOUT（504，本地硬期限）、KB_OCR_REQUIRED（422）、KB_FORMULA_EVALUATION_FAILED/KB_CSV_ROW_WIDTH/KB_TABLE_ROW_WIDTH/KB_TABLE_STRUCTURE_UNSUPPORTED（422）、KB_IMAGE_LIMIT/KB_IMAGE_INVALID（422）、KB_MINERU_UNAVAILABLE/KB_VLM_UNAVAILABLE（503未配置）、KB_MINERU_PROTOCOL_ERROR/KB_MINERU_CONTENT_UNSUPPORTED/KB_VLM_INVALID_RESPONSE/KB_VLM_MODEL_CHANGED（502永久协议/结果错误）、KB_PARSE_SERVICE_UNAVAILABLE（503临时）、KB_EXTERNAL_PARSE_TIMEOUT（504临时）。Job.errorMessage继续固定脱敏摘要，实际错误类别看errorCode/stage，不返回文件/base64/供应商原始错误。

知识检索/Chunk来源增加可用的sourceBlocks.slideNumber、pageNumber、bbox、provenance、imageNumber/imageWidth/imageHeight、generated、vlmModel，以及表格顶层sheetName/tableName/headerRow/rowNumbers/rowNumberUnit/formulaPolicy。Excel的rowStart/rowEnd/rowNumbers为1起始真实工作表行号；CSV为1起始记录号（含header=1），带rowNumberUnit=CSV_RECORD，引号内换行不改变记录号。bbox坐标保留服务端原始单位，不自行归一化或猜像素；未提供页码/标题时不造值；图片生成文本明确generated，不宣称逐字OCR准确。

以下为此前阶段记录；格式支持与解析档位以本节和支持矩阵为准。

## H. 第 6 阶段：人工维护与生命周期（当前有效）

统一 Basic、服务端 tenant+owner 私有归属、Result(code/message/data/requestId)、未知字段/标量类型拒绝规则继续沿用。所有维护请求携带**文档** version（GET /api/documents/{id} 获取），成功后使用响应 documentVersion 或文档 version；Chunk 没有独立客户端版本。不存在/不属于调用者/已删均404，未认证401。

| 方法与路径 | 输入 | 成功 data / 状态 |
|---|---|---|
| GET /api/documents/{documentId}/chunks | current=1,size=20（1..100）、可选enabled | 当前 activeGeneration 分页，按chunkIndex/id升序；200 |
| POST /api/documents/{documentId}/chunks | version,content | Changed；201 |
| PATCH /api/documents/{documentId}/chunks/{chunkId} | version,content | Changed；200 |
| DELETE /api/documents/{documentId}/chunks/{chunkId}?version=N | 文档版本 | Changed，chunks=[]；200 |
| PATCH /api/documents/{documentId}/chunks/{chunkId}/enabled | version,enabled | Changed；200 |
| POST /api/documents/{documentId}/chunks/batch-enabled | version,enabled,chunkIds 或 applyAll | Changed；200 |
| PATCH /api/documents/{documentId}/enabled | version,enabled | Document；200 |
| DELETE /api/documents/{documentId}?version=N | 文档版本 | DocumentCleanup；202，Location指向清理回执 |
| GET /api/document-cleanups/{id} | 无 | DocumentCleanup；200 |
| POST /api/document-cleanups/{id}/retry | 无body | FAILED重排（attempts清零）；PENDING/SUCCEEDED原样返回200；RUNNING409 |

Changed={documentVersion,manualRevision,chunkCount,chunks:[ChunkView]}。ChunkView={id,documentId,kbId,generation,chunkIndex,content,embeddingText,contentHash,charCount,tokenCount,tokenCountEstimated,enabled,edited,metadata,createdBy,updatedBy,createdAt,updatedAt}。metadata 为真实解析来源或人工来源，人工块不再宣称原文行号/章节；原文件仍需授权且不会被人工编辑改变。普通编辑不接受任意embeddingText、metadata、tenant或向量。tokenCountEstimated=true表示粗估，不能当成供应商实际分词数。

Document 新增 manualRevision（单调）、hasManualChanges。chunkCount 为当前代现存总块数，含禁用块；禁用仅改变检索有效范围。内容以原格式展示，CRLF/CR统一LF，非空、默认最多8192 code point，非法Unicode/NUL400。首次添加要求文档已有成功 activeGeneration；无索引409/KB_DOCUMENT_NOT_INDEXED，文档未启用时新增/编辑/启用Chunk为409/KB_DOCUMENT_DISABLED。禁用/删除块可用于已禁用文档。没有解析时 list 返回 records=[]，不自动入库。

```json
{"version":1,"content":"人工确认：数据库备份保留七天。"}
```

```json
{"version":2,"enabled":false,"chunkIds":["11111111-1111-1111-1111-111111111111"]}
```

```json
{"version":3,"enabled":true,"applyAll":true}
```

批量两种范围互斥：applyAll=true必须省略chunkIds；否则要求非空、唯一、无null且默认最多200项。[]不会解释成全选；[]、重复、超限、同时全选和IDs400；任一块不属于该文档当前代404且全批回滚。applyAll含单独禁用块，是显式全选启停操作，与文档重新启用的保留规则不同。

新增/编辑通过既有模型同步重算向量（默认总等待预算40s），调用位于事务外；写回时再次检查文档 version/generation、启用、墓碑、入库冲突。模型502/503/504或DB503时不更换已有正文/向量，不返回“已修改”。过期版本409/KB_VERSION_CONFLICT；入库PENDING/RUNNING409/KB_JOB_ACTIVE。首次创建服务器生成ID，编辑保持ID/序号，删除后序号不复用；删除重复调用404。无单独幂等键，版本CAS避免同一成功请求被重复写入；响应丢失后先GET文档/Chunk确认再重试。

文档禁用会取消/失效当前入库但保留active内容和每块enabled；文档重新启用只恢复本身enabled的块，必要时重建缺失向量，失败时保持原禁用状态。原物理向量保留，查询在距离排序前同时过滤文档和Chunk启停。文档启停也增加version，不自动恢复已取消任务。

**重新入库覆盖确认**：人工增改删或Chunk启停后，提交 /ingestion-jobs 及任务 /retry 都要求以下参数，否则409/KB_MANUAL_OVERWRITE_REQUIRED；版本已变409/KB_VERSION_CONFLICT。确认说明：从原文件重建全部自动块，覆盖人工正文、人工新增/删除和块独立启停。先GET文档并审阅当前Chunk后提供版本。

```json
{"requestId":"reviewed-reingestion-001","confirmOverwriteEdited":true,"expectedDocumentVersion":4}
```

requestId原作用域不变；确认参数/expectedDocumentVersion也参与同键冲突校验，相同确认重放原任务200，即使它已完成；不同参数409/KB_IDEMPOTENCY_CONFLICT。新代成功才清除hasManualChanges；失败保留旧证据与人工状态。doc.enabled本身不会在重新入库中被自动改变。

删除文档先同事务墓碑/撤销资格/取消任务/持久化清理，再202返回；之后GET文档、原文件、Chunk、文档任务入口404，检索排除，旧worker不能激活或覆盖状态。回执即使父KB已墓碑仍按可信tenant/owner查询。重复删除（同归属、正version参数）返回同一回执，不重复建任务。删除不可撤销，原业务墓碑、任务/阶段与脱敏审计按当前保留策略保留。

DocumentCleanup={id,documentId,status,stage,attempts,maxAttempts,nextRunAt,leaseUntil,errorCode,errorMessage,createdAt,completedAt}；不暴露objectKey、租约token或凭据。DATABASE→OBJECT；PENDING→RUNNING→SUCCEEDED，失败有限次PENDING退避后FAILED，进程重启领取过期RUNNING。清理所有generation/向量/原文件；当前无远程刷新设施，无额外刷新任务需停止。失败仍不可检索；清理完成前父KB删除409/KB_NOT_EMPTY。版本化bucket返回KB_STORAGE_VERSIONED_BUCKET，必须处理版本政策后再retry，不能把S3删除标记误当物理清除。

以下内容保留此前阶段记录；涉及人工维护和删除时，以本节及最新 API 契约为准。

更新日期：2026-10-24。第5阶段已完成结构化知识检索；A–E、G与J中的AI元数据为当前契约，F及H–I为后续设计。上传后显式POST任务，HTTP不执行长入库。运行配置与完整命令见 RUNBOOK.md；可执行 OpenAPI：`/openapi/knowledge-base.yaml`（需要认证）。

## A. 通用约定（已实现）

- 默认 `http://127.0.0.1:8080`；Basic 凭据来自服务端账号配置，归属取 principal 的 tenantId/userId。正文归属字段和未知字段拒绝；请求租户头不参与授权。跨用户、跨租户、已删除父知识库统一404。没有新增 IAM 或共享权限。
- JSON 为 `{code,message,data,requestId}`；成功 code 为字符串 `0`，错误 data=null。合法 X-Request-Id 为1–128位安全 ASCII，响应头与 JSON 一致。健康和原文件响应除外。
- 分页 current 默认1、范围1..1000000；size 默认20、范围1..100；返回 `{current,size,total,records}`，按 createdAt/id 倒序。name 为不区分大小写的字面子串（`%`、`_` 不是通配符）。知识库与文档列表的计数、记录处于同一数据库快照；存储操作列表为运维视图，状态允许在分页间改变。
- 更新和删除必须携带最新正整数 version；冲突409。PATCH 省略或 null 表示保留，description 设空字符串可清空。请求类型严格，不接受数字转字符串或小数转整数。
- 知识库创建没有幂等键保证；文档上传要求 `Idempotency-Key: 16..128位 [A-Za-z0-9_.:-]`，按知识库+可信归属唯一。相同键、相同文件哈希/原名/规范化显示名/规范化入库配置：完成后200返回同一个文档的**当前详情**，`Idempotency-Replayed: true`；首次201/false，带 Location。不同载荷409；处理中或失败的键409，先查询清理状态，再使用新键。操作记录本阶段不自动过期。

```json
{"code":"KB_VERSION_CONFLICT","message":"Reload the current version and retry","data":null,"requestId":"demo-2"}
```

| HTTP | code / 含义 |
|---|---|
| 400 | KB_INVALID_INPUT；KB_EMPTY_FILE；KB_PROFILE_NOT_ALLOWED |
| 401 / 403 | KB_UNAUTHENTICATED（WWW-Authenticate）/ KB_FORBIDDEN |
| 404 | KB_NOT_FOUND / KB_DOCUMENT_NOT_FOUND / KB_OPERATION_NOT_FOUND；包含不属于当前用户 |
| 409 | KB_NAME_EXISTS / KB_DOCUMENT_NAME_EXISTS / KB_VERSION_CONFLICT / KB_NOT_EMPTY |
| 409 | KB_EMBEDDING_LOCKED / KB_MODEL_UNBOUND / KB_IDEMPOTENCY_CONFLICT / KB_UPLOAD_NOT_COMPLETED / KB_UPLOAD_LEASE_EXPIRED / KB_OPERATION_BUSY / KB_OBJECT_REFERENCED |
| 413 / 415 | KB_UPLOAD_TOO_LARGE / KB_UNSUPPORTED_FILE；错误请求 Content-Type 为 KB_HTTP_415 |
| 503 | KB_DATABASE_UNAVAILABLE / KB_STORAGE_UNAVAILABLE / KB_FEATURE_DISABLED / KB_UPLOAD_IO_UNAVAILABLE / KB_CLEANUP_PENDING |
| 500 | KB_INTERNAL；隐藏 SQL、凭据、供应商异常和内部路径 |

## B. 知识库与服务端模型（已实现）

| 方法/路径 | 请求和响应 |
|---|---|
| GET /api/kb-models | 200，可用配置列表：id/modelId/provider/modelName/dimension/embeddingSpace/configVersion/configRevision |
| POST /api/kbs | name必填，description可选，embeddingProfileId可选（默认服务端 qwen-embed-1024）；201详情与Location |
| GET /api/kbs | current/size/name；200分页 |
| GET /api/kbs/{kbId} | 200详情 |
| PATCH /api/kbs/{kbId} | version必填；name/description/embeddingProfileId至少一个；200详情 |
| DELETE /api/kbs/{kbId}?version=1 | 仅空库软删除，200/data=null；有文档或 UPLOADING/CLEANUP_PENDING 操作返回409/KB_NOT_EMPTY |
| PUT /api/kbs/{kbId}/embedding-binding | 仅迁移前遗留 UNBOUND 库；version、embeddingProfileId必填；200，首次绑定后不可再改 |

name strip后非空、最长128个UTF-16单元；description最长2000，两者拒绝NUL。存活名称按 tenant/owner/PostgreSQL lower(name) 唯一。绑定快照与 namespace 在创建时由服务端生成；只接受允许目录的 profile id，不接受用户传入 provider/dimension/namespace。首版固定1024维。绑定后即使尚无向量也禁止直接更换，409明确要求新建库并重建迁移；后续不会绕过已有向量保护。

详情：id/name/description/version/createdAt/updatedAt，以及 bindingStatus (`BOUND`/`UNBOUND`)、embeddingProfileId、embeddingModelId、embeddingProvider、embeddingDimension、embeddingConfigRevision（服务端配置快照SHA-256）、vectorNamespace、embeddingAiConfigRevision（首次成功发布前null，其后固定）。旧库模型字段为null，须显式绑定再上传。AI修订在首次发布同一事务钉住，后续漂移409，不能把新空间写入已有库。

```json
{"name":"产品手册","description":"本地上传","embeddingProfileId":"qwen-embed-1024"}
```

原 `/api/v1/knowledge-bases` 集合及 `/{id}` 路由继续可用，与新路由共用服务。原 `PUT /{id}` 仍要求 name/version，省略或null description会清空；新路由也接受该兼容 PUT。GET `/actuator/health` 无需认证。

## C. 文档上传与维护（已实现）

| 方法/路径 | 请求和响应 |
|---|---|
| POST /api/kbs/{kbId}/documents | multipart：file必填，metadata可选JSON part；Idempotency-Key必填；201（重放200）文档详情 |
| GET /api/kbs/{kbId}/documents | current/size/name/status；status支持PENDING/PROCESSING/READY/FAILED或省略；200分页 |
| GET /api/documents/{documentId} | 200详情 |
| PATCH /api/documents/{documentId} | version必填；name或ingestionSpec至少一个；200详情 |
| GET /api/documents/{documentId}/file | 授权后流式200原始字节，实际Content-Type、长度、attachment原名、no-store、nosniff；不返回签名URL |

仅 `.txt`、`.md`、`.markdown`，必须非空且是 UTF-8 文本。路径型原名仅保留 basename；原名、显示名strip后非空且≤255、无控制字符。忽略客户端Content-Type，用 Tika 基于内容检测；Markdown实际可能是 text/plain，按检测值保存。二进制伪装、非UTF-8、不支持扩展名415。文件大小同时受 Servlet 与实际流字节上限控制，默认20MiB；临时文件落盘，8KiB流式计数和哈希，失败清除临时文件。

metadata缺省等价下例。入库配置是严格的完整值，PATCH时不作嵌套部分合并；省略的子字段填默认值。schemaVersion只能1，parser为AUTO/TEXT/MARKDOWN且与扩展名匹配；kind为STRUCTURAL或WHOLE_DOCUMENT；maxChars范围128..4000，overlapChars范围0..1000且小于maxChars；tableRows范围1..1000（默认50），toleranceChars范围0..4096（默认512）。未知字段拒绝。上传及PATCH**只保存配置，不自动入库**；显式POST任务捕获当前配置，后台执行。

```json
{"name":"产品说明","ingestionSpec":{"schemaVersion":1,"parser":"AUTO","splitter":{"kind":"STRUCTURAL","maxChars":1024,"overlapChars":128,"tableRows":50,"toleranceChars":512}}}
```

```json
{"version":1,"name":"新名称","ingestionSpec":{"schemaVersion":1,"parser":"AUTO","splitter":{"kind":"STRUCTURAL","maxChars":800,"overlapChars":120}}}
```

文档详情：id/kbId/name/originalFilename/sourceType(UPLOAD)/objectKey/mimeType/fileSize/contentHash(SHA-256)/enabled(初始true)/processStatus(PENDING、PROCESSING、READY或FAILED)/ingestionSpec/configRevision(初始1)/activeGeneration(初始null)/chunkCount(初始0)/version/createdAt/updatedAt。相同库中存活文档显示名按lower(name)唯一。内容相同但名称、幂等键不同允许上传，未做内容去重。配置实质变化才 configRevision+1；任意有效PATCH version+1并将processStatus置PENDING、阻止旧输入发布；activeGeneration保留，原文件、哈希和objectKey不修改。

## D. 上传补偿与后续状态边界

上传前先持久化 UPLOADING 操作，再在数据库事务之外S3 PUT。成功后同一短事务插入 PENDING 文档并置 COMPLETED；原文件保存稳定objectKey。数据库失败后，先确认没有已提交引用并领取清理租约，再删除对象。删除失败持久化 CLEANUP_PENDING/attempts/nextRunAt/errorCode；进程中断留下的UPLOADING可在租约过期后恢复。提交结果不确定且数据库不可用时保留意图，禁止盲删对象。

| 方法/路径 | 行为 |
|---|---|
| GET /api/kbs/{kbId}/storage-operations | current/size；200分页：id/idempotencyKey/status/attempts/nextRunAt/leaseUntil/errorCode/updatedAt；用原幂等键定位超时上传 |
| POST /api/kbs/{kbId}/storage-operations/{operationId}/retry-cleanup | 当前用户手动重试；仅未被文档引用的CLEANUP_PENDING或已过期UPLOADING；清理成功/已清理200，活跃租约或已提交引用409，删除失败503 |

上传补偿仍由当前用户显式retry-cleanup，nextRunAt为建议时间，数据库租约协调；此处与第4阶段自动运行的generation清理是两种不同职责。清理成功保留CLEANED记录，重新上传使用新幂等键。文档删除、启停和Chunk维护仍待后续阶段；公共检索见G节。

文档状态为PENDING→PROCESSING→READY/FAILED；排队和自动重试期间为PROCESSING。取消或输入过期置PENDING。有效PATCH置PENDING并保留activeGeneration，运行中的旧输入不得发布。重新入库失败可同时呈现FAILED和非空activeGeneration，表示旧证据仍可检索。文档enabled与Chunk人工enabled独立，启用文档不得恢复人工禁用Chunk。

## E. 入库任务与阶段日志（已实现）

| 方法/路径 | 请求/语义 | 成功 |
|---|---|---|
| POST /api/documents/{documentId}/ingestion-jobs | JSON {requestId}；捕获当前配置；首次入库与重新分块均使用此入口 | 202 JobView，Location和Idempotency-Replayed:false |
| GET /api/documents/{documentId}/ingestion-jobs | current/size/status；五种任务状态或省略；createdAt/id倒序 | 200分页 |
| GET /api/ingestion-jobs/{jobId} | job→document→知识库和可信tenant/owner校验 | 200 JobView |
| GET /api/ingestion-jobs/{jobId}/stages | current/size；按attempt和FETCH/PARSE/SPLIT/EMBED/PERSIST/PUBLISH排序 | 200分页 |
| POST /api/ingestion-jobs/{jobId}/retry | JSON {requestId}；仅FAILED/CANCELLED，创建新任务/新代并捕获当前配置，retryOf=原jobId | 202 JobView |
| POST /api/ingestion-jobs/{jobId}/cancel | 无正文；PENDING/RUNNING原子置CANCELLED，失效凭证；终态幂等返回原状态 | 200 JobView |

第3阶段文档下嵌套 `GET /api/documents/{documentId}/ingestion-jobs/{jobId}` 与 `/{jobId}/stages` 保持兼容，同样校验父文档匹配。提交/重试均快速持久化返回，不在HTTP内调用模型。

requestId必填8..128字符，正则 `[A-Za-z0-9][A-Za-z0-9._:-]{7,127}`，按可信tenant/owner唯一；与X-Request-Id（跟踪）和上传Idempotency-Key（上传幂等）独立。相同requestId+document+retryOf重放**同一个任务的当前状态**，200、Idempotency-Replayed:true、相同Location；即使原任务已失败或配置已变也不重建。相同键换document或retry源409/KB_IDEMPOTENCY_CONFLICT。一个文档有PENDING/RUNNING（包含退避中）时其他键409/KB_JOB_ACTIVE，message含当前jobId。重复领取/投递不会启动第二次执行。错误归属/已删除父资源404；未认证401；未知字段/自传tenant、claimToken等400。

重新分块：PATCH文档完整ingestionSpec（带最新version）→确保没有活跃任务（必要时cancel）→POST新的requestId。自动重试使用同一job与原快照，attempt增加且目标代不变；人工retry使用新requestId，创建新job和单调新generation，读取当时文档版本与配置。成功任务不能retry（409/KB_JOB_NOT_RETRYABLE）；希望重新入库时使用文档提交接口。

JobView：id/requestId/documentId/documentConfigRevision/targetGeneration/jobType(INGEST)/status/stage/attempts/maxAttempts/createdAt/startedAt/completedAt/nextRunAt/leaseUntil/errorCode/errorMessage/aiConfigRevision/retryOf。状态为PENDING、RUNNING、SUCCEEDED、FAILED、CANCELLED。attempts初始0，默认maxAttempts=3；startedAt为首次领取时间（尚未领取null），completedAt终态非null；PENDING的nextRunAt为可领取时间，RUNNING的leaseUntil可变；终态清空lease。旧V3终态审计记录可保留原lease值。stage为当前/最后阶段，不虚构进度百分比；pending初始FETCH；历史attempt的错误始终留在stages中。leaseOwner/claimToken/完整输入快照不对外暴露。

```json
{"requestId":"ingest-20261022-001"}
```

```json
{"code":"0","message":"OK","data":{"id":"b167deae-568d-4270-a13e-ff734e43ce9c","requestId":"ingest-20261022-001","documentId":"527b17ec-d340-4964-9034-9c03b24116eb","documentConfigRevision":2,"targetGeneration":2,"jobType":"INGEST","status":"PENDING","stage":"FETCH","attempts":0,"maxAttempts":3,"createdAt":"2026-10-22T12:05:00Z","startedAt":null,"completedAt":null,"nextRunAt":"2026-10-22T12:05:00Z","leaseUntil":null,"errorCode":null,"errorMessage":null,"aiConfigRevision":null,"retryOf":null},"requestId":"http-demo-1"}
```

StageView：stage/attempt/status/startedAt/endedAt/durationMs/inputCount/inputUnit/outputCount/outputUnit/errorCode/errorMessage；status为RUNNING/SUCCEEDED/FAILED/CANCELLED，未得到结果为null，单位为objects/bytes/utf16_chars/blocks/chunks/texts/vectors/generations。消息固定脱敏且数据库最多512字符，不存内容、供应商原始错误、内部URL或凭据。

```json
{"stage":"EMBED","attempt":1,"status":"FAILED","startedAt":"2026-10-22T12:05:02Z","endedAt":"2026-10-22T12:05:03Z","durationMs":843,"inputCount":8,"inputUnit":"texts","outputCount":null,"outputUnit":null,"errorCode":"KB_MODEL_RATE_LIMITED","errorMessage":"Execution ended; inspect stage and errorCode. Source contents and provider details are omitted."}
```

执行失败发生在202之后，GET任务仍200，通过status/errorCode/stages判断结果。默认3次、指数退避和抖动（2s起、5m封顶）；租约过期可恢复同一任务，旧执行者不得写阶段/发布/覆盖状态。停止或重启进程不会丢任务。取消之后不会因为迟到的模型异常自动重试。

常见执行errorCode：可重试KB_MODEL_RATE_LIMITED、KB_MODEL_TIMEOUT、KB_MODEL_UNAVAILABLE、KB_STORAGE_UNAVAILABLE、KB_INGESTION_EXPIRED（过期接管）；不可重试KB_EMPTY_CONTENT、KB_STRUCTURE_TOO_LARGE、KB_SOURCE_INVALID、KB_MODEL_INVALID_RESPONSE、KB_MODEL_REQUEST_REJECTED、KB_MODEL_CONFIG_CHANGED、KB_SOURCE_CHANGED等。输入版本变化为CANCELLED/KB_INGESTION_INPUT_CHANGED；主动取消KB_JOB_CANCELLED。故障码供任务状态使用，不代表GET返回对应HTTP错误。提交阶段409另有KB_DOCUMENT_DISABLED/KB_MODEL_UNBOUND；数据库不可用503/KB_DATABASE_UNAVAILABLE。

检索通过G节公共接口只识别activeGeneration。旧代与失败目标代有持久化清理记录，清理状态和恢复SQL见RUNBOOK。原文件保留。F及H–I继续作为后续设计。

## F. Chunk 查询、独立启停与编辑（P5）

| 方法/路径 | 请求/语义 | 成功 |
|---|---|---|
| GET D/chunks | 分页；默认activeGeneration，可筛enabled/edited；管理视图默认含人工禁用记录 | 200分页 |
| GET D/chunks/{chunkId} | 默认仅active；历史保留代须显式generation且同owner/存活父 | 200详情 |
| PATCH D/chunks/{chunkId}/enabled | 幂等键；body={expectedDocumentVersion,generation,enabled} | 200，data={chunk,documentVersion,manualRevision,replacementJobId?} |
| PUT D/chunks/{chunkId}/content | 幂等键；body={expectedDocumentVersion,generation,content}；不可直接提交embeddingText/向量/edited标记 | 202 EDIT_CHUNKS job，新正文与新向量完整后切代 |
| DELETE D/chunks/{chunkId}?expectedDocumentVersion=N&generation=G | 幂等键；立即人工墓碑抑制，写override，再异步重建/清理 | 202 job |
| GET D/chunk-overrides | 分页，显示本人手工策略/未匹配原因，便于WAITING_REVIEW处理 | 200分页 |
| DELETE D/chunk-overrides/{logicalChunkKey}?expectedDocumentVersion=N | 幂等键；owner明确放弃指定人工策略；manualRevision+1，创建替代重处理任务；不接受隐式全部重置 | 202 job |

ChunkView包括id/documentId/kbId/generation/chunkIndex/logicalChunkKey/content/embeddingText/contentHash/charCount/tokenCount/enabled/edited/metadata/createdAt/updatedAt；tokenCount未知为null，估算带estimated属性；默认不返回embedding数组。metadata只含有依据的位置，示例：

~~~json
{"chapterPath":["安装","本地部署"],"sourceAnchor":"chapter:install/local","parserVersion":"markdown-ast-v1"}
~~~

上述页码只有存在真实分页信息的来源才返回；普通Markdown没有页码时省略pageStart/pageEnd。表格示例为sheetName/cellRange/rowStart/rowEnd，不拿文件字节偏移冒充页码。

文档enabled与Chunk.enabled独立：PATCH D {"version":N,"enabled":true} 不能执行批量启用Chunk。人工启停写override且增加manualRevision；新代必须继承匹配策略。对旧代Chunk操作返回409/KB_GENERATION_CHANGED，提示重新读取；重建时有未匹配策略返回/进入WAITING_REVIEW，用户明确移除指定override后才能重新发布。更改source/ingestionSpec不自动丢弃人工编辑。

## G. 结构化知识检索（已实现）

`POST /api/knowledge/search`，Content-Type: application/json。200的data为证据数组，零命中为[]。使用既有Basic与可信tenant/owner归属；无写入幂等参数，X-Request-Id仅跟踪。前轮设计的/api/kbs/{id}/search未实现，以本轮统一入口为准。

```json
{"query":"PostgreSQL connection","kbIds":["8ad83093-1284-4f37-b478-2b2e967530ef"],"documentIds":["527b17ec-d340-4964-9034-9c03b24116eb"],"topK":5,"minScore":0.7}
```

| 参数 | 约束 |
|---|---|
| query | 必填非空文本，strip后默认≤4000 Unicode code point；拒绝NUL和未配对代理字符；还受模型编码字节预算限制 |
| kbIds | 必填非空UUID数组，唯一、无null，默认≤16；每个库均须归属且未删除 |
| documentIds | 可选；省略/null表示所选库全部文档；提供数组时非空、唯一、无null，默认≤100；每项须归属、未删且属于所选库 |
| topK | 可选整数，省略/null为5；1..服务端配置上限（默认50，最高200） |
| minScore | 可选有限数值[-1,1]；省略/null无阈值；不是概率、百分比或跨模型分数 |

服务端上限配置见RUNBOOK，不能在请求中覆盖。未知字段（tenant、operator、provider、namespace、vector、任意SQL/metadata过滤、mode、rerank等）400。权限校验覆盖所有请求项，越权/不存在404，不进行静默截断；自有文档不在所选知识库中400/KB_SEARCH_DOCUMENT_SCOPE。禁用但归属内的文档可作为范围，结果会被过滤。

一次请求只接受相同modelId/provider/modelName/dimension/embeddingSpace/configVersion/embeddingConfigRevision且已发布AI修订相同的知识库；相同维度不代表可混用。不兼容409/KB_SEARCH_MODEL_MISMATCH，向量化前拒绝，须分请求查询。本轮没有排名融合；兼容多库只生成一次查询向量和执行一次全局排序。空库可与相同绑定的已发布库一起查；全空且没有AI修订时直接[]、不调用模型。未绑定模型409/KB_MODEL_UNBOUND；服务端已钉住修订与当前AI不符409/KB_MODEL_CONFIG_CHANGED。

查询向量与索引一致使用public.<=>余弦距离；score=1-distance，可能为负，浮点边缘钳制[-1,1]，scoreType固定COSINE_SIMILARITY。按距离升序、相同距离chunkId升序；不是概率、BM25或精排分数。未设置minScore时会返回最近邻，即使主题无关也可能有结果；需要相似度下限时明确设置minScore，并根据真实模型评估阈值。

```json
{"code":"0","message":"success","data":[{"chunkId":"7c84e183-ac4c-4a4d-a33d-a4141c966f22","kbId":"8ad83093-1284-4f37-b478-2b2e967530ef","documentId":"527b17ec-d340-4964-9034-9c03b24116eb","documentName":"database.md","chunkIndex":0,"generation":1,"content":"PostgreSQL connection reads KB_DB_URL from the environment.","score":1.0,"scoreType":"COSINE_SIMILARITY","sourceMetadata":{"chapterPath":["Operations","Database"],"blockType":"PARAGRAPH","sourceBlocks":[{"lineStart":5,"lineEnd":5,"blockIndex":2}]},"fileUrl":"/api/documents/527b17ec-d340-4964-9034-9c03b24116eb/file"}],"requestId":"search-1"}
```

上例说明字段；真实临时测试库的完整输入、输出与数据来源见[SAMPLE_SEARCH.json](SAMPLE_SEARCH.json)。sourceMetadata为白名单解析来源，TXT/Markdown无页码不返回虚构pageNumber；有真实页码/章节/表格/字符范围时保留。fileUrl是需重新授权的原文接口，不返回objectKey、存储密钥、永久公开URL或embeddingText。documentId/chunkId/generation/chunkIndex定位本次证据；原文可按章节/行号查看，Chunk管理路由将在后续阶段提供。

SQL在排序、minScore与全局LIMIT之前过滤指定KB/document、父墓碑、可信tenant/owner、doc.enabled/chunk.enabled、activeGeneration与完整索引身份。保留旧activeGeneration的PENDING/FAILED文档仍可检索。模型调用结束后，重新授权KB并用SQL语句快照过滤；切换代/禁用/删除在模型等待期间生效，结果不包含旧快照。发送之后发生的新变化无法追溯撤回响应。

本轮为同库精确查询，不依赖外部向量回表或近似候选池，因此无“过滤后不足topK”的补召回缺口；有效证据不足或minScore过滤时可自然少于topK。模型/数据库失败明确返回错误，不当成无命中。

| HTTP | code / 原因 |
|---|---|
| 400 | KB_INVALID_INPUT；无效query/UUID/重复或空列表/topK/minScore/未知字段；KB_SEARCH_DOCUMENT_SCOPE |
| 401 / 404 | KB_UNAUTHENTICATED / KB_NOT_FOUND或KB_DOCUMENT_NOT_FOUND；含跨用户、跨租户、已删资源 |
| 409 | KB_SEARCH_MODEL_MISMATCH / KB_MODEL_UNBOUND / KB_MODEL_CONFIG_CHANGED |
| 422 | KB_EMBEDDING_INPUT_TOO_LARGE；模型编码字节预算不足 |
| 502 | KB_MODEL_INVALID_RESPONSE / KB_MODEL_REQUEST_REJECTED |
| 503 | KB_MODEL_UNAVAILABLE / KB_MODEL_RATE_LIMITED / KB_DATABASE_UNAVAILABLE |
| 504 | KB_MODEL_TIMEOUT |

本接口只返回数据库中的检索证据，不调用LlmClient生成答案。混合检索、精排与排名融合留待后续阶段，不将其他分数冒充余弦相似度。

## H. URL导入、条件刷新与计划（P7）

| 方法/路径 | 请求/语义 | 成功 |
|---|---|---|
| POST K/imports | 幂等键；body={url,name?,ingestionSpec?}；只允许URL类型，默认功能关闭 | 202，data={document,fetchJob}；初始storageStatus=PENDING_FETCH，尚无可检索代 |
| POST D/refresh | 幂等键；body={expectedDocumentVersion}；仅URL，创建一次检查/下载任务 | 202 REFRESH job |
| GET D/refresh-schedule | 计划不存在404；不是完整任务历史 | 200 ScheduleView |
| POST D/refresh-schedule | 幂等键；body={expectedDocumentVersion,cronExpression,timezone,enabled}；已有计划409 | 201，version=1 |
| PUT D/refresh-schedule | 幂等键；body={version,cronExpression,timezone,enabled}，完整替换计划 | 200，schedule.version+1 |
| DELETE D/refresh-schedule?version=N | 幂等键；禁用并删除计划，不删除文档/Chunk | 200，data=null |

sourceUrl不接受userinfo/内置凭据，下载策略/限额见PLAN 4.4；URL策略拒绝422，超限413，功能未启用503。重定向、DNS/IP策略检查在真实连接层落实，不只做正则。sourceUrl/ETag/Last-Modified均不能由用户覆盖已接受验证状态。

REFRESH/fetch job只检查源并持久化候选对象，targetGeneration可null；有变化时在短事务内更新源对象/configRevision、将fetch job置SUCCEEDED(outcome=INGESTION_ENQUEUED)，并原子创建固定新输入的INGEST子job。返回/查询fetch job.result含childJobId，不把“下载完成”当作“可检索完成”。document.latestJob转为子任务；schedule validators与SUCCEEDED状态仅在子任务原子发布后确认。无变化且配置已发布则result.outcome=UNCHANGED，不创建无意义向量代。

若上传原文/配置已有失败待处理版本，即使304也应从已存对象创建新INGEST子任务；不能用304掩盖失败。URL首次导入走相同fetch→INGEST关系，原始哈希、MIME、大小在下载确认后填充，PENDING_FETCH时可null，不能预填伪值。

~~~json
{"expectedDocumentVersion":4,"cronExpression":"0 0 * * * *","timezone":"Asia/Shanghai","enabled":true}
~~~

ScheduleView含documentId/cronExpression/timezone/enabled/nextRunAt/lastRunAt/lastEtag/lastModified/lastContentHash/lastStatus/consecutiveFailures/pendingRefresh/version；不暴露可写leaseOwner/leaseUntil/claimToken。cron最小间隔60s、六段，next=null/无效时区400。时间响应用带偏移ISO8601，DB存UTC，DST与漏触发合并语义见PLAN；没有计划的普通上传文档不创建空计划行。

## I. 回答生成（P8，默认关闭）

POST K/answers，body={question,topK?,mode:"VECTOR"或"HYBRID",rerank?:false}；沿用G的权限/检索限制。首版非流式，不另造一套SSE；已有AI流式能力可在明确需求时增量开放。

200 data={answer,evidence,citations,model,usage,metrics}；citations只能引用本次受权evidenceId及chunkId/generation。零证据返回answer=null、status=NO_EVIDENCE，不能伪造引用；引用验证失败502/KB_MODEL_INVALID_RESPONSE。传给模型的文档文本为不可信上下文，不执行其工具指令；回答前后重新检查来源可见性。未返回真实usage时null，不估算计费。回答成功不保证事实正确，质量验收使用约定问题集；生产开放前做真实模型联调。

## J. 复用AI服务的内部契约（已实现的模型身份协议）

现有服务已实现POST /ai/embed、/ai/rerank、/ai/chat、/ai/vision；这是独立服务API，不应混入backend公共租户接口。当前网络入口无服务间认证过滤器，部署需受控内网/网关鉴权；不能转发用户Basic凭据。

第3阶段已新增GET /ai/embedding-models/{alias}?dimensions=1024，返回共享Result格式（无API key/内部URL）：

~~~json
{
  "code":"0","message":"success","data":{
    "modelId":"qwen-embed","provider":"bailian","providerKind":"BAILIAN",
    "modelName":"text-embedding-v4","dimensions":1024,
    "embeddingSpace":"bailian-text-embedding-v4","configRevision":"<AI配置 SHA-256>",
    "configVersion":"bailian-text-embedding-v4-1024-v1","batchSize":10,"active":true,"protocolVersion":"embed-v1"
  },"requestId":"profile-1"
}
~~~

不返回API key、内部URL或供应商原始认证信息。metadata描述配置，不自动做付费探测。本轮已扩展现有EmbedRequest，保留text/texts二选一和实际已有model/dimensions：

~~~json
{"texts":["部署 > 数据库\n通过KB_DB_URL设置连接。"],"model":"qwen-embed","dimensions":1024,"expectedConfigRevision":"<profile中保存的AI配置修订>"}
~~~

新增EmbedResult.provider/embeddingSpace/configRevision；model仍为实际逻辑别名，既有embeddings[{index,values}]/dimensions/usage/metrics不改名。configRevision为AI修订，与KB本地配置快照hash分别保存；metadata还校验modelName/configVersion。expectedConfigRevision不匹配时409/MODEL_CONFIG_CHANGED，KB禁止重试同一失配输入或忽略检查；显式model或expectedConfigRevision不启用fallback。已有不传该字段的AI调用保持兼容。本轮协议测试覆盖metadata与调用间配置变更、真实维度与非有限/零向量、原有供应商重排协议未被修改。
