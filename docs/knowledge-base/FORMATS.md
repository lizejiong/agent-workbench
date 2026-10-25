# 知识库格式支持矩阵（第 7 阶段）

`GET /api/knowledge/formats` 返回当前部署的配置能力，需认证；配置启用不等于外部连通或质量验证。全部格式仍经同一内核、任务、分块、Embedding 和代发布。

| 格式 | fast | fidelity | 来源与规则 | 本轮验证类型 |
|---|---|---|---|---|
| TXT | 严格UTF-8段落 | 明确别名到fast | 原文行号；允许BOM | 既有本地回归 |
| Markdown | CommonMark AST/GFM | 明确别名到fast | 标题路径、代码、规则表格、原文行号 | 既有本地回归 |
| 普通PDF | Tika，明确NO_OCR | 需MinerU2.7.6 | fast实际页号/段落；fidelity可有标题路径、页号、bbox和表格 | 本地二页真实PDF；MinerU协议桩 |
| 扫描PDF | 无文字明确KB_OCR_REQUIRED | 需MinerU OCR | 不宣称普通文本抽取支持扫描件 | 本地拒绝验证；OCR结构/表格协议桩，未真实OCR |
| DOCX | Tika基础文本、可用标题和规则表格 | 当前不可用415 | 无可靠分页不造页码；复杂合并表格拒绝 | 本地自生成DOCX |
| DOC | Tika Office适配 | 当前不可用415 | 同上；加密/损坏明确失败 | 适配代码完成；尚无本地DOC代表样例验证 |
| PPTX/PPT | Tika基础文本、规则表格 | 当前不可用415 | 实际幻灯片位置，不伪造文档页码 | 本地PPTX二页与legacy PPT |
| XLSX/XLS | POI多sheet、格式值、公式求值 | 明确别名到fast | 首非空行表头；保留空单元格与真实行号，空行跳过但不串行 | 本地两种Excel、多sheet、中文、空值和公式错误 |
| CSV | Commons CSV/RFC4180、UTF-8 | 明确别名到fast | 首记录表头、固定列数、引号换行；=开头只是字面字符串 | 本地CSV、中文/空字段/引号内换行/宽度错误 |
| PNG/JPEG | 需现有AI vision/VlmClient | 同一VLM路径别名 | 生成描述+可见文字；标注generated/provenance，不伪造图片中的文本行号 | 本地图像尺寸/字节校验，HTTP协议桩；未真实VLM |
| SVG、宏Office、其他格式 | 不开放415 | 不开放415 | 本轮没有SVG需求，不引入Batik或外部资源加载 | 拒绝用例 |

外部真实服务验证：**无**。当前运行环境未提供MinerU推理服务或供应商VLM/Embedding账户；本地HTTP桩只验证协议、来源结构、错误和索引一致性，不能证明OCR识别或模型语义质量。普通PDF/Office/表格的解析器是真实库，S3与pgvector测试使用真实MinIO/PostgreSQL。

档位不会因服务失败而下降：只有表中逐项声明的静态别名才使用同一解析器；MinerU503/超时/版本不符、VLM空描述或换模型都使任务明确失败，旧activeGeneration仍可用。Office fidelity不能把文件直接发送给只认PDF/图片的旧MinerU服务。

代表性文件由本项目 `FormatFixtures` 生成，位于 `services/backend/src/test/resources/knowledge-base/formats/`：

- `ordinary.pdf`：二页真实文本层PDF，数据库与备份段落，页号应为1/2。
- `scanned.pdf`：只有内嵌发票图片，没有文字层；fast必须失败，不能靠标题/扩展名猜OCR完成。
- `handbook.docx`：真正Heading1样式、中文段落和规则表格，空值不移列。
- `slides.pptx`、`legacy.ppt`：文本/表格与真实幻灯片位置。
- `multi-sheet.xlsx`、`multi-sheet.xls`：销售/Stock两sheet，乘法公式和0.00格式、中文、尾部空单元格、真实第5行数据。
- `quoted.csv`：中文、空数量、引号内换行和`=SUM(1,2)`字面文本，不执行公式。
- `invoice.png`、`invoice.jpeg`：可见INVOICE 42/Apples: 2；协议桩描述不当作真实识别验收。

规则表格首行作为表头并重复；空/重复表头补有位置的字段名。Excel公式使用POI实际求值，结果错误、外部工作簿缺失或不支持公式均明确失败，不回退到未知缓存空值。CSV的行号单位是CSV_RECORD，含表头为1；多行引号字段仍只算一个记录。

默认上传20MiB（可配置），Office ZIP展开额外限制64MiB总量、4096条目、16MiB单条目和大条目100倍压缩比；默认本地子进程30s/256MiB/并发2、最多500页/32sheet/20000行/128列、解析文本100万UTF-16单元。图片≤2MiB/16Mi像素/每边10000，MinerU默认90s同步预算、不轮询，响应≤8MiB。表格分块沿用50行/目标1024字符/软容忍512/硬8192，单元格超预算明确失败，不能截断成无对应的数据。

MinerU合并单元格展开值保留行对应；HTML只在本地Jsoup解析，不访问图片/链接，不保存原始HTML或大段base64。引用原文件仍通过授权文件接口；VLM文本是辅助描述，人工维护后其正文/语义向量遵循同PG事务规则。

完整配置、恢复与命令见 [RUNBOOK](RUNBOOK.md)，请求契约见 [API](API.md)。最终验证统计见 [PROGRESS](PROGRESS.md)。
