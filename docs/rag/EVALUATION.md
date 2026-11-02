# RAG 评估记录

## Python 主 Agent 接入验证（2026-11-02）

新增官方 LangChain create_agent / LangGraph 运行入口；协议与安全行为验证不计作答案质量分数。Python `tests/protocol_fixture.py` 用原生 Chat Completions HTTP 协议固定工具调用/最终回答，只验证真实框架是否按协议执行，不能据此声称模型会自主选择正确工具。

沿用 Java `database.md/backup.md/cooking.md` 数据集、1024 维 topic-vector HTTP 桩与真实 PG/pgvector/MinIO。新增 Java 定向联调从真实 Uvicorn TCP `/api/agent/chat` 开始，Python 向真实 Java 签发本人的范围委托、执行两次检索，模型协议桩得到带唯一 K 编号的工具结果后返回最终引用，Java 委托撤销和未写独立问答历史均有断言。

主 Agent 参数：并发 4、总预算 60s、最多 8 次模型/6 次工具、工具结果 4096 UTF-8 字节、消息+提示词+协议预留 60000 字节、输出上限 1024 模型 token、SDK 重试 0。会话重启复核归属，旧 ToolMessage/助手答案被清理，只保留至多 4 条、8000 字节近期用户问题。测试按需缩短预算或限制并发，不把测试设置当生产推荐。

真实框架检查覆盖多次工具调用、唯一引用、四种状态、越权会话/撤权、错误脱敏、取消关闭、连接池复用/关闭和重启上下文清理；记录见 `AGENT_RUNTIME_VALIDATION.json`。未验证真实供应商选工具/语义正确性、业务工具组合、人工中断恢复、生产负载或上线效果。本次没有 Recall/MRR/faithfulness 等可报告效果指标；引用归属合法与提示词隔离均不构成事实或注入安全保证。

以下保留各轮历史验证基线。

2026-10-28，R1。当前验证结构化检索和失败/权限语义，不存在已测的 RAG 答案质量分数。

本轮独立用例合计 269 项，257 通过、12 个可选独立数据库用例跳过，0 失败/错误；包含真实 Testcontainers 集成。完整命令、打包与本地运行包恢复边界见 [进度](PROGRESS.md) 和 [机器回执](R1_VALIDATION.json)。测试通过率不作为业务检索效果指标。

## 数据集与可重复基线

第 2 轮沿用下述已入库样例和 topic-vector 基线；新增省略范围、空范围、默认范围超额与跨用户显式选择的回归。`KnowledgeSearchTest` 将真实入库后的请求、证据与确定性分数写入构建目录 `services/backend/target/knowledge-base/search-example.json`；隔离构建时文件位于隔离副本下。该基线仍使用 HTTP 模型桩，不计作真实供应商语义效果。

复用 `services/backend/src/test/resources/knowledge-base/search/{database,backup,cooking}.md`：数据库连接说明、pg_dump 备份说明、烘焙负例。`KnowledgeSearchTest` 真实上传/解析/入库，用显式 HTTP topic-vector 桩构造 1024 维向量，再执行真实 PG cosine SQL。

| 场景 | 实际断言 / 标签 |
|---|---|
| PostgreSQL connection，topK=3，minScore=0.7 | database 第一、backup 第二，分数约 1.0 / 0.8；这是确定性向量桩的回归基线 |
| astronomy，minScore=0.9 | 空结果；无阈值仍可返回近邻，不能声称近邻必然相关 |
| 空索引 | 无 embedding 费用；COMPLETE/NO_EVIDENCE |
| 其他 owner / tenant、禁用 Chunk/文档、旧 generation | 不可作为返回证据或精排输入 |

`HybridRetrievalTest` 每次创建 manual.md（麒麟数据库、ZX-1472、PostgreSQL 备份）、recovery.md（Database backup/pg_dump）、cooking.md；真实 ES 8.19.17 + cjk/BM25，模型仍为协议桩。ZX-1472 和麒麟数据库命中 manual.md，ABSENT_987654321 无命中。RRF 去重、原始 cosine/BM25 分离、精排映射、依赖失败和回表竞态均为行为回归，不是业务语义评测集。

参数固定为：pipelineVersion=retrieval-v1，向量精确 cosine，channelCandidates=50、fusionCandidates=20、topK 默认5、RRF k=60、两个权重1；rerank 默认关闭。测试按用例显式启用精排并缩短 timeout；不得将故障用例参数当作生产推荐值。真实模型必须固定 model/configRevision/space、解析分块配置、activeGeneration 和关键词目标及同步修订后再比较。

## 验证类型

第 4 轮在已有 HybridRetrievalTest 的真实 PG/MinIO/ES 数据上保存 `target/knowledge-base/fusion-baseline.json`，同时包含纯向量、单查询混合和多查询混合的请求结果。额外单元回归验证重复子查询只执行一次、全请求候选分配、同一查询/通道的重复候选不增分、不同文档同正文保留来源。模型仍为 topic-vector HTTP 桩，输出只作为算法行为对照，未得出语义质量提升结论。

| 类型 | 本轮范围 | 结论边界 |
|---|---|---|
| 本地模拟 | 模型 HTTP 桩、受控失败/延迟、真实执行器和取消、ES 停滞 HTTP fixture | 可验证契约、预算和权限，不验证模型语义 |
| 真实外部服务 | 独立 Testcontainers PG/pgvector、MinIO、ES；数量和结果见 PROGRESS | 可验证 SQL、对象存储、BM25/分析器和同步，不代表生产容量 |
| 真实供应商 | 本轮未调用付费 embedding/rerank/chat | 历史本机 embedding 连通记录不能算本轮效果验收 |
| 端到端答案效果 | B 已有工程行为验证，未做真实模型语义评估 | 不报告虚构的准确率、幻觉率或生产 SLO |

## 后续效果评估方案（未执行）

第 9 阶段完成工作区无 Agent 时的备用交付。Java 的 9 项定向测试（含 2 项内部真实 HTTP 场景、4 项查询理解和 3 项账号边界）通过；Python 4 项 MockTransport 测试覆盖仅 query 参数、scope 注入、状态、故障、取消、预算、跨工具调用唯一引用和恢复精确版本/身份。另有 1 项真实 Python httpx→Java 随机端口→PG/MinIO/模型 HTTP 桩联调，连续两次 READY、唯一 K 引用及 traceId 传播通过。FastAPI lifespan 复用/关闭已直接执行验证，具体版本与脱敏回执见 AGENT_INTEGRATION_VALIDATION.json。

一次检索后的摘录引用、与业务结果组合仅是调用/渲染与只读结果桩验证，没有真实主 Agent 决策、真实业务工具或 LangGraph 图检查点运行。澄清状态沿用 Java 查询理解测试及 Python 契约桩；没有声称真实模型澄清质量通过。恢复时的 reconfirm 只验证适配器失败关闭行为，宿主尚未安装图恢复前清理旧消息的钩子。没有新增真实 Agent 答案正确率、业务成功率或真实图恢复安全性的效果数据。

第 8 轮使用真实 MVC 随机端口和客户端 socket 验证 SSE 增量、跨帧引用、明确终态、坏编号/未闭合引用、无证据和依赖错误、上游 EOF/error、空闲超时、本人取消、真实断连、跨实例样式的 DB 截止传播和崩溃遗留清扫。HTTP 桩记录真实连接关闭，区别于只验证 Future 已取消。继续复用普通问答及授权历史场景；没有把连接关闭推断为数据库立即取消，也没有测生产慢客户端、吞吐或供应商流式性能。

第 7 轮已验证独立问答的真实存储、授权历史和引用协议行为（3 项 PG/MinIO 集成场景，模型为 HTTP 桩）。合法 E 编号、一次修复、系统/资料消息分离、无证据不调用、冲突或否定条件写入中文模板属于工程约束。尚未用真实供应商判断事实综合、否定条件保留、冲突解释、提示词注入抵抗或引用断言的语义支持；这些仍须在固定数据集人工标注后评估。HTTP 桩 usage 只验证透传，不作为计费或模型性能数据。

第 6 轮采用 evidence-context-v1-utf8-guard：文档/子问题/来源配额、局部 E 编号、完整行表格裁剪、原正文及展示正文哈希、模型调用前复核。预算采用既有 AI 粗估×1.25 加独立 UTF-8 字节上限；为系统/输出/误差单独预留，尚无目标模型 tokenizer 的误差数据。证据变更和预算不可用返回明确错误，不能据此统计成“问题不可答”。邻居测试使用真实入库代次，禁用块不得成为补充证据；来源撤权/删除继续通过文件接口检查。

第 5 轮延续同一融合候选，验证精排只改变排序和筛选，不改写原始通道分。真实本地 HTTP 协议桩覆盖乱序 index、重复/越界 index、缺分、非法分、错 chunkId、空结果和 429；后处理用例覆盖候选少于 topK 仍评分、弱相关全剔除、BEST_EFFORT/REQUIRED、精排前后撤权和总预算。没有标注数据用于校准门槛，也没有真实精排质量对比指标；默认不设置可信度阈值。

第 3 轮查询理解使用确定性模型输出桩验证术语 PG/PostgreSQL、ZX-1472 编号、30 天、否定条件、重复子问题、真实澄清状态、非法 JSON、超量子问题、越权路由和超时回退；另验证权限/对话检查在模型之前，以及 INDEPENDENT 入口不读取 Java 历史。中文模板本身未进行真实模型语义效果评估；保护词校验只能识别部分改写偏差，不能代替人工评审。

业务数据集每项保存 caseId、question、授权身份/库范围、相关 document/chunk 标签、可答/不可答标签、预期证据片段和人工评审意见；单独加入权限负例、版本变更、近义问法、精确编号、中文及文档内指令攻击。样例不得泄露真实用户资料，训练/调参集与验收集分开。

先比较 VECTOR（无 rerank）、KEYWORD、HYBRID RRF，再比较相同候选的 rerank；每次固定版本和 budget，记录空结果、降级和错误率。只在成功且同等范围的调用间比较检索质量，同时独立报告依赖失败，禁止把失败样本移除后声称整体可靠。

| 指标 | 定义 / 记录方式 | 当前数值 |
|---|---|---|
| Recall@k / MRR / nDCG@k | 人工相关性标签，按 query 平均；分别报告可答/不可答与权限样本 | 未测 |
| 引用覆盖与支持性 | 编号合法性已有行为验证；引用是否支持具体主张仍须人工审查 | 未测语义指标 |
| 无证据拒答与事实正确性 | 独立人工评审；不能用模型自评或分数阈值替代 | 未测 |
| p50/p95/p99、吞吐与错误率 | 固定硬件、并发、数据量，含超时/容量拒绝/降级，记录模型调用量与费用 | 未压测 |
| 取消与撤权 | 预算内终止等待；模型前后权限/正文/版本复核；DB 残留另测 | 行为测试，见 PROGRESS |

已知限制：1024 维固定，精确检索无 ANN 容量验收；不同嵌入空间请求需拆分；cjk 对业务专名的召回没有标注数据；关键词 dirty 文档暂不参与关键词召回；候选/topK、回表竞态与输入预算可能减少召回。有效版本、引用格式和阈值仅是工程约束，不是事实正确性的保证。
