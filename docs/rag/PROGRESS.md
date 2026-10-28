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
