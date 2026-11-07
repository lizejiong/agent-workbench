# RAG 服务计划

## 2026-11-07：工具与引用的展示优化

复用现有 Web 时间线和授权证据，完成紧凑工具状态行、分层详情、引用预览、按知识库/文档/版本聚合与证据片段选择。组件映射和社区参考见 [CHAT_PRESENTATION.md](CHAT_PRESENTATION.md)。本轮限于展示层，不扩展检索、Agent 或审批能力。

## 2026-11-06：有序 Agent 时间线

Python 适配现有 LangGraph 事件，Java V12 持久化 blocks/eventSeq，Web 使用同一套时间线组件展示实时与历史。SSE 按请求头协商 v1/v2；v2 区分内容、工具、运行终态及连接结束。设计、社区参考与约束见 [TIMELINE.md](TIMELINE.md)。当前只读检索工具不引入无后端授权的确认按钮。

## 当前范围：Java 统一 Agent 会话（2026-11-05）

按用户明确边界实施 Java 会话账本、受委托历史读写、Python 原生 SSE 分批保存及 Web 历史恢复。架构和全部字段见 [CONVERSATIONS.md](CONVERSATIONS.md)。技术映射：backend/agent + JDBC/事务/Flyway V11；Python 复用现有 httpx、create_agent/LangGraph 和 Saver；Web 复用 ApiClient 与原代理，不新增 AI 框架或第二套检索。

阶段顺序为 Java 数据/权限/幂等 → Python 授权历史/流/取消 → Web 列表/分页/管理 → 定向验收与文档，本轮代码已覆盖这些范围。Python sessions.py 删除，业务历史由 Java 唯一管理；图按 runId 隔离并在结束/重启清理，不自动恢复旧操作。新回答继承已使用历史助手的证据依赖，撤权后不会通过派生消息绕过限制。

知识库范围在创建时固定，Java 独立问答仍使用原 V9 表。模型/工具/消息/证据预算、持久终态和断连窗口均明确记录。后续真实供应商、业务工具或生产扩容需另行验收，本轮结束不自动扩展。

以下保留此前阶段计划，历史“本轮范围”以对应日期理解。

## 当前执行顺序（2026-11-02 更新，按用户附件）

用户已完成第 2–9 轮，并于 2026-11-01 另行授权明确模块边界、接入真实 Python 主 Agent。本次将第 9 阶段适配示例提升为正式运行入口；不扩展业务 MCP、前端聊天或生产部署。下表取代下文历史 R2–R5 的分组编号。

| 轮次 | 范围 | 状态 |
|---|---|---|
| 2 | 统一结构化检索、服务端范围、向量基线 | 已完成；默认/空范围、兼容路由，13 项向量定向测试通过 |
| 3 | 术语、受限查询理解、澄清与拆分 | 已完成；中文版本化模板、结构校验、可信入口，4 项定向测试通过 |
| 4 | 子查询多通道、全请求预算、RRF | 已完成；单一引擎、查询/通道排名、候选总额、定向验证通过 |
| 5 | 精排门槛与明确故障策略 | 已完成；两种故障模式、弱证据状态、6 项定向测试通过 |
| 6 | 证据选择、上下文预算与引用契约 | 已完成；预算/来源/邻居及授权快照，4 项定向验证通过 |
| 7 | Java 独立问答与持久化引用 | 已完成；V9 运行/会话、中文模板、授权历史、引用校验与一次修复，3 项集成验证通过 |
| 8 | MVC SSE、独立问答历史和取消 | 已完成；真实 HTTP 增量/取消/断连、运行恢复，10 项定向验收及断连复核通过 |
| 9 | Java 内部证据契约与 Python Agent 适配 | 原备用交付完成；新增正式 FastAPI/LangGraph 主 Agent 已接入，真实 Uvicorn→Java→PG/MinIO 联调通过，模型为协议桩 |

各轮使用下文开发期验证节奏；本次另行授权的主 Agent 接入范围见下一节，不自动扩大到生产部署。

## 主 Agent 接入补充（2026-11-02，当前）

同日用户另行授权首版 Web：登录默认 chat、ChatGPT 风格交互、后台往返及接口缺口核对。已复用 Python JSON 问答和 Java 知识列表/下载，未扩大服务端会话/图范围；本次登录内的前端列表不宣称是持久历史。范围见 `docs/web/CHAT.md`，后续会话 API、流式与生产部署须另行执行。

新授权下在原占位 `services/agent-service` 实现 `agent_service.app:app` 和 `POST /api/agent/chat`。Python 复用 rag-common/FastAPI/httpx，使用官方 LangChain create_agent 创建 LangGraph 循环与 AsyncSqliteSaver；没有自行实现 ReAct。主模型由 Python 通过 ChatOpenAI 调原生工具调用协议，Java 继续提供知识、统一检索和独立问答。目录/接口/上下文边界见 `docs/ARCHITECTURE.md` 与服务 README。

现有适配器提升至 `agent_service/knowledge/adapter.py`，examples 只做兼容转发；中文知识提示片段也提升为唯一文件。工具 Schema 仅 query，身份/范围/短期委托/deadline/引用表在 ToolRuntime.context 注入，不进入模型或图检查点。会话首次由服务生成 ID，实际范围由 Java 委托结果绑定；每轮转交当前用户认证、重新签发委托，复核 Python 会话归属，完成/失败/取消后尽力撤销。

Python 自有 SQLite 保存会话归属/固定范围/运行租约，另由 LangGraph 保存图状态，不访问 Java 知识表。恢复输入先删除旧工具/助手消息，只保留至多 4 条、8000 UTF-8 字节的近期用户问题；敏感证据重新检索，不自动续跑取消的工具节点或复用旧答案。默认 60 秒、并发 4 且不排队、8 次模型/6 次工具、工具 4096 字节、输入消息+提示词与协议预留 60000 字节；SDK 重试关闭。

当前交付是可运行服务与真实图/Java 集成，不宣称真实模型效果、业务 MCP、人工暂停恢复、多主机检查点存储、Agent SSE、前端聊天或生产上线。后续必须另行授权。

## 第 9 阶段映射（2026-11-01，原备用交付记录）

仓库再次核对：agent-service 只有占位，python-common 有 FastAPI/httpx 约定而无 Agent 工具注册器或 LangGraph。新增 examples/integration/rag_agent，复用已锁定的依赖；不安装 LangChain/LangGraph、不实现 ReAct。Java `/internal/rag/retrieve` 只走原 RagRetrievalService 的 INDEPENDENT 模式与证据组装，Java 不读取/写入 Agent 历史或图检查点，Python 不直接访问知识表。

独立安全链用服务 ID + Bearer 校验服务身份。用户通过原 Basic 边界签发限时 opaque 委托，绑定服务、tenant/owner、宿主 session、库/文档范围；DB 只存令牌 SHA-256。每次加载当前用户并复用原范围授权，不能由服务、模型或请求头自称任意租户。V10 只保存委托、撤销与检索请求取消元数据，不是第二套 Agent 会话。委托默认 5 分钟、最多 15 分钟，当前所有权/启停/代次仍由原检索引擎检查。

调用元数据来自可信宿主运行上下文：独立 requestId、W3C traceparent、ISO deadline。trace 只用于关联；Java 在受控工作池传播 traceId 到 AI HTTP，deadline 取宿主、委托和服务器上限最小值。有界零队列内部工作池、DeferredResult 截止和显式取消接口传播 Future/HTTP 取消；DB 控制记录处理跨实例和取消先到。内部返回 Agent 白名单视图，不输出提示词、SQL、原始异常、内部地址或调试查询。

Python lifespan 复用 AsyncClient，query 为唯一工具参数；宿主注入身份/范围/委托与预算。工具状态明确区分、结果按 UTF-8 guard 限长，不调用 /answer。每次检索把 E 编号映射为请求唯一 K 编号，并保存可重新确认的来源线索；图恢复须由宿主先清理旧敏感消息、注入新委托再精确确认。提供中文版本提示片段，实际 Agent 模型/图与业务工具仍由宿主管理。真实调用回执与未验证边界见 AGENT_INTEGRATION_VALIDATION.json。

## 第 7–8 轮技术映射（2026-11-01）

`RagAnswerService` 管理有界交互任务，`AnswerPipeline` 统一非流式和流式问答；仍通过 `RagRetrievalService` 共用第 2–6 轮的检索和证据，中文模板集中在 PromptCatalog。`AnswerLedger` 使用原 JdbcTemplate/Flyway V9 保存独立问答会话、运行、有效查询、来源快照与结果，网络调用不持有数据库事务。用户/租户来自认证，同会话 RUNNING 唯一约束拒绝并发，历史助手文本复用前重新验证来源。

backend 保持 Spring MVC，`RagStreamController` 使用 SseEmitter；正文增量来自 AI 现有 `/ai/chat/stream`，通过已存在的 OkHttp 依赖适配 start/content/usage/done/error。推理内容不向用户转发；完整 stop 事件才允许成功。AI 并发许可与原 JSON 模型调用共享。读写空闲受 kb.ai.call-timeout 限制，整流 call timeout 取问答剩余预算，并注册 Call.cancel。取消也中断共享检索等待与原 JDK 异步 HTTP。

问答工作池和 SSE 写池各为固定、有界零队列执行器，默认各 4；单流事件队列 32，慢客户端背压受 deadline 限制。独立写线程每秒空闲心跳，断连默认取消，不做内存重放或断线继续。每秒监控本机任务的截止时间、跨实例 DB 取消状态及部分文本，按持久 deadline 清扫崩溃遗留运行。状态 CAS 只允许 RUNNING 成功写回，取消后的旧调用不能覆盖终态；恰好一次指数据库终态结算，不承诺网络尾事件恰好送达。

失败和取消保存已取得的证据与版本信息；本人运行读取仍过滤当前失效摘录，列状态和部分正文为权威，result_json 可为最近一次检查点。实时模型正文已发出时不做引用修复或模型切换重放，完整答案校验引用，不合法保留原文本并给出 CITATION_INVALID。JSON 普通问答仍允许一次原证据修复。暂无结果缓存、Agent 服务实现、真实供应商效果或生产压测验收。

以下保留 2026-10-28 的 R1 仓库核对与历史设计；后续交付以本文开头的执行表、技术映射和 PROGRESS 为准。第 2–8 轮已实现独立问答，Agent 工具与真实供应商/生产负载验收仍不在本次交付范围。

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
