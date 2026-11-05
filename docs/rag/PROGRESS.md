# RAG 进度

## R1：共享检索引擎加固（2026-10-28，已完成）

本轮范围：核对知识库交付，复用现有结构化检索，补齐预算、取消、失败语义与版本追踪。B 独立问答、SSE、C Python 工具及查询理解尚未实现；本轮结束后不自动进入后续阶段。

### 实际改动与决定

- 已阅读知识库四份交付文档并核对 Controller、检索/权限/索引/模型源码。保留 JDK17、Boot3.5.16、模块、MyBatis-Plus/JdbcTemplate、Flyway V1–V8、pgvector、ES Java Client、Jackson 和既有测试约定，无新增框架或第二套索引。
- 复用 `HybridRetrievalService`、原 search/retrieve 路由和向量通道。新增 `RetrievalDeadline`；授权等待、通道、精排/组装和最终版本复核共用总预算；纯向量也进入原有界、零队列执行器，保留 cosine 分数和模型错误码。
- 向量通道将调用方 deadline 传入 embedding，不再重新开始计时；精排取剩余预算上限。先处理最早通道 deadline，异常退出在 finally 取消剩余 Future；调用线程中断传播取消。ES 查询及首次 schema 验证改用同一 Java Client 的异步传输，Future 取消传到 HTTP。
- `KB_RETRIEVAL_DEADLINE` 明确总预算，兼容旧 `KB_SEARCH_MODEL_DEADLINE`。成功证据响应 no-store，未新增应用结果缓存或应用层重试。
- retrieve 新增 status/outcome/trace；search 保持数组并新增对应状态头。区分完整空结果和降级空结果，全部失败仍为错误。trace 含已授权库的模型/索引修订与 requestId；绑定变化时 409，避免错标版本。
- 记录用户、模型、运维三种可见范围；模型证据复用精排前后回表规则。未生成答案或声称阈值/引用保证事实正确。
- 建立 PLAN、API、RUNBOOK、EVALUATION 和本进度文档；机器 OpenAPI 同步扩展。历史知识库交付保留，并链接本轮增量。

### 验证结果

详细回执见 [R1_VALIDATION.json](R1_VALIDATION.json)。本轮共覆盖 269 项独立测试：257 通过、0 失败、0 错误、12 可选独立数据库用例未配置而跳过；Testcontainers 的 PG/MinIO/ES 用例实际执行，并非整体跳过集成测试。

| 验证 | 实际结果 |
|---|---|
| 首批检索定向回归 | 50 项通过；真实 PG/pgvector、MinIO、ES 8.19.17/cjk，模型为显式 HTTP 桩 |
| 根 reactor 全量测试 | 267 项，255 通过、12 LocalPostgresTest 可选跳过；包括全部公共组件、AI、知识库、维护、混合检索和刷新 |
| 新增向量 deadline 回归 | 2 项在隔离 reactor 中通过；验证真实向量通道向 EmbeddingClient 传递外层 deadline，过期请求不调用模型/索引 |
| 预算与取消 | 授权等待、纯向量错误兼容、短通道先取消、总预算限制组装、并发容量拒绝、所有未完成任务中断、索引绑定变化 |
| 模型前后权限 | 撤权证据不进入精排；精排等待后再次过滤；跨用户/租户、启停、删除、旧 generation 保持既有实际回归 |
| HTTP 取消 | 真实本地 HTTP 传输下 embedding 中断释放许可；ES 首次 schema 检查超时、中断与锁释放；不是供应商质量验收 |
| OpenAPI / Compose | 41 路径、52 操作、421 引用解析通过；web+keyword 配置检查通过 |
| 最终打包 | 隔离目录全 reactor verify 成功；252 个构建输入与工作区逐字节/哈希一致，未重复跑已经通过的全量测试 |

根目录 verify 在所有测试通过后，因 Windows 锁定运行中 JAR 而于 repackage 阶段失败，不能记成根目录 verify 成功。该打包过程也留下了薄 JAR；已从已校验 SHA-256 的历史 stage10 产物恢复本地 backend/AI 运行包，并用原忽略配置与既有 launcher 恢复两个本地进程。两个 health 均 UP，授权 capabilities 和知识库列表读取均 200；未执行资料写入、数据清理或迁移变更。后端恢复包是 stage10 已验证快照，其后既有整理仅移除无用 import；本轮新检索实现没有激活到当前运行实例。

本轮可运行新包单独保存为 `services/backend/target/rag-backend-0.1.0-SNAPSHOT-rag-r1.jar`，SHA-256：`8684c3e38adc11c8d3f2e935d806809952167017da69aa1afdec13c919219845`；独立构建副本在 `.cache/rag-r1-build/services/backend/target/`。二进制与缓存按原 Git 规则忽略。后续构建须在隔离 checkout/目录运行，避免覆盖运行中的 target。

### 限制与下一步

本轮未进行真实供应商模型验证，也未激活 R1 新功能；本地进程因上述产物恢复而重新启动。HTTP/线程取消不等同于数据库立即停止；现有 PG statement_timeout、连接池和 socket 超时约束残留资源。同步 MVC JSON 的浏览器断连尚无实时取消通知；SSE 断连取消留在 R3。

不同嵌入空间仍显式拒绝同次 cosine 排序；调用方拆分请求。rerank 仅追踪既有配置别名，当前 AI 协议没有不可变精排供应商修订。关键词目标哈希不代替每文档同步修订。无真实业务语义指标、生产负载或答案效果验收。

下一轮 R2：复用已有 Java `/ai/chat` 和 Jackson，增加受限查询理解、模型前复核的证据组装及非流式独立问答；继续共用现有检索引擎。R2 完成后再单独开展 SSE 与 Python Agent 适配。

## 结构重构回归修补（2026-10-31）

本轮按代码审查结果做小范围修补，保留现有业务分包、Service/BO/VO 和内部实体访问契约。

- WebFlux 公共异常处理保留 Spring `ErrorResponseException` 的 HTTP 状态与协议响应头；4xx/5xx 分别使用应用配置的参数/内部错误码与提示，不输出原始异常原因。通过 WebTestClient 的 WebFlux 处理链测试覆盖不支持的媒体类型 415、显式 404/503、requestId 和自定义错误契约。
- 表格单元格统一处理 LF、CRLF、CR 后转为 `<br>`，不再残留回车符；保留反斜线和竖线转义。新增表头与正文的单元用例，并通过 CSV 上传、子进程解析、入库与向量发布验证三种换行。此问题在结构重构前已存在。
- PostgreSQL UUID/JSONB 类型处理器移到 `infra/persistence`，更新 MyBatis 扫描配置和知识库、文档、切片实体引用；移除这些处理器及表格渲染方法中的重复说明性注释。目录与公共 Web 文档同步更新。

隔离源码副本 `.cache/structure-fix-20261031` 的全 reactor `verify` 成功（含可执行 JAR 打包）：共 292 项，280 通过、0 失败、0 错误，12 项 `LocalPostgresTest` 因未配置专用数据库而按原条件跳过；Testcontainers 的 PostgreSQL/pgvector、MinIO 和 Elasticsearch 集成测试实际执行，模型调用使用 HTTP 桩。新增 5 项回归全部通过，其中 CSV 三种换行在同一集成用例内分别验证。425 个构建副本文件与工作区经过 SHA-256 核对。

验证命令：`./mvnw.cmd -B -ntp -f .cache/structure-fix-20261031/pom.xml -Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository -Dkb.test.elasticsearch-image=atlas-rag-elasticsearch:latest verify`。日志保存在 `.cache/structure-fix-20261031.log`，逐测试集汇总为 `.cache/structure-fix-20261031/.validation-summary.json`。

构建没有覆盖当前运行中的 JAR。本轮不扩展 R2，不执行运行包部署或真实供应商模型效果评估。

## 开发期测试节奏调整（2026-10-31）

按用户要求减少开发过程中的测试。后续优先复用已有定向用例，仅为未覆盖的关键行为和边界补测试；集成测试按数据库、权限、索引或外部协议的实际影响选择，阶段末集中验收受影响范围。已有通过结果在相关代码未变化时复用，全 reactor `verify` 改为按显式要求、发布流程或实际影响范围触发。

PLAN 和 RUNBOOK 已记录选择规则、单类/单方法命令和结果报告边界。此轮仅调整文档，执行 `git diff --check`，没有运行 Maven、Docker 或模型调用；附件中的后续功能阶段尚未在本轮实施。

## 附件第 2 轮：结构化向量检索基线（2026-10-31，完成）

复用现有单一 pgvector、入库模型绑定、证据 BO/VO、总 deadline、参数化 cosine SQL、权限与有效代次检查。新增 `/api/rag/retrieve` 兼容入口；省略范围从可信 tenant+owner 解析，默认范围超额明确拒绝，显式空范围返回 NONE/NO_EVIDENCE 且不调用外部依赖。指定范围逐项授权，保留不同空间 409 的既有政策。

最小验证：隔离副本 `.cache/rag-development-20261031` 执行 `-pl services/backend -am -Dtest=KnowledgeSearchTest -Dsurefire.failIfNoSpecifiedTests=false test`，13 项全部通过，0 跳过。真实 PG/pgvector、MinIO、解析入库，查询向量为 HTTP topic 桩；覆盖正常/无关、默认/空/超额范围、跨用户与租户、启停/删除/旧代次、不同空间、维度错误、模型超时。基线回执为该副本的 `services/backend/target/knowledge-base/search-example.json`；日志 `.cache/rag-round2.log`。复用先前预算/HTTP 边界结果，未运行全量 verify 或真实供应商效果评估。

## 附件第 3 轮：受限查询理解（2026-10-31，完成）

RAG 门面在既有共享引擎之前执行范围预检；USER 从授权 Java 历史接口取上下文，INDEPENDENT 不加载历史，queryMode 不进入普通请求契约。复用已有 AI HTTP 传输增加 ChatClient；理解默认关闭，可配置确定性术语表，JSON 输出校验字段、类型、数量、去重、硬标识/数字/否定词和知识库子集。预算内失败回退规范化查询并明确降级，澄清状态不调用检索。模型查询只用于知识问题，不执行任务。

按用户补充要求，系统提示词改为中文、独立 UTF-8 模板，使用 PromptCatalog 和服务端配置按版本/受控目录加载，保留版本及内容 SHA-256。query 同时追踪术语映射修订与实际模型；提示词、资料和用户输入分离。目录 README 说明维护方式，新增独立 RAG OpenAPI 生成器及契约。

最小验证：`QueryUnderstandingServiceTest` 最终 4 项通过，0 跳过，日志 `.cache/rag-round3-verified.log`；覆盖术语/编号/否定/数量、拆分去重、澄清、错误 JSON、超量、越权、超时及授权顺序。开发中修复了测试桩重复配置引发的测试错误。使用模型输出桩；未进行真实模型语义评估。当前 Java 会话为空实现，非空未知 conversationId 返回 404，实际历史存储在第 7/8 轮补齐；全部子查询的召回在下一轮接入。

## 附件第 4 轮：多查询融合与总预算（2026-10-31，完成）

同一个 HybridRetrievalService 接入去重子查询；最多 3 个子查询分批执行，每批原向量/BM25 通道并行，复用原零队列有界执行器、HTTP 取消和总 deadline。默认 300 候选总额在调用前按查询×通道分配，融合/精排容量和最终 topK 仍独立。配置 record 的兼容构造器已显式标记 ConstructorBinding；真实启动回归发现并修复了该绑定问题。

RRF 按查询/通道列表计排名，同一列表重复候选只贡献一次，不按正文合并其他文档。channelScores 与 channels 保留 subQuery，保持原始评分；candidateBudgetTruncated 与 refillLimitReached 独立，降级空结果不宣称确定未命中。pipelineVersion=retrieval-v2。改写结果同时服从项目查询长度上限。

最小验证：预算/取消/重复查询/RRF 的 12 项单元回归与 2 项真实 PG/MinIO/ES 集成场景最终全部通过，日志 `.cache/rag-round4-verified.log`；查询理解 4 项在 `.cache/rag-round4.log` 中通过，相关代码后续未变化。输出纯向量、单查询混合、多查询混合对照到隔离副本 `services/backend/target/knowledge-base/fusion-baseline.json`。模型仍为 HTTP 桩，不报告真实语义提升；未运行全量 verify。

## 附件第 5 轮：精排门槛与故障策略（2026-10-31，完成）

复用既有 AiRerankClient 和供应商适配，按返回 index/chunkId 校验整组评分。候选不足 topK 仍请求评分。新增服务端 BEST_EFFORT/REQUIRED：前者保留无分融合或在阈值模式明确拒收，后者返回 KB_RERANK_UNAVAILABLE；总 deadline/中断不作为降级吞掉。启用要求与阈值冲突在配置构造时拒绝。逐条丢弃弱证据，全部弱时状态 BELOW_THRESHOLD，与无候选、未启用、模型失败区分。评分输入固定展示正文，trace 增加 display-content-v1。

最小验证：RerankDeadlineTest 5 项及 AiRerankProtocolTest 1 项共 6 项通过，0 跳过，日志 `.cache/rag-round5.log`。本地真实 HTTP 对 AI 协议的桩验证乱序、重复/越界、缺分、非法分、错 ID、空结果及 429；后处理覆盖撤权、deadline、少量候选评分、弱证据和两种策略。复用既有外部设施回归结果，未启动 Docker 或全量 verify。无真实供应商质量数据，门槛默认关闭，未声称分数证明事实正确。

## 附件第 6 轮：受控证据上下文（2026-10-31，完成）

RAG 门面共用 EvidenceAssembler，最终用户证据与模型材料均经过当前权限、启停、正文和代次复核。按文档/子问题/章节或 sheet 配额选择，保留本次 E 编号、原正文与展示哈希、来源定位及授权文件接口。可选一跳邻居受同一权限、代次和预算约束，明确标记补充来源、未独立精排；不合并重叠块，来源逐块保留。表格按整行收缩，文本按换行或 Unicode 码点裁剪并标记，循环服从 deadline。

计数复用 AI 文本工具 `/ai/utils/estimate-tokens`，每次非空上下文一次调用，加 25% 余量，并用包含问题、历史和定位元数据的 UTF-8 字节总额保守限制；系统/输出/安全储备另列。estimated 明确为粗估，不宣称目标模型精确计数。资料变更和预算不足明确报错，模型消息与系统提示词不出现在用户视图。

最小验证：EvidenceAssemblerTest 3 项和 KnowledgeSearchTest 的 RAG 端到端场景 1 项最终通过，日志 `.cache/rag-round6-verified.log`。真实 PG/MinIO 上传入库、邻居补充、禁用邻居排除、跨用户来源拒绝与删除后 404；模型和计数工具为 HTTP 桩。修正了样例包含多个有效邻居时对固定返回条数的错误假设，改为检查禁用块不能出现。回执位于隔离副本 `services/backend/target/knowledge-base/rag-retrieve-example.json`。未跑全量 verify、真实模型效果或生产负载。

## 附件第 7 轮：Java 独立问答（2026-11-01，完成）

新增非流式 answer 和本人运行/会话读取，共用既有检索、证据、ChatClient 与计数工具。中文问答和一次引用修复模板集中在 PromptCatalog，版本和 SHA-256 随模型调用落库；UTF-8 无效编码拒绝加载。READY 才生成，无证据与澄清直接响应，检索故障不会冒充资料不足。正文引用只认可本次 E 编号，最多一次原证据修复；仍不合法保留文本并显式 CITATION_INVALID。

Flyway V9 保存独立问答会话、运行和含来源哈希的结果；短事务与网络调用分开。私有会话 tenant+owner 校验，同会话并发 409；调用失败保留 FAILED/TIMED_OUT 和可用部分文本。历史来源重新授权并检查正文、元数据、代次，失效摘录不再次下发或进入模型；本人历史答案文本可见。Java 不接管 Agent 上下文。总 timeout、输出与上下文储备可配置；SSE/断连和主动取消留给第 8 轮。

最小验证：KnowledgeSearchTest 的 3 个新增方法全部通过、0 跳过，日志 `.cache/rag-round7-verified.log`。真实 PG/MinIO、V9 迁移与上传入库；Chat/计数为本地 HTTP 桩。覆盖答案引用持久化、跨用户/租户会话拒绝、撤权后来源/模型历史过滤、无证据不生成、一次修复与未知引用、模型超时、同会话并发，以及生成中禁用资料导致失败。隔离副本曾残留已删除的空历史实现，已清理其源文件和 class 后验证通过；不影响工作区运行产物。没有真实供应商语义/注入效果评估，消息分离和编号校验不等于事实正确保证；未执行全量 verify。

## 附件第 8 轮：MVC 流式问答、取消和恢复（2026-11-01，完成）

普通和流式问答共用 AnswerPipeline、检索与证据服务，新增 MVC SseEmitter 端点和本人运行取消。实际适配仓库 AI 的 start/content/usage/done/error SSE，使用已有 OkHttp 依赖、共享 AI 并发许可、读写空闲超时、总 Call timeout 及 Call.cancel。正文增量实时发送，推理内容不转发；缺失 done/stop 或上游错误保存失败，流式引用完整后校验，不修复或重放已发送文本。事件与来源哈希、模型/提示词版本、持久化终态均已文档化。

有界零队列问答池及独立写池默认各 4，单流 32 事件缓存、空闲每秒心跳。取消/断连中断检索等待和 HTTP；保存部分文本并以 CANCELLED/TIMED_OUT 结束。数据库 RUNNING 条件写回防止旧任务覆盖终态，每秒消费跨实例取消状态与持久截止时间，清扫崩溃遗留运行，不恢复生成。失败也保存已获得的有效查询、来源与请求/已开始模型信息。数据库结算未确认时 UNKNOWN/persisted=false，不将 done 当作成功。

最小验证：隔离构建 `.cache/rag-development-20261031` 的最终组合 10 项通过、0 失败/错误/跳过，日志 `.cache/rag-round8-verified.log`（KnowledgeSearchTest 新增 3 项流式场景、复用 3 项普通问答，QueryUnderstandingServiceTest 4 项）。真实 PG/MinIO、Spring MVC 随机端口、OkHttp socket 和本地模型 HTTP 桩：首个增量时运行仍未完成，跨帧 `[E1]` 合法，坏/未闭合编号显式失败，无证据不生成，检索依赖失败不假装空结果，上游 EOF/error/空闲超时不成功，跨用户取消拒绝、本人取消/断连真实关闭上游连接，持久截止传播和遗留清扫保留部分文本。

日志检查发现断连时既有 JSON 全局异常处理器会再次写 SSE 响应；已在流式 Controller 局部处理已提交 SSE 的 IOException，随后仅重跑受影响断连方法 1 项通过，日志 `.cache/rag-round8-disconnect.log`，不再出现重复写 JSON/Servlet 异常。另统一执行器拒绝为 503，并确保已建立运行先发 meta。OpenAPI 生成器输出 6 个 RAG 路径，两个契约文件 512 个引用解析通过，`git diff --check` 通过。测试结束关闭容器时的连接池清理告警不作为业务调用失败。

没有运行全 reactor verify、付费供应商、事实/注入语义效果评估或生产负载/慢客户端容量测试；未部署或覆盖本地运行 JAR。附件第 2–8 轮范围到此结束，不自动实施第 9 轮 Python Agent 或后续评估阶段。

## 第 9 阶段：Java 内部证据与 Python 工具适配（2026-11-01，备用交付完成）

按新授权检查仓库后确认 agent-service 只有占位，未安装 LangGraph/LangChain，无已有主 ReAct/图/工具接口。因此未创建新 Agent，增加 examples/integration/rag_agent 的 async query-only 工具、FastAPI lifespan、真实调用脚本、中文提示片段与说明；Python 不承接 RAG 算法，不调用 /answer、不直接写知识表，Java 不读写 Agent 图或历史。

新增 `/internal/rag/retrieve`，共用原 RAG 门面和证据组装并固定 INDEPENDENT；服务身份单独安全链与数据权限分开。用户通过原 Basic 签发短期随机委托，绑定服务/用户/租户/宿主会话/库与可选文档，DB 只存哈希；当前用户及原知识权限每次复查。支持本人撤销、范围收缩和过期。V10 的两张表只保存委托与请求取消控制元数据，没有 Agent 消息或第二套知识索引。

内部执行器有界零队列，deadline 受宿主、服务器与委托三方封顶。显式取消接口及数据库墓碑支持取消先到与跨实例传播，Future 中断传递到原 JDK HTTP；Python 取消 HTTP 后在 0.5s 内尽力通知，通知失败仍有 Java deadline。requestId 与 traceId 在 Python、Java、模型 HTTP 之间传播，内部 Agent 响应只含授权快照、真实分数、版本和受控状态，不透传 SQL/地址/异常。

Python 使用共享 httpx 连接池和可信上下文，工具参数仅 query；READY/NO_EVIDENCE/NEEDS_CLARIFICATION/UNAVAILABLE 与权限失败分别处理。每次局部 E 编号映射为全请求唯一 K 编号，注册表绑定用户/租户/会话，只允许实际取得的引用；工具视图按保守 UTF-8 guard 裁剪并区分 Java/工具哈希。图恢复示例用新委托精确确认当前 generation/hash，无法确认不回用旧摘录，宿主仍须清理旧敏感 ToolMessage。

验证：隔离目录 `.cache/rag-development-20261031` 的 Java 9 项通过、0 跳过（`.cache/rag-round9-java-verified.log`）；新增真实 Python→Java 联调 1 项通过、0 跳过（`.cache/rag-round9-cross-language.log`）。真实 PG/pgvector、MinIO 与 MVC 随机端口，Embedding/计数为 HTTP 桩；覆盖当前范围/版本、服务与用户委托、越权范围、未知会话字段拒绝、撤销、禁用资料、trace、取消先到/进行中取消及故障不冒充无证据。最初 DeferredResult 导包错误在编译时修复，最终组合通过。Python 4 项、ruff、FastAPI lifespan 的复用/关闭检查通过。OpenAPI 增加至 10 个 RAG 路径，引用静态解析通过；未运行全量 verify。

最后将 DeferredResult/HTTP 生命周期协调移到 controller/support，保持业务服务只处理原检索 BO；增加工作开始前的持久取消状态检查，覆盖任务登记与句柄注册间隙，委托预检查询也兼容最小查询长度配置。受影响的 3 项集成场景（含真实 Python 调用）复核全部通过、0 跳过，日志 `.cache/rag-round9-final.log`。OpenAPI 的两个文件共 561 个引用解析通过。

联调脱敏回执与依赖版本在 AGENT_INTEGRATION_VALIDATION.json；实际虚拟环境 Python 3.12.13、httpx 0.28.1、FastAPI 0.143.3、Pydantic 2.14.1。真实连续两次检索、来源渲染和引用映射已验证，业务工具仅只读结果桩；没有真实主 Agent 回答、LangGraph 工具注册/图恢复、业务系统或付费模型联调，未部署。宿主项目到位后按示例接入其既有工具/图，不将本次备用交付宣称为完整 Agent 上线。

## 主 Agent 接入补充：Java/Python 边界与正式入口（2026-11-02，完成）

用户另行授权明确模块边界，并把知识适配器接入真实 Python 主 Agent。重新核对工作区仍没有主循环，因此在原 services/agent-service 占位内落地 FastAPI 入口、官方 LangChain create_agent / LangGraph 与 AsyncSqliteSaver，不手写 ReAct；使用 Python ChatOpenAI 调支持 tools 的原生协议，不调用 Java /answer。Java 的知识管理、入库、检索、精排和独立问答实现保持原职责，生产 Java 源码和迁移未变；只增加一项跨语言契约测试。架构、目录、接口和上下文表已经更新。

知识适配器与中文提示片段提升到正式服务，examples 只做兼容导入，避免两份逻辑/提示词。工具 Schema 只暴露 query，可信 ToolRuntime.context 注入用户/租户/会话、范围、委托、deadline 和本轮引用表。每轮转交当前 Basic 账号给 Java 申请短期委托，由 Java 解析实际身份和范围；Python 验证持久会话归属与固定范围，再取得同会话运行租约。完成、失败或取消后尽力撤销委托；服务令牌不自动获得数据权限。

Python 自有 SQLite 管理会话和检查点，不访问 Java 知识表，也不将密码/服务令牌/用户委托放入检查点。会话重启先重新授权，送模前替换旧消息，仅保留至多 4 条、8000 字节近期用户问题；旧 ToolMessage/助手答案和未完成工具调用不会进入新请求或自动重放，所需资料重新检索。当前没有自动复用旧助手结论、摘要或人工中断恢复 API。

默认 60 秒总预算、每进程并发 4 且不排队、同会话租约、8 次模型/6 次工具、工具 4096 UTF-8 字节和输入消息+提示词/协议预留 60000 字节。共享 HTTP 池由 lifespan 创建/关闭，SDK 重试关闭，断连和超时取消图/HTTP并尽力通知 Java；未知/未闭合引用失败关闭，不重放整轮。主模型与 Java 调用传播同 traceId，用户仅返回受控状态与实际引用来源。API 使用严格请求/响应模型，动态 OpenAPI 与 Java 契约分开。

最小验证：Python 正式服务 6 项 + 原适配器 4 项合并执行，10 通过、0 失败/跳过，日志 `.cache/agent-runtime-python-20261102.log`；覆盖真实图工具调用、多次引用、四种状态、错误脱敏、身份/范围/会话、重启上下文、池关闭、同会话并发、deadline 和 HTTP 断连取消。Windows 默认 pytest 临时目录无访问权限，改用工作区 .cache 的独立 basetemp。ruff 和 uv 锁文件检查通过；uv build 的源码包/wheel 成功，确认两个中文提示模板已包含在 wheel 中。

Java 隔离副本仅运行新增 `pythonMainAgentRunsGraphAgainstJavaWithUserDelegationAndValidatedSources`，1 通过、0 跳过，日志 `.cache/agent-runtime-java-20261102.log`。真实 Uvicorn TCP → Python 图 → Java MVC → PG/pgvector/MinIO；两次检索 READY、最终来源编号互不冲突、trace 跨 Python/Java/模型 HTTP 关联，委托已撤销，Java 独立问答表未新增记录。主模型和向量模型仍为明确 HTTP 协议桩，未调用真实供应商。回执和锁定版本见 AGENT_RUNTIME_VALIDATION.json；未运行全量 verify，没有覆盖运行 JAR。

当前范围到此结束：已实现真实框架和运行入口，不等于真实供应商模型效果、MCP 业务工具、Agent 前端/SSE、多主机图存储或生产部署已完成。后续接入具体供应商、业务工具与上线按新范围执行，不自动开展。

## Web 首版：默认聊天、后台入口与接口核对（2026-11-02，完成）

按新授权实现 React 聊天页，登录成功统一进入 /#/chat；右上角和侧栏进入原知识后台，后台可返回聊天。参考 ChatGPT 当日公开访客页的浅色侧栏、留白与圆角输入区，采用独立样式及中性主题，不覆盖后台布局，也不展示未实现的附件/语音等按钮。

复用现有 Python POST /api/agent/chat、Java 知识列表和文件下载。支持本次登录的会话/草稿、新建与搜索、知识范围、中文输入法/换行、等待/停止、Markdown/表格/代码、真实引用来源和复制。首轮传 question/可选 kbIds，后续仅 question/sessionId；不上传旧答案/证据，不改变 Python 图上下文策略。显示状态区分资料不足、澄清、依赖失败及降级；原始 HTML/外部图片不执行或加载，下载由 documentId 构造原授权路径。

开发和 preview 将 /api/agent 优先分流到 KB_AGENT_ORIGIN，其余 API 仍去 Java。浏览器响应关闭显式销毁 Python 上游；真实浏览器取消已关闭 Python 工具 HTTP，并调用 Java 取消通知。Nginx 改用运行时 KB_AGENT_UPSTREAM 模板和延迟 Docker DNS，Agent 不存在时不会阻止后台启动；保留断连、SNI/CA 校验及安全响应头。没有自动部署 Agent 或放开 CORS。

本轮只新增 2 项引用展示边界检查，连同原 API 19 项共 21 通过；TypeScript/生产构建通过，日志 .cache/web-chat-{build,unit}-20261102.log。Playwright 实查默认路由、后台往返、多次引用/授权下载、固定范围与续聊、四种检索状态、取消传播、IME/Shift+Enter、桌面/390px 手机布局、HTML/外部图片过滤、零浏览器持久存储与重新登录清理。修正完整答案返回时自动跳到末尾的问题，并补充手机侧栏焦点/隐藏与输入框高度处理。回执见 docs/web/CHAT_VALIDATION.json。

验证使用真实 Chromium/Vite/Uvicorn/FastAPI/LangGraph/SQLite，Java API 和主模型是明确 HTTP 协议桩；未重跑真实 Java/PG/MinIO 或供应商，未运行 Maven verify，未覆盖原运行 JAR。Nginx 应用镜像/生产网络未实跑，不把 Vite 联调当作生产代理验收。

接口缺口已按代码列于 docs/web/CHAT.md：稳定身份查询、服务端会话列表/历史、重命名/删除、Python Agent SSE、运行状态/显式取消，以及能力发现/授权证据预览。当前前端列表仅覆盖本次登录，退出/刷新不等于删除 Python 检查点；没有伪造持久历史或流式文本。本轮到此结束，后续接口另行实施。


## Java 统一 Agent 会话、消息与原生 SSE（2026-11-05）

- V11 新增 Java Agent 会话、运行、消息账本；租户/所有者、固定范围、标题/时间、稳定序号、三类关联 ID、执行状态、工具展示、引用、实际证据摘录与知识版本全部持久化。公开列表/消息分页、重命名/删除、运行读取/取消；服务身份 + 会话绑定用户委托的内部读写。
- 数据库短事务取得唯一执行权，重复请求不重复保存/调用图，陈旧快照拒绝，终态不能复活。持久 deadline 清扫仅结算，无自动恢复生成。取消/超时可在不可延长的5秒窗口保存尾部，删除物理清消息/证据并保留最小请求墓碑。
- Python 移除 sessions.py，从 Java 有效历史中选近期消息/用户问题提取摘要，新回答继承历史助手的证据依赖。每次模型调用前确认历史与新证据，旧引用不进入本轮引用表。图按 runId 隔离，终态/重启清理，不从旧 checkpoints 重放工具。
- 新增 Python 原生 SSE：meta/delta/tool/error/done、背压与心跳，正文批量保存；AnyIO 取消域保护末尾结算，修复浏览器断连二次取消导致清理中断的问题。Web 从 Java 恢复历史，支持管理、分页、工具状态、引用摘录及取消后的权威状态复查；仅查询，不自动重发问题。
- Java 定向3个场景已通过真实 PG/pgvector/MinIO 和 Python TCP/SSE 验证；Python 与 Web 定向结果及浏览器记录统一保存为 CONVERSATION_VALIDATION.json。运行中 JAR 未覆盖，不连接既有业务数据库，不运行全量 reactor verify。
- 维护当前架构、会话契约、OpenAPI 生成器和五份 RAG 文档。真实模型语义效果、业务工具、人工图恢复、多实例图存储、生产代理/容量未验收；本轮范围到此，不自动开发后续能力。

最终定向验证：Java 3 个独立场景通过（最新分页/终态工具与跨语言分别补验，均0失败/跳过）；Python 9 项服务 + 4 项原适配器通过；Web 构建和23项检查通过；浏览器8类行为通过。OpenAPI 9路径/39个本地引用解析通过，ruff与锁文件检查通过。记录、分层、日志和源码哈希见 [CONVERSATION_VALIDATION.json](CONVERSATION_VALIDATION.json)。临时浏览器、Vite和验收Agent已关闭。
