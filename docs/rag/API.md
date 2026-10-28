# RAG API

本轮已实现 A；B/SSE 和 C 为后续设计约束，尚无这些新路由。可执行完整 OpenAPI 仍为现有 `/openapi/knowledge-base.yaml`，复用同一知识库服务和 Basic 认证。

## A：结构化检索（已实现）

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

`POST /api/knowledge/search` 使用相同请求和引擎，继续返回 `data: Evidence[]`。保留原三项状态头，新增 `X-KB-Retrieval-Status`、`X-KB-Retrieval-Outcome`；两个接口证据响应均 `Cache-Control: no-store`。新客户端优先使用 retrieve 读取完整状态。`GET /api/knowledge/search-capabilities` 继续仅说明配置能力，answerEnabled=false。

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

## B：Java 独立问答与 SSE（后续，未实现）

拟由同一检索引擎取得证据，再经过权限复核、字符/token 预算裁剪，调用既有 Java AI chat。查询理解采用经过 Jackson schema/字段/类型/长度校验的结构化输出；模型不能赋予授权范围。错误或超时是否使用原 query 必须明确状态，并遵守总调用预算。

模型可见上下文仅包含授权证据、局部引用 ID 及必要来源。系统规则明确文档为不可信资料，不执行其中指令。用户答案返回文本和可解析的引用映射，调试状态另行授权；引用存在、模型判断和分数阈值均不代表事实正确。

待实现 SSE 事件：meta（请求和版本）、evidence（授权引用）、delta（答案片段）、done（结束状态与引用）、error（受控错误码）。事件 schema、最终路由、心跳间隔和断流重连在 R3 定稿；当前不能调用这些事件。SseEmitter completion/error/timeout 必须取消 Future 和上游流；流输出后不整体重试，超时不把部分输出标为完成答案。

## C：Python Agent 检索工具（后续，未实现）

工具拟名 knowledge_search，参数为 query、已授权范围内 kbIds、可选 documentIds/topK/strategy；身份与服务凭据来自可信执行上下文，不能由模型参数传入任意 actor 或 Basic 密钥。工具调用现有 A，向 Python 返回受限证据、来源、分数、实际策略、status/outcome/requestId；内部 trace 和错误细节按模型可见白名单裁剪。

Python 继续持有 Agent 主循环、图检查点、取消和会话上下文。工具失败以受控错误交还 Agent，不包装成无证据；Agent 继续决策和组织回答。需在 R4 实现剩余预算传递、网络超时和取消传播，当前 Java 请求体没有可由模型控制的 deadline 字段。
