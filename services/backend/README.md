# RAG 业务后端

Java 17 / Spring Boot 3.5.16 / Spring MVC，接入根 Maven 多模块和公共 Web/响应组件。

第10阶段完成API管理交付：GET /api/knowledge/management导航、认证OpenAPI/52条Postman请求、scripts/knowledge-base/delivery_acceptance.py一键HTTP验收、库文档数和最近成功入库时间。V8复用内容审计表记录同事务摘要；Actuator/Micrometer提供任务、阶段、模型失败、清理和刷新指标。KB_OPERATOR默认false，仅保护全局指标，业务归属不变；不建设用户管理或新的前端框架。完整启动/恢复/最终验证和限制以运行手册、进度为准。

第9阶段增加可配置混合检索：Elasticsearch Java Client/BM25、真实中文分析器检查、数据库事务Outbox与修订可见性、并行向量/关键词召回、RRF去重和可选原生AI精排。POST /api/knowledge/retrieve返回证据及实际通道/精排状态；原/search保留数组并增加策略响应头。关键词和精排默认关闭，向量独立可用；V7自动记录所有入库/维护/删除同步意图。GET文档keyword-index和KB keyword-sync可查询/恢复。Compose可选keyword profile，默认cjk；真实供应商精排需单独联调，本轮提供结构化证据检索。

第8阶段增加受控URL导入和数据库持久化刷新：OkHttp验证精确域名/端口、DNS地址及逐次重定向，条件请求/SHA-256跳过未变化文件；CronExpression使用显式时区，数据库租约与claimToken协调实例。候选对象经同一可靠入库任务成功后，文件元数据、pgvector代和HTTP基线同PG事务切换；失败保留旧版本，旧对象及失败候选进入可重试清理。远程能力默认关闭，配置与恢复SQL见运行手册；scripts/knowledge-base/refresh_acceptance.py可使用受控本地HTTP来源完成真实请求验收。

第2阶段已交付知识库管理、服务端模型绑定、S3本地文件上传、授权原文读取和持久化补偿。配置、Flyway迁移、构建、运行和测试见 [运行手册](../../docs/knowledge-base/RUNBOOK.md)；接口见 [API](../../docs/knowledge-base/API.md)，阶段与真实验证见 [PLAN](../../docs/knowledge-base/PLAN.md) 和 [PROGRESS](../../docs/knowledge-base/PROGRESS.md)。

第3阶段的Tika/CommonMark结构解析、分块和固定模型内核已接入第4阶段可靠后台任务：上传后显式POST ingestion-jobs快速返回202，数据库持久化队列、有界线程池、定时扫描、租约续期和失权隔离、有限重试及取消。重新入库先准备新generation，同库事务发布；失败保留旧证据，成功后可重试回收旧代。配置和失败阶段查询见运行手册；样例见 [Chunk输出](../../docs/knowledge-base/SAMPLE_CHUNKS.json)。

第5阶段交付POST /api/knowledge/search：按可信归属校验指定知识库和文档，复用EmbeddingClient生成一次查询向量，pgvector精确余弦查询在排序前过滤当前代和启用状态，返回正文、scoreType和真实来源。一次请求限一个兼容模型及配置修订，不混排不同嵌入空间。topK默认5、可配置上限默认50；验收脚本为scripts/knowledge-base/search_acceptance.py。

第6阶段交付Chunk分页/新增/编辑/删除、独立启停和显式批量，以及文档启停、墓碑删除和持久化清理回执。人工正文与向量同PG事务更新；文档重新启用保留单独禁用块，重新入库覆盖人工内容必须显式确认当前版本。Flyway新增V5；对象清理在事务外有限重试、租约恢复，原文件bucket须未开启版本控制并授予GetBucketVersioning/DeleteObject。真实HTTP验收：python scripts/knowledge-base/maintenance_acceptance.py（只删除脚本自己的临时资源）。

第7阶段沿用同一内核扩展PDF/Office/Excel/CSV/PNG/JPEG；GET /api/knowledge/formats查询配置能力。fast与fidelity路由明确，扫描PDF的fast无OCR；MinerU2.7.6和既有AI vision默认关闭，失败不降档。本地解析使用有界堆/超时子进程，表头、公式值、实际行号/页号/幻灯片号进入统一来源。支持/实测矩阵见[FORMATS](../../docs/knowledge-base/FORMATS.md)；验收：python scripts/knowledge-base/formats_acceptance.py（保留自身样例库，外部调用需显式开启）。
