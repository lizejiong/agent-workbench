# RAG 评估记录

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

| 类型 | 本轮范围 | 结论边界 |
|---|---|---|
| 本地模拟 | 模型 HTTP 桩、受控失败/延迟、真实执行器和取消、ES 停滞 HTTP fixture | 可验证契约、预算和权限，不验证模型语义 |
| 真实外部服务 | 独立 Testcontainers PG/pgvector、MinIO、ES；数量和结果见 PROGRESS | 可验证 SQL、对象存储、BM25/分析器和同步，不代表生产容量 |
| 真实供应商 | 本轮未调用付费 embedding/rerank/chat | 历史本机 embedding 连通记录不能算本轮效果验收 |
| 端到端答案效果 | 未实现 B，无答案/引用准确率数据 | 不报告虚构的通过率、幻觉率或生产 SLO |

## 后续效果评估方案（未执行）

第 3 轮查询理解使用确定性模型输出桩验证术语 PG/PostgreSQL、ZX-1472 编号、30 天、否定条件、重复子问题、真实澄清状态、非法 JSON、超量子问题、越权路由和超时回退；另验证权限/对话检查在模型之前，以及 INDEPENDENT 入口不读取 Java 历史。中文模板本身未进行真实模型语义效果评估；保护词校验只能识别部分改写偏差，不能代替人工评审。

业务数据集每项保存 caseId、question、授权身份/库范围、相关 document/chunk 标签、可答/不可答标签、预期证据片段和人工评审意见；单独加入权限负例、版本变更、近义问法、精确编号、中文及文档内指令攻击。样例不得泄露真实用户资料，训练/调参集与验收集分开。

先比较 VECTOR（无 rerank）、KEYWORD、HYBRID RRF，再比较相同候选的 rerank；每次固定版本和 budget，记录空结果、降级和错误率。只在成功且同等范围的调用间比较检索质量，同时独立报告依赖失败，禁止把失败样本移除后声称整体可靠。

| 指标 | 定义 / 记录方式 | 当前数值 |
|---|---|---|
| Recall@k / MRR / nDCG@k | 人工相关性标签，按 query 平均；分别报告可答/不可答与权限样本 | 未测 |
| 引用覆盖与支持性 | B 实现后检查引用 ID 存在，并人工审查引用是否支持具体主张 | 未测 |
| 无证据拒答与事实正确性 | 独立人工评审；不能用模型自评或分数阈值替代 | 未测 |
| p50/p95/p99、吞吐与错误率 | 固定硬件、并发、数据量，含超时/容量拒绝/降级，记录模型调用量与费用 | 未压测 |
| 取消与撤权 | 预算内终止等待；模型前后权限/正文/版本复核；DB 残留另测 | 行为测试，见 PROGRESS |

已知限制：1024 维固定，精确检索无 ANN 容量验收；不同嵌入空间请求需拆分；cjk 对业务专名的召回没有标注数据；关键词 dirty 文档暂不参与关键词召回；候选/topK、回表竞态与输入预算可能减少召回。有效版本、引用格式和阈值仅是工程约束，不是事实正确性的保证。
