# RAG API

已实现 A 和 B 的普通/流式问答、取消、运行及会话读取；C 尚待实施。契约见 `/openapi/knowledge-base.yaml` 和 `/openapi/rag.json`，复用知识库服务和 Basic 认证。

## A：结构化检索（已实现）

2026-10-31 第 2 轮：`POST /api/rag/retrieve` 是 `/api/knowledge/retrieve` 的兼容入口，执行同一个引擎。所有三个检索入口均允许省略/null `kbIds`，服务端取当前 tenant+owner 的存活私有库；超过 `kb.search.max-kb-ids` 返回 400/`KB_SEARCH_SCOPE_LIMIT`，要求显式缩小范围，绝不截断后偷偷检索部分库。显式 `kbIds: []` 返回 COMPLETE/NO_EVIDENCE、strategy=NONE、channels=[]，不会调用模型或索引。非空范围保持逐项授权，文档过滤必须属于已选范围。多嵌入空间仍为 409，调用方分别请求，不跨空间比较 cosine。

`POST /api/knowledge/retrieve` 返回统一 Result（code/message/data/requestId）。认证身份取服务端 principal；请求不接受 tenantId、ownerId 或 actor。所有 kbIds、documentIds 逐一验证，不静默丢弃越权库。

```json
{
  "query": "PostgreSQL 如何备份？",
  "kbIds": ["00000000-0000-0000-0000-000000000001"],
  "topK": 5,
  "strategy": "AUTO",
  "rerank": false
}
```

示例 ID 仅为占位。可选 documentIds 为非空、不重复 UUID 数组，且每个文档属于所选自有库。topK 默认 5，部署默认上限 50；库默认最多 16、文档 100、query 4000 Unicode 码点。未知字段、错误类型、重复 ID、空白/非法 query 按既有 400 规则拒绝。

strategy=AUTO|VECTOR|KEYWORD|HYBRID。AUTO 无关键词配置时实际 VECTOR，已配置时 HYBRID；配置不代表健康。显式 KEYWORD/HYBRID 在服务关闭时 400/KB_KEYWORD_DISABLED。minScore 仅适用 VECTOR 的有限 cosine [-1,1]；minRerankScore 必须启用精排且有限，不使用 RRF 代替精排阈值。不同嵌入空间请求 VECTOR 仍 409，须分别检索。

成功 data 字段：

| 字段 | 含义 |
|---|---|
| evidence | Chunk 列表：chunkId/kbId/documentId/documentName/chunkIndex/generation/content/sourceMetadata/fileUrl |
| score / scoreType | 纯向量为 COSINE_SIMILARITY；融合管线为 RRF。最终精排顺序不保证 RRF 降序 |
| fusionScore / rerankScore / channelScores | 融合、精排和原始通道分数分别返回；不可视作概率或跨空间直接比较 |
| requestedStrategy / strategy | 请求/默认策略；实际成功通道 VECTOR、KEYWORD、KEYWORD+VECTOR，成功但零命中仍算成功通道 |
| channels | [{channel,status:SUCCEEDED|FAILED|TIMEOUT,candidates,errorCode}]；仅受控错误码 |
| rerankStatus | DISABLED、SKIPPED_EMPTY、APPLIED、FALLBACK_FUSION、THRESHOLD_UNAVAILABLE |
| channelCandidates / fusionCandidates / topK | 实际漏斗预算，默认 50→20→5；纯向量无融合时均为 topK |
| refillLimitReached / minRerankScore | 关键词有界补召回是否耗尽，以及实际精排阈值 |
| status | COMPLETE 或 DEGRADED：通道失败/超时、精排回退/阈值不可用或补召回达到限制时降级 |
| outcome | EVIDENCE=有保留证据；NO_EVIDENCE=完整执行且无保留证据；INDETERMINATE=空结果同时存在依赖失败或召回不完整 |
| trace | requestId、pipelineVersion=retrieval-v1、elapsedMs、indexes、keywordIndexVersion、rerankModel |

trace.indexes 为本次已授权库的 {kbId,embeddingModelId,embeddingConfigRevision,aiConfigRevision}。未绑定/未发布修订可为 null；不代表该库一定产生了命中。返回前再次核对绑定，变化时 409/KB_SEARCH_SCOPE_CHANGED。keywordIndexVersion 是成功关键词通道的配置目标哈希，不是所有文档的已同步证明；最新文档修订仍按已有 PG Outbox/正文校验。rerankModel 为配置别名，不伪造供应商内部修订；DISABLED 时 null。正文、endpoint、namespace、密钥、原始异常不加入 trace。

NO_EVIDENCE 不保证没有相关资料；EVIDENCE、分数和来源定位也不保证资料或未来答案事实正确。

`POST /api/knowledge/search` 使用相同请求和引擎，继续返回 `data: Evidence[]`。保留原三项状态头，新增 `X-KB-Retrieval-Status`、`X-KB-Retrieval-Outcome`；两个接口证据响应均 `Cache-Control: no-store`。新客户端优先使用 retrieve 读取完整状态。`GET /api/knowledge/search-capabilities` 继续描述原知识检索接口，answerEnabled=false；该接口只返回证据，独立问答走下文 B 的 RAG 路径。

| HTTP | 语义 |
|---|---|
| 400 | 请求/范围/策略/阈值不合法，或显式使用未配置能力 |
| 401 / 404 | 未认证；未知、已删、跨用户/租户的资源，不透露其是否存在 |
| 409 | 未绑定、嵌入空间/修订不兼容、调用期间索引绑定变化 |
| 422 | 模型请求编码后超过既有输入预算 |
| 502 | 模型响应无效或永久请求拒绝；纯向量沿用原错误码 |
| 503 | KB_SEARCH_CAPACITY、KB_SEARCH_UNAVAILABLE、KB_SEARCH_CANCELLED 或原数据库/模型不可用错误 |
| 504 | KB_SEARCH_TIMEOUT（阶段或总 deadline）或既有 KB_MODEL_TIMEOUT；不返回伪造空列表 |

单通道失败仍有另一路成功时返回 200 + DEGRADED；全部通道失败返回 503。精排阈值因依赖失败无法应用时返回空 evidence + DEGRADED/INDETERMINATE，调用者不能将其理解为“无相关知识”。

## 第 3 轮：局部查询理解

`POST /api/rag/retrieve` 保留 query/kbIds/documentIds/topK/strategy/rerank/minScore/minRerankScore 的平铺输入，增加可选 conversationId。响应 `data` 为 `{status, query, retrieval}`；query 含 originalQuery/effectiveQuery/subQueries、实际缩小后的 kbIds、needsClarification/clarificationQuestion、degraded/fallbackReason、promptVersion/promptHash/termVersion/model。retrieval 为既有结构化检索结果；澄清时为 null。原 `/api/knowledge/search` 和 `/api/knowledge/retrieve` 保持原契约。

状态：READY 表示保留了证据，NO_EVIDENCE 表示成功检索未保留证据，NEEDS_CLARIFICATION 表示需要补充用户对象；降级空结果为 UNAVAILABLE，依赖错误继续使用 502/503/504，均不伪装为没有资料。查询理解的降级单独见 query.degraded。默认关闭模型改写；术语规则仍确定性执行。第 4 轮接入全部 subQueries 的受控召回。

普通 HTTP 入口固定 USER 模式，queryMode 不属于请求字段。内部 INDEPENDENT 模式不读取 Java 对话，留给 Agent 独立查询；身份不能由模型传入。Java 会话存储交付前，非空未知 conversationId 返回 404。所有库范围先验证，再读取同用户历史或调用理解模型；模型只可缩小范围。JSON 格式、字段、问题数量、ID/数字/否定词检查用于发现部分错误，不保证改写未改变语义。

机器契约：`/openapi/rag.json`，由 `python scripts/rag/build_openapi.py` 生成，知识库 OpenAPI 引用对应路径。

第 4 轮起，全部去重后的 subQueries 进入同一引擎，按子查询分批、每批通道并行，最多 3×2 次召回。`channels[].subQuery`、`evidence[].channelScores[].subQuery` 标明所属查询；每个查询/通道列表最多贡献一次排名。分数仍分别保留 cosine、BM25、RRF 和 rerank，不相加原始分。`channelCandidates` 为本次总额分配后每通道的实际上限，`candidateBudgetTruncated` 明确全请求候选预算是否压低召回量；它与外部补召回的 `refillLimitReached` 分开，截断导致的空结果为 INDETERMINATE。trace.pipelineVersion 更新为 retrieval-v2。单查询向量、未开启精排时继续保持原 cosine 分数语义。

## 第 5 轮：精排状态和故障策略

第 5 轮精排状态细分为 DISABLED、SKIPPED_EMPTY、APPLIED、BELOW_THRESHOLD、FALLBACK_FUSION、THRESHOLD_UNAVAILABLE。BELOW_THRESHOLD 表示模型真实评分成功，但本轮逐条丢弃低于 minRerankScore 的候选后没有证据；它不代表已证明库中没有答案。阈值未校准时默认不设置。`trace.rerankInputVersion=display-content-v1` 说明模型输入为展示正文，channelScores、fusionScore、rerankScore 继续分别保留。

精排故障模式由服务端配置：BEST_EFFORT 返回无精排分的融合结果并标记降级；如同时启用分数门槛，则无分结果全部剔除并返回 THRESHOLD_UNAVAILABLE/INDETERMINATE。REQUIRED 在超时、限流、协议错误等情况下返回 503/KB_RERANK_UNAVAILABLE；总 deadline 或中断继续按总预算错误传播。请求不能任意切换故障模式。

## 第 6 轮：证据与上下文

`/api/rag/retrieve` 的 data 增加 context：evidence、estimatedTokens、inputTokenBudget、estimated、truncated、strategyVersion、contentHash。每条证据包含本次 E1/E2 编号、kbId/documentId/chunkId/generation/chunkIndex、documentName、截断后的 content、白名单 sourceMetadata、授权 fileUrl、原正文 sourceContentHash 与展示快照 snapshotHash，以及 truncated、adjacent、parentChunkId、所属 subQueries 和可用的精排/融合分。

证据按引擎排序，先给各子问题一个选取机会，再受文档/子问题/章节或 sheet 来源配额限制。可配置当前代次的相邻块补充，最多一跳，占用同一条数和文本预算，adjacent=true，未冒充独立精排命中。RAG retrieval.evidence 同步为最终有效证据；邻居 scoreType=UNSCORED。旧知识检索入口保持既有 topK 契约。

长表格保留表头、分隔行和完整数据行；普通文本按换行或 Unicode 码点边界缩短并标 truncated。未合并相邻或重叠块，来源仍逐块可追踪。页码不存在时不增加该字段。用户视图不返回内部 modelContext 或系统提示词；模型视图只包含局部编号、资料正文、文档名和白名单定位，省略内部 UUID 和文件 URL。

全部资料、定位元数据、问题和授权历史计入输入预算；系统提示、输出和安全余量另外预留。计数复用 AI 的粗略估算并增加 25% 余量，同时用 UTF-8 字节总额约束输入；estimated=true，不能当作目标模型的精确 tokenizer 或计费。资料全部因预算不可用时返回 422/RAG_CONTEXT_BUDGET；快照变更或撤权导致不可用时返回 409/RAG_EVIDENCE_CHANGED，模型调用前重新验证。来源 fileUrl 每次下载仍执行当前权限与删除检查。历史答案的证据持久化在第 7 轮实现。

示例请求：`{"query":"PostgreSQL 备份","kbIds":["已授权库的 UUID"],"strategy":"VECTOR","topK":5}`。真实入库后的完整证据回执保存在验证构建副本的 `target/knowledge-base/rag-retrieve-example.json`；其中 sourceContentHash/generation 可解释当次来源，引用编号合法性不能证明资料支持答案断言。

## B：Java 独立问答（第 7 轮已实现）

`POST /api/rag/answer` 接受 `question`、可选 `conversationId`，其余知识范围、检索和精排字段同 retrieve。身份、USER 模式、模型和总耗时由服务端确定。新请求无 conversationId 时创建私有会话；未知或其他用户会话返回 404，同会话已有 RUNNING 返回 409/RAG_CONVERSATION_BUSY。

```json
{"question":"PostgreSQL 如何备份？","kbIds":["00000000-0000-0000-0000-000000000001"],"strategy":"VECTOR","topK":5}
```

响应为 Result，data 包含 answerId、conversationId、requestId、status、answer、citations、sources、unavailableSources、degraded、citationValidation、usage、modelCalls、query、retrievalTrace、evidenceContextHash、repairAttempted、errorCode。sources 是服务端证据快照；citations 为 evidenceId→documentId/chunkId/generation/snapshotHash，不接受模型生成文件地址。正文使用 `[E1]` 标记。

状态为 COMPLETED、NO_EVIDENCE、NEEDS_CLARIFICATION 或 CITATION_INVALID；无证据/澄清不调用生成模型。依赖不完整或失败按 5xx 返回，运行记录为 FAILED/TIMED_OUT，错误 data 带 answerId/conversationId/requestId。没有引用或未知编号最多用原证据修复一次，仍失败保留文本并标 CITATION_INVALID/degraded，不宣称成功引用。usage 只在单次生成调用且上游实际提供时汇总；多次调用逐条查看 modelCalls 的实际 usage，不伪造计数。

`GET /api/rag/runs/{id}` 返回当前用户的运行，含 status、answer（失败可为部分文本）、result 和 errorCode。`GET /api/rag/conversations/{id}` 返回最近 20 条，按新到旧排列。未授权资源一律 404。历史保留用户问题、有效查询、答案、证据快照、模型和提示词版本；来源在读取时再验证，失效正文从 sources 移除，相关引用编号列入 unavailableSources。保留本人历史答案文本不赋予原文件的下载权限。用于新模型调用的近期历史最多 6 条/3000 字符，失效来源对应的助手文本不重新送模。

中文模板和资料 JSON 分别放在 system/user 消息。所有消息纳入输入预算，生成前后重新检查证据权限、正文、来源元数据及有效版本。引用格式和编号校验不证明语义支持或事实正确。响应 no-store，不提供系统模板正文或内部模型消息。

## 第 8 轮：MVC SSE、取消与终态

`POST /api/rag/answer/stream` 接受同一个 AnswerRequest，返回 text/event-stream、Cache-Control:no-store、X-Accel-Buffering:no。POST 客户端使用 fetch/readable stream 或同等 SSE 解码器；每个 data 是独立 JSON，无 Result 外层。流开始前的认证、请求格式、未知会话、同会话并发和容量拒绝按 HTTP 返回；meta 发出后的检索/生成故障通过 SSE 收尾。

| event | data |
|---|---|
| meta | requestId、runId=answerId、conversationId、deadline |
| retrieval | phase=COMPLETED/FAILED、status=READY/NEEDS_CLARIFICATION/NO_EVIDENCE/UNAVAILABLE、degraded，正常检索时含实际 strategy |
| sources | 服务器选择的 EvidenceContext（含 evidence 和 contentHash）；无上下文为 {evidence:[]} |
| message | {text:答案增量}；一个引用可能跨多条增量 |
| error | requestId、受控 code 和通用 message，不透传上游正文 |
| finish | status、persisted、run；run 格式同运行 GET，result 含最终引用/模型/版本/usage |
| done | {requestId}，仅表示本次流结束 |

正常顺序为 meta→retrieval→sources→message*→finish→done。引用不合法或生成失败在 finish 前增加 error；检索失败为 meta→retrieval(FAILED)→error→finish→done，不发伪造无证据。澄清/无证据各发静态 message，随后以对应状态 finish，不调用生成模型。空闲写 SSE 注释心跳，不属于业务事件。

```text
event: meta
data: {"requestId":"req-example","runId":"00000000-0000-0000-0000-000000000007","answerId":"00000000-0000-0000-0000-000000000007","conversationId":"00000000-0000-0000-0000-000000000008","deadline":"2026-11-01T12:01:00Z"}

event: retrieval
data: {"phase":"COMPLETED","status":"READY","degraded":false,"strategy":"VECTOR"}

event: sources
data: {"evidence":[{"evidenceId":"E1","documentName":"备份说明","content":"使用 pg_dump 备份。","generation":1,"snapshotHash":"当次展示正文的SHA256"}],"contentHash":"当次上下文的SHA256"}

event: message
data: {"text":"可使用 pg_dump 备份。[E"}

event: message
data: {"text":"1]"}

event: finish
data: {"status":"COMPLETED","persisted":true,"run":{"answerId":"00000000-0000-0000-0000-000000000007","conversationId":"00000000-0000-0000-0000-000000000008","question":"如何备份？","status":"COMPLETED","answer":"可使用 pg_dump 备份。[E1]","result":{"status":"COMPLETED","citationValidation":"VALID","citations":[{"evidenceId":"E1","generation":1,"snapshotHash":"当次展示正文的SHA256"}]}}}

event: done
data: {"requestId":"req-example"}
```

示例为展示事件关系而省略其余来源/运行字段，完整结构以 OpenAPI 为准。客户端等完整正文后读取 finish.run.result.citationValidation；半个 `[E` 不提前判定。流式不执行引用修复，未知或未闭合 E 标记会得到 CITATION_INVALID/error；已收到的原文无法回滚。上游 EOF、error 或非 stop 不会结算为成功，隐藏推理不作为 message。

`POST /api/rag/runs/{id}/cancel` 无请求体，仅本人可用，返回 Result data={runId,conversationId,status}。重复取消返回已有终态；已完成的运行保持完成。取消写入持久状态，关闭本机流式 HTTP 并中断工作线程；另一实例收到取消时由每秒状态监控传播。取消/断连保存部分答案为 CANCELLED，截止或 HTTP 超时为 TIMED_OUT，均不会覆盖为完整成功。GET 运行/历史接口沿用第 7 轮权限与失效来源政策；失败或取消的 result 也保留可用的有效查询、证据哈希和模型请求/已开始信息。

finish.persisted=true 表示已读取数据库终态；持久化依赖失败会发 RAG_RUN_PERSISTENCE_UNAVAILABLE，finish.status=UNKNOWN/persisted=false，不声称保存成功。数据库 CAS 保证一次终态，不保证尾事件送达一次。断连可能收不到 error/finish/done，客户端用 runId 查询；默认断连即取消，不支持 Last-Event-ID 恢复或事件重放，不自动重发整个流式请求。崩溃遗留 RUNNING 在其持久 deadline 到期后清扫为 TIMED_OUT，不恢复生成。

运行 result 的 modelCalls.purpose=ANSWER_REQUESTED 表示配置模型已请求，ANSWER_STARTED 表示上游 start 已报告实际模型；两者不代表成功或已有 usage。成功结果为 ANSWER（必要时另有 CITATION_REPAIR）。运行列状态与 answer 部分正文优先于持久 result_json 检查点。来源/引用/模型版本可追踪不代表事实正确。

## C：Python Agent 检索工具（后续，未实现）

工具拟名 knowledge_search，参数为 query、已授权范围内 kbIds、可选 documentIds/topK/strategy；身份与服务凭据来自可信执行上下文，不能由模型参数传入任意 actor 或 Basic 密钥。工具调用现有 A，向 Python 返回受限证据、来源、分数、实际策略、status/outcome/requestId；内部 trace 和错误细节按模型可见白名单裁剪。

Python 继续持有 Agent 主循环、图检查点、取消和会话上下文。工具失败以受控错误交还 Agent，不包装成无证据；Agent 继续决策和组织回答。需在 R4 实现剩余预算传递、网络超时和取消传播，当前 Java 请求体没有可由模型控制的 deadline 字段。
