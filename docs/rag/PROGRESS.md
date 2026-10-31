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
