# RAG 服务计划

## 当前执行顺序（2026-10-31，按用户附件）

用户已授权按附件第 2–8 轮顺序开发并分别提交。下表取代下文历史 R2–R5 的分组编号；每轮完成定向验收与提交后再进入下一轮。

| 轮次 | 范围 | 状态 |
|---|---|---|
| 2 | 统一结构化检索、服务端范围、向量基线 | 已完成；默认/空范围、兼容路由，13 项向量定向测试通过 |
| 3 | 术语、受限查询理解、澄清与拆分 | 待实施 |
| 4 | 子查询多通道、全请求预算、RRF | 待实施；已有双通道引擎复用 |
| 5 | 精排门槛与明确故障策略 | 待实施；已有供应商客户端复用 |
| 6 | 证据选择、上下文预算与引用契约 | 待实施 |
| 7 | Java 独立问答与持久化引用 | 待实施 |
| 8 | MVC SSE、独立问答历史和取消 | 待实施 |

各轮使用下文开发期验证节奏；不自动扩大到附件之外的 Agent 实现或生产部署。

2026-10-28。以当前仓库代码和知识库交付为依据，按阶段交付；本轮范围为 R1 共享结构化检索加固。独立问答和 Agent 工具尚未实现，不将本轮称作完整生产 RAG 验收。

## 仓库核对与复用

已阅读 `docs/knowledge-base/{PLAN,PROGRESS,API,RUNBOOK}.md`，并核对实际代码。历史文档中的问答占位不是已实现接口，当前能力以 Controller 为准。

| 职责 | 已有实现与本次复用 |
|---|---|
| Java 基线 | JDK 17、Spring Boot 3.5.16；根 Maven reactor，`services/backend` 为 Spring MVC |
| 数据与迁移 | MyBatis-Plus、JdbcTemplate、Flyway V1–V8；本轮不改表或既有迁移 |
| 权限 | `KnowledgeBasePrincipal` 由服务端账号映射提供 tenant/user；`requireOwned` 验证每个库和指定文档 |
| 向量 | `KnowledgeSearchService`、`EmbeddingClient`、`AiEmbeddingClient`、`PgvectorVectorStore`；唯一 `kb_vector`，1024 维，精确 cosine |
| 混合检索 | `HybridRetrievalService`、`SearchChannel`、`RrfRerankPostProcessor`；同一个引擎服务现有两个入口 |
| 关键词 | Elasticsearch Java Client 8.18.8、可选 ES 8.19.17 / cjk / BM25；原 Outbox、修订与清理流程 |
| 精排 | `RerankClient` → `/ai/rerank`；供应商原生协议已在 AI 服务中实现 |
| 模型与 JSON | `services/ai-service` 已有 `AiService`、chat、chat/stream、embed、rerank；Jackson 已有严格反序列化配置 |
| HTTP | backend 模型传输已有 JDK HttpClient，远程文件已有 OkHttp；继续复用，不重复实现供应商适配 |
| 流式能力 | AI 服务当前为 WebFlux SSE；backend 尚无问答 SSE。后续在 MVC 使用 SseEmitter，复用 AI 流式协议，不迁移整个 backend |
| Agent | `services/agent-service` 只有占位；后续 Python 管理 FastAPI/LangGraph、图状态和上下文，Java 提供知识检索 |
| 测试 | JUnit 5、MockMvc、Testcontainers（独立 PG/pgvector、MinIO、可选 ES）和显式 HTTP 模型桩 |

未发现 LangChain4j / Spring AI，也没有新增此类框架的缺口。

## 统一架构与正确性边界

调用路径：认证 principal → 全范围授权 → 查询嵌入 / 并行 BM25 → 有效索引候选 → RRF → 再次回表 → 可选精排 → 再次回表 → 结构化证据。A 返回该结果；后续 B 在其上组装受限上下文并调用 Java AI chat；C 只调用 A 后交还 Python Agent。

库 ID 是检索范围，不是授权证明。未知、已删、跨 owner/tenant 资源按既有 404 规则处理，所有外部调用前完成全范围检查。SQL 和回表继续约束文档/Chunk enabled、墓碑、activeGeneration、模型配置与 AI 修订；精排前后都验证正文与有效修订。资料中的提示词或命令只作为正文，不能获得系统权限。

向量请求沿用入库绑定的 model/provider/name/dimension/space/local revision/AI revision。同一次 cosine 排序只接受一个兼容空间；不兼容请求仍 409，调用方须拆分。不会比较不同空间的原始分数。HYBRID 中向量失败可由关键词独立成功并明确报告；这不表示跨空间向量已经成功。

R1 的总预算从进入检索引擎开始，以 monotonic clock 计时，覆盖授权等待、召回、精排、最终复核。每阶段在原零队列、有界 ThreadPoolTaskExecutor 中运行，通道 deadline 与模型 HTTP deadline 取总预算剩余量的上限。等待方先处理最早通道 deadline；超时或调用线程中断取消未完成 Future。Embedding HTTP 和 ES 异步 Future 继续传播取消。没有新增应用层重试或缓存。

调用方响应等待有总上限；JDBC 被 interrupt 不等同于数据库语句已经停止，残留操作仍受现有 PG statement_timeout、连接池和 socket 超时约束，不能承诺数据库瞬间取消。Servlet 认证、请求体解析、序列化和网络发送不计入引擎计时。当前同步 MVC JSON 请求没有可靠的浏览器断连通知；B 阶段通过 SseEmitter 生命周期补齐断连取消，不能把线程中断测试当作断连验收。

结果区分 COMPLETE / DEGRADED，以及 EVIDENCE / NO_EVIDENCE / INDETERMINATE。NO_EVIDENCE 只表示本次配置策略未保留证据，不证明全库不存在答案。全部通道失败、总 deadline、数据库错误不会转换为空证据。版本指纹和 requestId 可追踪；本轮无生成提示词，不虚构 promptVersion。索引绑定在预检与返回前不一致时 409，避免错误标注追踪版本。

## 可见范围与缓存

| 消费方 | 允许的数据 |
|---|---|
| 当前用户 / A | 该身份授权的正文、白名单来源、受保护文件路径、分数、通道状态、脱敏版本与 requestId |
| 后续模型 | 模型调用前重新验证的授权证据子集和局部引用 ID；不发送账号、endpoint、原始错误或调试状态 |
| 运维 | 沿用 operator 指标和受控日志；operator 不获得其他用户的知识正文权限 |

本轮不引入结果缓存，证据响应 `Cache-Control: no-store`。未来若加入缓存，键至少包含 tenant/user、完整授权范围、query/document filters、策略和参数、embedding/AI/index/pipeline/prompt 版本；撤权、启停、编辑、删除、activeGeneration 和关键词 revision 变化必须失效，并在模型调用前重新检查。禁止 query-only 或跨用户结果缓存。

## 阶段与退出条件

| 阶段 | 范围 | 完成条件 / 当前状态 |
|---|---|---|
| R1 本轮 | 盘点、统一检索预算与取消、空结果/失败语义、版本追踪、兼容 API、五份文档 | 已完成；故障与权限回归、真实 PG/MinIO/ES 验证和独立打包结果见 PROGRESS |
| R2 后续 | Java 查询理解、受限证据组装、非流式独立问答 | 复用 `/ai/chat` + Jackson 校验结构化输出；模型生成 kbIds 只能缩小已授权范围；有限改写/调用预算；无证据和依赖失败分别处理 |
| R3 后续 | backend MVC SSE 问答 | SseEmitter、bounded executor、统一问答 deadline、断连取消、事件与引用契约、无整流重放；完成后单独结束 |
| R4 后续 | Python Agent 检索工具 | 仅包装 A，可信身份映射、预算传递、脱敏结构化反馈；不复制检索逻辑或接管 Agent 图/上下文 |
| R5 后续 | 外部依赖、效果与负载验收 | 固定数据集/版本、真实 embedding/rerank/chat、Recall/nDCG/引用和拒答人工评审、延迟与容量压测 |

后续契约草案见 API，未实现项明确标记。本轮结束后不自动进入 R2。

## 开发期验证节奏（2026-10-31 调整）

按用户要求减少开发过程中的测试，以覆盖本次改动风险的最小验证集合为准：

- 文档、注释等低风险改动做静态检查；Java 行为改动通过一次定向测试同时完成编译和验证，优先选择已有测试类或方法。
- 只有已有用例未覆盖新增的关键行为、异常或权限边界时才补测试，不为简单转换、目录移动或实现细节重复编写用例。
- 数据库、索引和外部协议集成测试按改动选择；涉及 SQL、授权过滤、有效版本或序列化映射时验证对应真实路径，其余开发迭代优先使用相关单元测试和 HTTP 桩。
- 已通过的检查在代码和影响范围没有变化时复用结果。阶段完成时集中执行受影响的验收用例，正常命中、空结果、故障和权限/有效版本等关键边界仍需覆盖，可复用既有测试。
- 全 reactor `verify` 仅在显式要求、发布流程要求或影响范围无法由定向验证充分覆盖时执行，不作为每次修改或每轮结束的默认步骤。
- 每轮记录实际执行、复用、跳过及未验证的范围；模型桩、真实外部服务和效果评估分别说明。

具体命令见 [运行手册](RUNBOOK.md)。现有测试继续保留，后续各轮按上述节奏选择验证范围。

## 技术依据

ES Java Client 8.18.8 的异步传输取消会传至 HTTP Future，依据[固定版本源码](https://github.com/elastic/elasticsearch-java/blob/v8.18.8/java-client/src/main/java/co/elastic/clients/transport/ElasticsearchTransportBase.java)。底层[HTTP 客户端实现](https://github.com/elastic/elasticsearch-java/blob/v8.18.8/java-client/src/main/java/co/elastic/clients/transport/rest_client/RestClientHttpClient.java)再转至 Cancellable；仅配置 socket timeout 无法代替本次总 deadline 和中断传播。
