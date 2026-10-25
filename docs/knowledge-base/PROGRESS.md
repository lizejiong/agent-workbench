# 知识库进度

## GitHub 公开发布（2026-10-25，已完成）

用户明确授权将项目发布到个人 GitHub 公开仓库，并说明后续定位为 Agent、RAG 仅为其中一项能力。使用已登录账号 lizejiong，选定名称 agent-workbench（已核对可用）；README 更新项目定位，明确已交付模型/知识库/管理台和待建设 Agent 运行时，不改现有包名、构建坐标或业务实现。

发布前核对 10 次提交中的 397 个历史 blob（约 3.45 MB），对本地真实密钥/密码及常见 API/GitHub 令牌、私钥格式做检查，未发现匹配。提交历史中只有 .env.example，无本地 .env、依赖、构建或运行缓存。此次只涉及定位文档，沿用既有代码与验证结果；通过 GitHub CLI 创建公开仓库并推送 main，结果以远端元数据及提交 SHA 核对为准。

已创建公开仓库 [lizejiong/agent-workbench](https://github.com/lizejiong/agent-workbench)，本地 origin 为对应 HTTPS Git 地址，main 已推送并跟踪 origin/main。GitHub 返回 visibility=public、private=false、默认分支 main；不携带认证的独立 API 请求返回 HTTP 200。首次推送的 c1909f4 与远端 main SHA 完全一致；发布记录随后同样提交并推送。未公开本地密钥、上传文件或数据库内容。

## 管理台提交整理（2026-10-25）

按用户要求将 React/Ant Design/Vite 管理台、同源代理与部署配置、四份知识库文档及 WEB_VALIDATION 记录整理为本地提交。核对 26 个前端文件的 SHA-256，全部与已通过 19 项测试、类型检查/生产构建和 35 项浏览器/HTTP 场景的验收版本一致，本轮复用这些验证结果，不重复运行相同测试。另记录本机真实百炼 Embedding、22 个 Chunk 入库与向量检索成功的结果。

补充 .playwright-mcp 临时目录忽略规则。对待提交文件按本地实际配置的密钥/密码逐项检查，无泄露匹配；.env、运行日志/回执、依赖、dist 和浏览器产物均排除，git diff --check 通过。无 Java 业务或数据库迁移修改；本次仅本地提交，未推送远程。

## 本机开发环境启动（2026-10-25）

**当前结果（用户再次更新密钥后）：已恢复真实模型调用并完成原文档入库。** 从本地配置重启 AI 服务（PID 27896），健康检查 UP；真实百炼 text-embedding-v4 返回 1024 维向量。通过既有 retry API 创建任务 7d400a4b-d37d-4418-a8f4-d9a2cd90f621，首次执行成功，FETCH/PARSE/SPLIT/EMBED/PERSIST/PUBLISH 六阶段全部 SUCCEEDED；54 个结构块生成 22 个 Chunk、22 个向量，激活 generation=2，文档状态 READY。

通过管理台同源代理对该文档执行真实向量检索，topK=3 返回 3 条证据，全部属于指定知识库/文档及 generation=2，分数类型 COSINE_SIMILARITY，首条约 0.9073。请求验证使用该文档首个 Chunk 的短文本片段，未输出正文或密钥。原文件复用，不重新上传；历史失败任务保留，新任务已成功。脱敏本机回执在 .cache/local-runtime/reprocess-receipt.json（Git 忽略）。本轮无业务代码改动；真实验证覆盖 Embedding、入库和向量检索，不扩展为 Rerank/VLM/MinerU 或关键词服务验收。以下记录保留首次启动及认证故障的过程。

按用户要求实际启动管理台（127.0.0.1:5173）、backend（8080）、ai-service（8081）、Compose PostgreSQL/pgvector（54329）和 MinIO（9000/9001），保留服务运行。使用现有构建产物，无业务代码修改。首次创建独立持久卷，Flyway V1–V8 全部迁移成功，私有原文件 bucket 初始化成功。

真实检查：前端 HTTP 200；backend/AI 健康状态 UP；通过前端代理认证访问 management、知识库分页、模型目录均 200；未认证知识库请求 401；真实浏览器显示“知识工作台”登录页。服务只监听本机。账号 kb-admin，随机密码及存储/数据库凭据保存于 Git 忽略的 deploy/knowledge-base/.env；运行日志与本机启动辅助文件位于 .cache/local-runtime，均未纳入版本库。

当前没有真实供应商密钥，ai-service 明确以 AI_ENABLED=false 启动；管理和上传可用，向量化/检索尚不可用。需要在本地为 AI 服务配置 BAILIAN_API_KEY、AI_ENABLED=true 后重启该服务；没有使用模拟 Embedding 代替真实服务。关键词、远程来源白名单、MinerU/VLM 保持未配置状态。本次只验证启动/代理/鉴权，不重跑此前功能验收。

后续入库故障排查：backend/AI 健康状态仍为 UP；真实模型描述接口返回 qwen-embed / text-embedding-v4 / 1024 维、active=false。本地 AI_ENABLED=false 且 BAILIAN_API_KEY 为空。实际入库 FETCH、PARSE、SPLIT 均通过，EMBED 阶段以 KB_MODEL_UNAVAILABLE 失败，达到 3 次尝试上限。需用户在本地配置真实百炼密钥并启用 AI 后重启 AI 服务，再重试失败任务；原文件已保存，无需重新上传。本轮未修改业务代码或模型绑定，未声称真实 Embedding 联调成功。

用户要求保存配置并重启后，已将本地 .env 的 AI_ENABLED 保存为 true。再次核对该文件与进程/用户/系统环境变量，BAILIAN_API_KEY、DASHSCOPE_API_KEY 均未配置；重启前仍需要用户在本地填入真实密钥。未使用占位密钥启动；未完成 AI 重启或真实向量化验证。此项属于待填写配置，覆盖上文首次启动时的 AI_ENABLED=false 文件状态。

用户填写密钥后，已确认 AI_ENABLED=true、密钥非空，停止本项目原 AI 进程并从本地配置重新启动（PID 28024），健康检查 UP，qwen-embed 描述 active=true、1024 维。随后用无敏感内容的短文本真实调用 /ai/embed，收到 502/AUTH；直接核对当前配置的百炼北京 embeddings 接口，收到 401/invalid_api_key（Incorrect API key provided）。已排除配置未读取、引号/空白及行尾注释问题；密钥未输出到聊天或日志。模型描述 active=true 仅说明本地配置启用，不代表真实认证成功。

当前仍需核对密钥来源/有效性及其地域与 Base URL 是否匹配（当前 dashscope.aliyuncs.com）。官方排查依据：https://help.aliyun.com/zh/model-studio/error-code#apikey-error 。原入库任务仍 FAILED/EMBED、attempts=3；认证恢复前未再次提交，原文件保留。前后端和 AI 进程继续运行，真实向量化与入库恢复尚未完成，未修改业务代码或嵌入空间。

## 当前交付：React / Ant Design / Vite 管理台（2026-10-25，已完成）

按用户明确要求补图形入口。已重读 PLAN/PROGRESS 并核对实际业务接口；web 原为占位目录，后端已有完整管理能力。本轮复用既有 Basic、服务端 tenant/owner、版本 CAS、持久化任务、来源下载、检索与清理，不改业务表/迁移/模型协议，不建设用户或角色管理。

已实现库搜索/分页/创建/重命名/删除、模型选择与文档数；文档上传/URL 导入/状态/最近入库时间/启停/删除与清理回执；解析/分块配置；任务阶段/耗时/次数/重试/取消；Chunk 展示正文/embeddingText/来源/新增编辑删除/独立及批量启停；URL Cron/时区/手动刷新与历史；范围检索与真实策略/通道/精排降级/分数/原文件。页面任务状态由后端读取，隐藏时降频，离开取消。人工覆盖明确确认；空选择不变全量，版本冲突不自动覆盖。

新增 web 构建、请求边界测试、Nginx/Dockerfile、Compose 可选 web profile；Node 20.19 / React19 / AntD6 / Vite8 / TS7，完整锁文件安装。Basic 凭据仅内存；同源代理；不展示 objectKey、内部地址、异常堆栈或整篇日志。

本轮已完成，实测记录见 [WEB_VALIDATION.json](WEB_VALIDATION.json)。无后端业务或数据库迁移变更；以先前交付的真实 JAR 连接全新隔离 PG/pgvector、MinIO、ES 做浏览器联调。Embedding/Rerank/VLM/MinerU 为明确的本地 HTTP 协议桩，不把它们当真实供应商质量验证。

| 本轮检查 | 真实结果 |
|---|---|
| 标准锁文件安装 | npm ci --offline --registry=https://registry.npmjs.org --no-audit --no-fund --prefix web 成功，125 包；无项目级 legacy-peer-deps 配置，npm ls --depth=0 成功 |
| 前端测试 | Vitest 19 项全部通过：凭据/同源路径、重定向、超时覆盖响应体、取消、错误脱敏、401、multipart 幂等键、完整分页选择与原文件名 |
| 类型与生产构建 | npm run build（先 tsc --noEmit）成功，3111 模块；路由与供应商分包，最大 JS 258.23 kB，无超大包警告 |
| 真浏览器/HTTP | 35 项场景：33 项真实页面操作、1 项直接 HTTP 未授权检查、1 项前端分包下载失败模拟；最终新构建非预期 JS 错误 0 |
| 管理主链路 | 页面建库/服务端模型选择、上传、6 阶段持久任务、5 个 Markdown Chunk、授权下载；原文件逐字节一致，保留原始扩展名 |
| 人工维护 | 编辑后真实检索出现新正文；新增/删除正确；显式单 ID 批量；空选禁用按钮；文档重启不恢复独立禁用块 |
| 版本与失败 | 重新入库取消不提交，确认请求带当前版本；generation2 无旧块；维度故障失败保留2，页面重试成功发布4；旧表单409后需显式重新载入；隐藏 TEXT 解析器配置保留 |
| 刷新 | URL 导入接同一持久任务；非法 Cron400、Asia/Shanghai 定时保存含 nextRunAt；304 跳过，内容变化发布2并更新头部与历史 |
| 降级 | 真实暂停 ES 时返回 VECTOR/失败通道；精排 HTTP 桩 SHORT 时显示 FALLBACK_FUSION，不混淆各分数 |
| 生命周期与认证 | 两份文档删除后不可检索，清理回执 SUCCEEDED；旧远程对象/ES 清理完成后空库可删除；私有深链404、原文件未认证401、错误密码401；退出/刷新清空页面，浏览器存储为空 |
| 显示与部署 | Edge 生产预览与 390px 移动布局检查通过，无页面横向溢出；Compose web+keyword config --quiet 成功；容器镜像未构建/启动 |

截图已实际查看：output/playwright/web-documents-desktop.png、web-document-desktop.png、web-document-mobile.png、web-login-desktop.png（本地验收产物，Git 忽略）。详细请求覆盖、样例分数/来源、文件哈希、源文件/构建指纹在 WEB_VALIDATION。临时样例 UUID 不代表持久部署数据，测试设施完成后退出。

开发中发现并修正：弹窗 Form 在挂载前初始化；AntD 供应商分包执行顺序；旧页面引用已替换分包时的空白页（增加明确刷新/重新登录兜底）；已打开任务页的 job 参数切换及过快完成时头部不刷新。初始失败与中间检查不计作最终通过；最终安装后重新运行测试/构建，并验证新构建的 TEXT 配置保留与模拟兜底。

本轮未重跑原全量250项 Maven 回归（Java/迁移没有修改），以原已验收 JAR 完成上述实际 HTTP 集成。真实模型供应商、生产 TLS/来源白名单、对象存储策略与 Nginx/应用镜像启动仍需部署环境联调；未做负载测试。本版继续使用单 pgvector，不新增 IAM、用户管理或回答生成。

## 提交整理（2026-10-25）

按用户“提交改动，去掉无关代码”的要求核对全部未提交内容：后端、AI嵌入模型身份/版本联动、迁移、部署、验收脚本和文档均属于知识库交付；保留此前已有公共模块和必要测试样例，没有新增用户管理或无关业务。删除4个Java文件中的6处无用导入；与第10阶段已验证的247个构建输入比较，差异仅为这些导入声明，业务逻辑未变。

清理后根项目mvn -B -DskipTests verify于2026-10-25 13:33:27 +08:00成功，完成编译、测试源码编译及打包；本次未重复执行上轮已完成的250项回归。Python/JSON/Markdown、暂存差异及提交范围再次检查；缓存、JAR、IDE文件和本地凭据均被排除。原验收结果保留，清理后的构建信息与当前JAR哈希追加到STAGE10_VALIDATION.cleanupFollowup。知识库交付整理为本地Git提交，未向远程推送；镜像及真实模型供应商联调限制继续有效。

## 原第 10 阶段交付：API 管理入口与整体交付（2026-10-25，已交付；镜像联调受外部网络限制）

已按本轮要求重读方案/进度并核对代码。web仍为占位目录，使用约定的API/OpenAPI/Postman/验收脚本交付，不另建前端框架。已采纳用户补充：不建设复杂用户管理；现有服务端账号与私有归属继续复用，仅可选operator标志保护全局指标，不授予其他用户数据权限。

本轮增加API管理导航、52条Postman请求及标准库生成器、一键验收、库文档数/最近成功入库时间、V8同事务摘要审计扩展、Micrometer持久队列/阶段/清理/刷新指标与Embedding/Rerank失败统计、提交后关联日志。各功能继续复用此前管理、上传、内核、可靠任务、检索和维护接口。

最终全10模块Maven verify成功，完成于2026-10-25 13:12:20 +08:00：250项，238通过，0失败/错误；12项仅为未配置的可选LocalPostgresTest，Docker集成全部执行。管理交付6项、混合检索19项及此前阶段回归全部通过。247个构建输入与最终源码逐字节/SHA-256一致；实际JAR中的配置、OpenAPI、集合、V1–V8迁移与全部类文件已复核。backend与AI常规target以及-stage10.jar已更新，哈希见[STAGE10_VALIDATION.json](STAGE10_VALIDATION.json)。

| 最终要求 | 实际执行结果 |
|---|---|
| 建库→上传→入库→检索→原文 | 最终java -jar连接真实PG/pgvector、MinIO、ES；8份HTTP脚本全部PASS，另验证管理导航/集合/审计/指标权限；真实来源章节和授权file字节一致 |
| Chunk编辑/启停/独立禁用/显式批量 | maintenance_acceptance实际HTTP通过；新正文、新向量和人工标志一致，文档重新启用保留独立禁用Chunk；空数组400，applyAll显式生效 |
| 重建成功/失败 | HTTP故障注入错误维度，失败保留generation1；修复后generation3成功，仅返回新代；重复已完成requestId返回同job。人工覆盖缺确认409，确认当前版本后重建 |
| 重复、租约失效和重启 | 既有并发领取/重复执行/失权迟到/取消回归通过；入库、对象清理、刷新分别用两个真实Java进程恢复，attempt=2且无重复激活 |
| URL未变/变化/失败 | refresh_acceptance真实条件304、同hash新ETag跳过；内容变化文件与代同PG激活；调度显式timezone；来源503有限失败，旧版本保留 |
| 删除与物理清理 | HTTP删除立即无证据；数据库→S3清理回执成功，真实ES旧修订最终清除后才能删父库；集成故障注入验证持久失败及重排 |
| 权限与最小账号机制 | 另启动两个真实JAR进程连接同PG：bob同租户不同owner、carol不同租户同owner，库/文档/原文/Chunk/审计/检索范围均404且列表空；伪造身份头无效；operator可读全局指标仍不能读alice文档 |
| 可选通道失败 | 真实暂停/恢复ES容器：HYBRID返回VECTOR与通道失败标识；离线时编辑形成持久同步失败，恢复后重试到SUCCEEDED；错误精排回落融合，有精排阈值时无证据且THRESHOLD_UNAVAILABLE |
| 日志、指标、审计 | 提交后日志含kb/document/job/generation/stage，不打印样例敏感正文；PG阶段FunctionTimer/任务/清理/刷新及Embedding/Rerank失败指标通过；失败事务无变更审计和新正文 |
| 契约与静态检查 | OpenAPI1.0.0，41路径/52操作/416引用；52条Postman请求的方法/路径/JSON数字版本校验通过，凭据为空；默认/keyword Compose解析、Python语法、JSON示例、Markdown与git diff --check通过 |
| 前端 | web仍为.gitkeep，无可执行前端检查/构建；按用户约定交付API/OpenAPI/调用集合/验收脚本，没有引入新前端框架 |

实际报告：[DELIVERY_HTTP_REPORT.json](DELIVERY_HTTP_REPORT.json)；混合检索调用：[SAMPLE_HYBRID.json](SAMPLE_HYBRID.json)；故障与降级：[SAMPLE_DELIVERY_FAULTS.json](SAMPLE_DELIVERY_FAULTS.json)；独立HTTP权限：[SAMPLE_DELIVERY_SCOPES.json](SAMPLE_DELIVERY_SCOPES.json)；三个真实进程恢复：[SAMPLE_DELIVERY_RESTARTS.json](SAMPLE_DELIVERY_RESTARTS.json)。模型Embedding/Rerank、VLM、MinerU都是明确本地HTTP协议桩，PG/MinIO/ES、下载来源、解析器和Java进程是真实运行；不能把协议验收当真实语义/精排/OCR质量。临时设施已退出，示例UUID仅为验收证据，不是持久在线数据。

使用：按RUNBOOK配置既有DB/S3/AI与服务端私有账号，启动JAR或Compose；认证GET /api/knowledge/management，下载OpenAPI/集合；执行python -X utf8 scripts/knowledge-base/delivery_acceptance.py。关键词、精排、OCR与远程来源按实际配置启用，未配置能力明确跳过或报错。无IAM建设、无用户管理页面；KB_OPERATOR默认false，仅运维指标使用。后续提交整理见本页顶部记录，不自动扩展后续功能。

剩余外部依赖：真实供应商密钥/固定模型质量、生产来源DNS/出口白名单、生产S3保留策略与ES集群配置尚未联调。应用基础镜像首次拉取发生unexpected EOF（期望47519993字节，仅收到44334499），第二次有界重试362秒仍未完成，已只停止本任务下载进程；应用镜像尚未构建/启动，不能宣称容器端到端通过。Compose配置与实际JAR已验收，网络恢复后按RUNBOOK重试镜像，或先部署已验证JAR。最终状态记录在STAGE10_VALIDATION。原交付的图形界面已由顶部 Web 工作补齐。单 pgvector(1024) 精确检索的大规模性能和跨不同嵌入空间融合仍不在完成范围；审计/任务保留归档由运维制定。

中间发现并已修复：可选ES依赖触发Boot默认localhost健康探测，已排除多余客户端自动装配；多构造器配置绑定增加ConstructorBinding。最终回归已覆盖，未将之前中断/失败的构建计作成功。

## 第 9 阶段，可配置混合检索（2026-10-24 至 25，最终验证纳入第10阶段）

已读取方案/进度并核对现有搜索、AI精排原生协议、维护及GC写入路径。仓库无配置好的ES服务；沿用Boot管理的Java Client8.18.8，使用本机缓存ES8.19.17/IK镜像的全新测试实例，不操作其他项目数据。新增V7事务Outbox、不可变关键词修订、PG有效版本过滤/回表、RRF和有界并行检索、既有AI精排客户端及可查询同步状态。结构化检索为本轮范围，未引入可选回答生成。

最终19项真实ES中文BM25、PG/MinIO、精排HTTP协议及同步/GC/失权/失败恢复测试通过，并纳入第10阶段全量250项验证与最终打包HTTP调用。首轮Java Client对REST简写的解析差异已改为类型化builder；没有用模拟关键词匹配代替BM25。默认公开ES8.19.17镜像，实测使用隔离缓存同版本镜像的内置cjk；精排供应商真实服务尚未联调。结果见[STAGE9_VALIDATION.json](STAGE9_VALIDATION.json)和[SAMPLE_HYBRID.json](SAMPLE_HYBRID.json)。

## 历史工作：第 8 阶段，URL导入与自动刷新（2026-10-24，已完成）

已重新读取PLAN/PROGRESS并核对代码，先收尾第7阶段最终206项回归与打包HTTP验收，再复用可靠任务/统一解析/pgvector发布。新增V6、受控OkHttp下载、导入幂等、Cron时区schedule、持久化run/租约/续租/恢复、候选文件同事务激活和可恢复对象清理。真实来源URL暂由服务端精确白名单管理，默认关闭，不顺带建设IAM或网络管理平台。

最终全10模块Maven verify成功（2026-10-24 22:44:45 +08:00）：225项，213通过，0失败/错误，12项为未配置的可选LocalPostgresTest跳过；Docker集成未跳过。RemoteRefreshTest 18项、RemoteNetworkPolicyTest 1项全部通过，既有管理/上传/可靠任务/人工维护/多格式/检索/AI回归通过。最终222个构建输入的SHA-256与已测试快照完全一致。

| 验证范围 | 实际结果 |
|---|---|
| 导入/幂等/归属 | 真实HTTP来源→共享上传→S3→PG→可选持久化job；save-only PENDING、初始成功基线为空，同key重放不下载，改参409，跨用户/租户404、未认证401 |
| 无变化 | 304携带成功ETag/Last-Modified直接SKIPPED；同字节新ETag以hash跳过、更新同版本validators；均不调用解析/Embedding、不改generation |
| 内容变化与发布 | 新对象/job快照准备时，授权file仍读旧字节，旧generation仍可检索；成功同PG切换文件/hash/version/代/成功ETag，检索只返回新正文。数据库触发器注入发布故障，文件/Chunk/向量/基线全部回滚 |
| 失败与恢复 | HTTP5xx有限3次退避、总期限；解析/维度失败保留旧文件/基线，修复后同一远程内容仍能重新拉取；失败候选清理故障落库并真实重试成功 |
| 并发/失权 | 8路领取仅一winner，重复投递不重复下载/换候选，活跃refresh与手动入库互斥；run/schedule租约过期后迟到模型不能发布，新token/new job恢复，旧job不能覆盖 |
| 真实进程重启 | 真实Java进程在EMBED期间强杀；第二进程连接同PG/MinIO，过期接管同run，attempt=2、原目标2失效、新目标3成功；检索仅generation3，无重复 |
| 时区与生命周期 | CronExpression+Asia/Shanghai→UTC真实计算、非法cron/timezone/旧schedule版本拒绝；到期重复扫描只建1run；禁用/删除及下载时并发禁用均失效旧执行者并停schedule；人工覆盖确认/自动SKIPPED |
| 网络/预算 | URL认证/非批准域名/元数据地址/IPv4IPv6私网与保留范围拒绝；每跳校验，循环重定向、空响应、声明超限/流式超限/超时拒绝；同origin跨URI也清除条件头，不保存目标validators |
| 最终打包HTTP | 实际java -jar连接真实PG/MinIO，acceptance.py与refresh_acceptance.py通过：导入/重放→后台入库→304/hash/变化→检索→真实@Scheduled触发→有限失败；没有只以MockMvc代替服务验证 |
| 迁移/交付 | V1–V6 validate/重放及旧数据升级回归通过；OpenAPI30路径/41操作/349引用；Compose、Python语法、21个JSON示例、Markdown与git diff --check通过；最终JAR资源/类/OkHttp依赖及哈希复核 |

实际请求/证据见[SAMPLE_REFRESH.json](SAMPLE_REFRESH.json)，真实两个进程恢复记录见[SAMPLE_REFRESH_RESTART.json](SAMPLE_REFRESH_RESTART.json)，最终统计、源快照/产物SHA-256见[STAGE8_VALIDATION.json](STAGE8_VALIDATION.json)。常规backend target已更新，另保存-stage8.jar；AI产物另存-stage8.jar以保留历史包。本轮未提交或推送。

使用：按RUNBOOK配置remote开关、精确来源域名/端口及必要内部来源例外，已有DB/S3/AI和worker就绪后调用import-url（ingest=true），查job/stages；再提交refresh或PUT显式cron/timezone。python -X utf8 scripts/knowledge-base/refresh_acceptance.py可重现受控本地来源调用链，保留自己创建的KB；失败清理及卡住恢复SQL均已写入RUNBOOK。

剩余限制：真实模型供应商、生产来源域名/DNS/出口策略、生产对象设施和目标Docker应用镜像尚未联调；Embedding为明确HTTP主题桩，受控文件来源、PG/pgvector、MinIO与两个Java进程是真实运行。remote默认关闭；HTTPS/精确白名单，内部来源须管理员例外；不支持来源登录/任意请求头、HTML抓取/浏览器渲染或压缩响应。重定向来源以完整下载/hash比较；源格式应继续符合显式filename。同步导入失败/过期需查既有storage-operations并用新key；后台刷新失败可用新requestId，不能绕过基线直接retry关联job。对象GC有限失败后需运维修复/重排，成功切换不承诺外部S3与PG分布式事务。后续混合检索/回答及管理界面等待单独授权。

## 历史工作：第 7 阶段，多格式统一入库（2026-10-24，已完成）

本轮授权到达时第6阶段仍在验证，已先完整收尾：最终197项测试/185通过/12可选跳过，HTTP维护与删除验收通过，详见第6阶段历史记录及STAGE6_VALIDATION。随后重新读取方案/进度并核对现有Tika、统一结构、AI vision和外部协议，继续复用原内核。

已扩展二进制上传/MIME、fast/fidelity注册、Tika基础Office/PDF、POI Excel、严格CSV、MinerU2.7.6固定协议、既有AI vision图片描述/OCR，增加进程内存/并发/硬超时和崩溃临时文件扫描。第6阶段的版本/人工覆盖/失权与同PG发布规则继续使用；无新的业务表迁移。

最终全10模块 Maven verify 成功：206项，194通过，0失败/错误，12项可选 LocalPostgresTest 跳过。新增 BinaryFormatsTest 9项通过；真实PG/MinIO覆盖各格式、来源、公式/空值、超限与失败保留旧代，既有重启/维护/检索回归通过。最终209个源文件快照与构建一致。实际 java -jar（含解析子进程）依次运行管理、多格式、维护、检索四份 HTTP 验收脚本通过；OpenAPI 26路径/36操作/296引用、Compose与文档检查通过。记录见[STAGE7_VALIDATION.json](STAGE7_VALIDATION.json)，实际请求/响应见[SAMPLE_FORMATS.json](SAMPLE_FORMATS.json)。模型Embedding、图片识别及MinerU均为明确HTTP协议桩；真实外部服务尚未配置，不能称作真实OCR或语义质量验证。逐格式范围见[FORMATS.md](FORMATS.md)。本轮未提交或推送。

以下为此前阶段记录；格式支持与解析档位以本节和支持矩阵为准。

## 历史工作：第 6 阶段，人工维护与生命周期（2026-10-24，已完成）

已先重读PLAN/PROGRESS并核对实现。复用现有MyBatis-Plus、可信tenant/owner、EmbeddingClient、唯一pgvector和可靠任务，不增加框架或下一阶段格式/URL能力。

本轮增加Chunk分页/新增/编辑/删除/独立启停/显式批量，以及文档启停/墓碑删除/持久化清理回执与retry。维护使用文档version和generation复查；模型在事务外，正文/向量/计数/脱敏审计同PG事务提交，失败回滚。新增V5，不改已有迁移；人工变更标志覆盖删除/禁用情形，重新入库要求明确确认与当前版本。

文档禁用或删除取消任务、失效token；重启用保留单独禁用块。清理先撤销检索，再DATABASE→OBJECT持久化两阶段执行；崩溃恢复、有限重试和人工重排。S3版本化bucket明确失败，避免删除标记冒充物理清理。完整设计/契约/配置见PLAN/API/RUNBOOK当前节。

真实验证：全10模块Maven verify成功，197项、185通过、0失败/错误、12项可选LocalPostgresTest跳过；ContentMaintenanceTest新增18项全部通过。真实PG/MinIO覆盖新语义向量、回滚、并发等待后重新读取版本、独立禁用/显式批量、迟到模型结果、有限清理重试与覆盖确认。两次真实Java进程验证对象阶段重启恢复和禁用块持久化。独立HTTP服务实际执行acceptance.py和maintenance_acceptance.py成功；完整调用见[SAMPLE_MAINTENANCE.json](SAMPLE_MAINTENANCE.json)，统计/隔离构建和产物哈希见[STAGE6_VALIDATION.json](STAGE6_VALIDATION.json)。原全量执行曾因环境进程中断未完成，恢复Docker后从最终180文件快照重新完整verify通过。外部模型供应商、生产S3留存策略和目标生产部署仍未完成联调；本地语义使用显式HTTP主题桩。

以下内容保留此前阶段记录；涉及人工维护和删除时，以本节及最新 API 契约为准。

## 历史工作：第 5 阶段，结构化知识检索（2026-10-24，已完成）

本轮先重读PLAN/PROGRESS并核对现有VectorStore、模型绑定和权限。复用已有MVC/校验、EmbeddingClient、S3来源接口与唯一PgvectorVectorStore，不增加依赖、迁移或新向量后端。2026-10-22完成Java全量验证；2026-10-24核对源码、完善OpenAPI声明、重新打包并完成真实HTTP交付验收。

### 实际改动与关键决定

- 新增search/KnowledgeSearchController、KnowledgeSearchTypes、KnowledgeSearchService、SearchProperties，公开POST /api/knowledge/search。请求query/kbIds/可选documentIds/topK/minScore；topK默认5，上限默认50并可配置。返回证据数组，包含chunkId、kbId、documentId、documentName、chunkIndex、generation、content、score、scoreType、sourceMetadata、fileUrl。
- 全部资源逐项校验服务端principal的tenant/owner和未删状态，未知/越权404，不能悄悄忽略；自有文档不在所选KB内400。query/UUID列表/topK/有限minScore及未知字段在外部调用前校验。查询与范围不接受请求中自传namespace/provider/vector/租户。
- 按模型/provider/名称/维度/space/configVersion/本地修订分组，并核对已发布AI修订。本版明确限制一次请求一个兼容空间，不兼容409/KB_SEARCH_MODEL_MISMATCH；同维度不同模型也拒绝。兼容多KB只生成一次查询向量；全空且尚未钉住AI修订时直接[]。指定模型/修订漂移仍失败，不降级。
- 扩展原VectorStore.query多Scope重载，旧单库调用统一复用。NamedParameterJdbcTemplate绑定UUID集合、修订、向量、阈值和LIMIT；精确public.<=>，排序前过滤可信归属、KB/doc范围、父墓碑、doc.enabled、chunk.enabled、activeGeneration和完整索引身份，全局topK；相同距离按chunkId排序。没有外部索引回表或近似候选丢失问题。
- 模型调用在DB事务外，沿用并发/维度/数量/超时与无fallback校验。调用后重验KB，最终SQL读取当前文档名和代/启用状态；模型等待期间代切换或删除不放行旧内容。保留旧active的PENDING/FAILED文档仍可检索。
- scoreType固定COSINE_SIMILARITY，score=1-distance，浮点边缘钳制[-1,1]；不是概率或BM25/精排分数。未设置阈值时最近邻可能主题无关；模型/DB失败不伪装无结果。只返回数据库展示content，不调用回答生成。
- sourceMetadata白名单保留真实章节、行号、表格/字符位置等；TXT/Markdown不虚构页码。fileUrl是仍需授权的原文GET；不泄露objectKey、存储密钥或供应商内部地址。更新PLAN/API/RUNBOOK、静态OpenAPI、Compose/.env与README。

### 真实验证

| 检查 | 结果 |
|---|---|
| 全10模块Maven verify（2026-10-22） | SUCCESS；179项，167通过、0失败/错误；12项未配置的LocalPostgresTest跳过，容器集成未跳过 |
| 后端 | 86项，其中74通过、12可选跳过；新增KnowledgeSearchTest 11项全部通过；既有管理、上传、可靠任务/重启和AI协议回归通过 |
| 检索真实设施 | PostgreSQL17.11/pgvector0.8.7、MinIO、AWS SDK上传/读取、Tika/CommonMark解析与Chunk写入、HTTP查询向量与真实cosine SQL；向量为明确的本地HTTP主题桩 |
| 范围与上限 | 兼容多库只一次query embedding；KB/doc范围、跨用户/跨租户404、混入越权资源拒绝、未认证401；topK默认5/配置6边界、重复/空/超量ID、query长度、未知字段和minScore类型/边界校验 |
| 可见性 | 文档/Chunk禁用与文档墓碑在LIMIT前过滤，仍能补足5条有效证据；启用文档不恢复禁用Chunk；模型等待时发布新代仅返回新generation/当前名称，模型等待时禁用/删库立即过滤或404 |
| 模型身份与失败 | 同为1024维的不同模型/本地版本及不同已钉AI修订明确409、未发embedding；绑定修订漂移409；错误维度502、超时504，不伪装[]；无阈值最近邻和无匹配阈值分别验证 |
| 来源与排序 | 真实正文/章节/第5行等来源、授权原文件字节验证；score1.0/约0.8排序，稳定同距离chunkId顺序；来源中注入objectKey/providerUrl/credential字段后对外被剔除，页码未伪造 |
| 真正HTTP（2026-10-24） | 临时独立Java应用与真实PG/MinIO上，旧acceptance.py和新search_acceptance.py实际通过；上传3来源→持久化后台入库→查询/过滤/空库/错误输入/授权读取，非仅MockMvc；完整请求响应见SAMPLE_SEARCH |
| 最终契约与包（2026-10-24） | OpenAPI18路径/25操作/199引用解析通过；补充nullable和422声明后重新package成功，业务源码与已通过全量测试版本一致；最终JAR内OpenAPI/配置/迁移/类文件一致性检查 |

使用与前轮相同的隔离构建方式，避免覆盖Windows旧AI进程占用的JAR。.cache/stage5-build核对171个构建源文件；最终backend产物复制回常规target，AI新副本使用-stage5.jar名称。源码、统计与产物SHA-256见[STAGE5_VALIDATION.json](STAGE5_VALIDATION.json)。未停止原AI进程，未提交或推送。

### 一条实际检索调用

[SAMPLE_SEARCH.json](SAMPLE_SEARCH.json)来自2026-10-24的真实HTTP请求，而非手写响应。请求POST /api/knowledge/search，query=PostgreSQL connection，kbIds含临时验收库9ce0e10f-4d5b-4723-a771-2f4503f88cc5，topK=3、minScore=0.7。返回database.md（1.0）和backup.md（0.800000011920929），未返回无关cooking.md；scoreType均COSINE_SIMILARITY，包含真实章节、行号、Chunk/文档ID和需授权的fileUrl。

数据来自services/backend/src/test/resources/knowledge-base/search/{database,backup,cooking}.md：实际经上传、后台入库、pgvector存储与查询生成。模型是显式HTTP主题向量桩，因此这证明协议、结构、过滤、排序与一致性，**不代表真实供应商的语义质量验收**。临时设施退出后示例UUID不再可访问，按脚本重新运行可生成新ID与完整调用。

### 使用方式与剩余限制

已入库知识库直接POST /api/knowledge/search；kbIds必填，documentIds可选，topK默认5；minScore可按真实模型效果设置。来源原文仍走GET /api/documents/{id}/file授权读取。完整配置、请求、构建和验收命令见API/RUNBOOK，执行python scripts/knowledge-base/search_acceptance.py可重现流程，设置KB_SEARCH_EXAMPLE_OUTPUT导出实际调用。

本阶段仍为pgvector(1024)精确扫描，未建设HNSW；大规模延迟须在真实数据量评估。跨不同嵌入空间须分请求，尚无排名融合。真实模型供应商/生产S3/生产多实例与Docker应用镜像仍未联调，不能把本地协议桩当作语义召回质量。检索响应仅保证最终SQL快照时点的可见性，发送后资源可继续变化。

人工Chunk维护/删除、多格式、URL刷新、混合检索/回答和管理界面仍待后续阶段。下一轮为人工维护与删除，等待用户明确授权。

## 历史工作：第 4 阶段，可靠后台任务与重新入库（2026-10-22，已完成）

已先重读PLAN/PROGRESS并核对实现。复用现有同步内核、PostgreSQL/pgvector、S3、认证与AI服务；无既有MQ，因此使用数据库持久化队列、Spring @Scheduled和有界ThreadPoolTaskExecutor。本轮没有扩展到公共检索或人工Chunk维护。

### 实际改动与关键决定

- V4__reliable_ingestion_jobs.sql增量扩展job状态、次数、排队/截止时间、完整输入快照与retry_of；request_id按可信tenant/owner唯一，单文档PENDING/RUNNING唯一。新增kb_generation_cleanup持久化清理。V1–V3不修改；V3既有运行记录回填原模型绑定和文件信息，保留原deadline/maxAttempts=1，升级需停旧版本执行者。
- IngestionJobService接入快速提交、幂等重放、短事务领取、续租、取消、有限退避和过期恢复；锁顺序base→document→job，候选锁用SKIP LOCKED避免阻塞。相同requestId返回原任务；不同键同文档活跃任务409。同job自动重试使用原输入和目标generation，人工retry捕获当前文档版本/spec，创建新job/新generation并关联retryOf。
- IngestionJobController实现POST文档ingestion-jobs、GET全局job/stages、POST retry，并增加cancel接口；旧文档嵌套只读路由保留。复用统一Result/校验/分页/Basic与租户+用户私有归属，不接受请求actor/tenant/token。
- IngestionWorker/Configuration/Properties提供默认2线程、零缓冲队列，可配置有限容量；先预留本地容量再领取，满时不消耗DB attempt，拒绝则按token归还未启动任务。独立调度线程续租，续租不能复活过期租约或延长attempt总deadline。execution_started_at一次CAS拦截同凭证重复投递。
- IngestionContext增加attempt/claimToken/leaseOwner；阶段begin/end、AI修订、失败、归还与发布都校验实际执行权。失效执行者不能改新attempt日志/状态，取消后迟到模型异常不触发重试；进程中断保留租约等待恢复。初始最大3次，指数退避2s起并抖动、5m封顶；格式/维度/身份变化永久失败，网络/超时/限流等有限重试。AiEmbeddingClient按真实AI错误信封区分RATE_LIMIT/AUTH/INVALID_RESPONSE，未增加供应商旁路。
- ChunkIndexWriter仍在同一个短PG事务内批写Chunk/pgvector/generation，激活前后检查enabled、未删、最新job、文档version/config、模型/AI修订和有效claim。失败保留旧activeGeneration/chunk_count；成功检索仅返回当前代，未改变Chunk独立enabled。
- GenerationCleanupService/Scheduler对旧代和终态失败目标排队清理（失败事务通常没有残留，清理可幂等无操作）；自动重试目标与当前代受保护。清理同库事务全程持有文档锁，崩溃回滚可恢复；失败独立落库，默认5次后FAILED，修复后可按RUNBOOK重排。保留原对象、job与stage审计。上传对象补偿仍使用原人工storage-operations流程，不混用generation GC。
- 更新PLAN/API/RUNBOOK、静态OpenAPI、README与既有Compose/.env示例。新增标准库HTTP验收脚本scripts/knowledge-base/ingestion_acceptance.py，覆盖202/幂等/轮询/阶段与重新分块发布。没有新框架或新增依赖。

### 真实验证

环境：Java17.0.20、Maven3.9.16、Docker29.4.0；真实一次性PostgreSQL17.11/pgvector0.8.7、MinIO与AWS S3 SDK。模型均为本地HTTP协议桩，未调用真实供应商，不宣称语义质量或生产模型账户联调通过。

| 检查 | 结果 |
|---|---|
| 全10模块Maven verify | SUCCESS；168项，156通过，0失败/错误；12项为未配置的可选LocalPostgresTest跳过，Docker集成未跳过 |
| 后端 | 75项，其中63通过、可选本地DB12跳过；真实PG/MinIO上传与入库33，PG管理12，分块5，HTTP协议5，配置边界1，原流限制/异常/安全7 |
| 幂等与并发 | 8路同requestId提交只建1job，8路领取只1winner；重复投递同凭证不重复模型调用且不把正常任务记失败；归属/认证/未知字段、活跃冲突与retry幂等验证 |
| 实际进程重启 | 启动开启@Scheduled的Java进程，在真实HTTP EMBED期间destroyForcibly，第二个真实Java进程连接同DB/MinIO，自动过期接管同job，attempt=2/SUCCEEDED，8条当前代证据且无重复；旧attempt EMBED保存KB_INGESTION_EXPIRED |
| 失权与取消 | 过期claim不再续租/写阶段/钉AI修订/覆盖新状态或发布；新attempt成功后迟到旧结果与旧失败均被拒；执行中取消持久化CANCELLED且不自动重试；禁用文档不得激活新代 |
| 重试 | 实际HTTP429及AI 503/RATE_LIMIT识别为限流；3次退避后FAILED，无无限循环；错误维度一次永久失败；AUTH/INVALID_RESPONSE按真实协议不可重试；已有超时/空数组/乱序/数量错误等回归通过 |
| 重新入库与GC | 模型或向量INSERT故障保留旧证据；人工retry读取新spec，成功切换新generation，查询仅新代；GC故障事务回滚/持久化重试，恢复后删旧代；保护当前代；执行器饱和时任务PENDING/attempt=0，拒绝可重新领取；续租真实更新lease |
| 迁移 | 4迁移validate/重放；V1旧数据→V4，V3含真实运行job→V4均通过，回填输入快照且不改原deadline/maxAttempts |
| 真正HTTP服务 | 独立临时服务上依次实际执行acceptance.py、upload_acceptance.py和ingestion_acceptance.py；管理/上传继续可用，上传仍PENDING，202提交/200重放、完整6阶段、PATCH重新分块切换generation=2且chunkCount=1 |
| 文档和契约 | OpenAPI 17路径/24操作、186引用全部解析；Python语法、13个JSON示例、Markdown围栏/链接/空白与git diff --check通过 |

为避免Windows现有AI进程占用原target JAR的问题，沿用独立.cache/stage4-build构建，复用同一绝对依赖缓存并核对163个源文件/模块配置文件哈希。没有停止现有AI进程。最终复核再次全量verify成功；业务源码、POM、迁移与应用资源均与独立构建完全一致。验证过的backend JAR复制到常规target，新AI JAR另存为rag-ai-service-0.1.0-SNAPSHOT-stage4.jar，保留原运行物。产物SHA-256、统计与真实进程/HTTP证据见[STAGE4_VALIDATION.json](STAGE4_VALIDATION.json)；常规backend target中的阶段/重启日志和测试报告也已同步。本轮未提交或推送。

### 使用方式、恢复与剩余限制

上传→POST /api/documents/{id}/ingestion-jobs（body requestId）→轮询GET /api/ingestion-jobs/{jobId}及/stages。后台短事务领取→事务外取原文/解析/分块/模型批处理→PG短事务写入新代并切换activeGeneration→延迟回收旧代。重新分块先PATCH spec再提交新requestId；有活跃任务先等待或cancel。FAILED/CANCELLED可retry新任务，自动重试不重读当前spec。

RUNBOOK给出环境变量、迁移、POST/查询/retry/cancel示例、卡住任务与GC的检查/恢复SQL。默认worker启用（测试profile关闭），需AI descriptor/修订协议、私有bucket和pgvector1024；进程崩溃后自动等待租约/退避恢复，不手改claimToken/activeGeneration。当前模型/文档绑定保持既有不可变规则。

真实模型供应商、生产S3、生产多副本环境仍未联调；并发领取与失权已由真实PG事务/两个进程验证。Docker应用镜像整体部署仍需目标环境验证，本次验证为构建产物与真实本地进程/容器基础设施。未实现公共检索HTTP、Chunk维护/文档删除、其他格式/OCR、远程URL调度、混合检索/回答或界面。任务与阶段审计不自动过期，后续可按业务保留策略处理；GC只回收非当前代，不删除原文件。

下一阶段为向量检索，须等待用户明确授权。

## 历史工作：第 3 阶段，同步入库内核（2026-10-21，已完成）

已按本轮范围重读PLAN/PROGRESS并核对代码，沿用现有MVC、认证归属、S3 SDK、PostgreSQL/MyBatis-Plus、Flyway与AI服务。没有异步调度、公开长HTTP入库入口或新增模型供应商SDK。前轮P1仍可用，以下阶段记录为历史。

### 实际改动与关键决定

- 新增parser/{DocumentParser,ParsedDocument,ParserRegistry,TextDocumentParser,MarkdownDocumentParser}：Tika内容检测、原对象大小/哈希核对、严格UTF-8/BOM与换行规则；TXT自然段，CommonMark0.27.1+GFM AST识别标题/段落/列表/代码/表格，保留真实行号、章节路径、blockIndex与表格位置。重复profile+MIME认领启动失败；AUTO按已验证扩展名区分text/plain的TXT/Markdown。
- 新增chunk/{Chunk,ChunkingService,ChunkIndexWriter}：默认1024 code point/重叠128，表格最多50行、重复表头，容忍512；相邻同章节段落自然组合，长段继续拆，代码/列表按完整块或行，表格不截单元格；硬内容8192、embeddingText16384，超限明确422；WHOLE_DOCUMENT显式单块。content负责展示，embeddingText加一次章节前缀、表格转字段值；UUID由doc/generation/index/hash产生，token_count未知NULL。
- 新增model/{EmbeddingClient,AiClientProperties,AiEmbeddingClient}：复用AI原生协议。共享JDK HttpClient+有界BodySubscriber，连接/完整调用超时、全局并发2、最多10条且编码JSON64KiB拆批，服务端batchSize更小时服从服务端；按index重排并验证数量、维度、有限/非零float32、模型身份/版本。此处选择JDK客户端以严格限制完整慢响应体，替代D1拟议的RestClient，不引入第二套供应商映射。
- AI现有ApiTypes/AiController/ModelRegistry/AiService扩展descriptor、configVersion、expectedConfigRevision和实际provider/space/revision；显式指定模型或修订禁止fallback，无效/不可用明确失败。旧未指定模型与修订的客户端保留原兼容降级。metadata不包含密钥/内部URL、不做付费探测。
- V3__synchronous_ingestion_and_pgvector.sql新增public.vector扩展、kb_ingestion_job/stage_log/generation/chunk/vector(1024)，扩展doc状态/next_generation/latest_job_id与base.embedding_ai_config_revision，复合归属及向量身份外键。VectorStore只有PgvectorVectorStore：JdbcTemplate批写、精确cosine查询、按代/Chunk删除原语；本轮无公共检索或业务删除接口、无近似索引/第二后端。
- 新增ingestion/{IngestionContext,IngestionProperties,IngestionJobService,IngestionKernel,IngestionQueryController}：固定FETCH→PARSE→SPLIT→EMBED→PERSIST→PUBLISH。先短事务保留job输入快照/新代/有效租约；下载/解析/模型在事务外；Chunk+向量+generation+active切换与成功状态同一个30s事务，默认最多1000块、每批100。按base→doc→job锁序验证version/config/latest job/token/真实时钟租约，失败不覆盖旧代。
- 首次成功发布原子钉住AI配置修订，后续metadata漂移在POST embedding前409；发布和数据库外键再检查，避免跨运行混空间。有效PATCH把文档置PENDING并保留旧activeGeneration；执行中输入变化拒绝发布。同步job只RUNNING/SUCCEEDED/FAILED，attempt=maxAttempts=1，无自动重试；再次内部调用创建新job/新代，过期运行可显式恢复且下一次ingest自动先处理，旧worker无法发布。
- 新增只读GET /api/documents/{id}/ingestion-jobs、/{jobId}、/{jobId}/stages，复用认证/分页/错误格式；POST集合405。阶段日志有真实计数/单位/clock_timestamp耗时，错误为固定脱敏短消息。内核不包含Controller逻辑。
- 更新PLAN/API/RUNBOOK、静态OpenAPI、相关README；交付[SAMPLE_CHUNKS.json](SAMPLE_CHUNKS.json)，来自671字节Markdown样例，实际生成并存储8个Chunk，展示文本和embeddingText均可检查。

### 真实验证

环境仍为Java17.0.20、Maven3.9.16、Docker29.4.0、一次性PostgreSQL17.11/pgvector0.8.7和真实MinIO。向量供应商使用本地HTTP协议桩，**没有调用真实供应商或支付模型费用**。

| 检查 | 结果 |
|---|---|
| 最终全10模块Maven verify | SUCCESS；154项总计，142通过，0失败/错误；12为未配置的可选LocalPostgresTest跳过，容器集成没有跳过 |
| 后端 | 49项通过：真实PG管理12、真实PG/MinIO上传与同步内核集成21、结构分块5、模型HTTP协议4、流限制1、原安全/异常6；另可选本地DB12跳过 |
| AI协议 | 10项通过，含descriptor与修订漂移拒绝、指定模型失败/禁用不降级，以及原路由、流式、重排回归 |
| 完整同步链路 | 真实S3读原文→Tika/UTF-8→CommonMark结构块→8个Chunk→HTTP适配批量向量化→实际vector(1024)持久化及精确查询；核对每个index对应的embeddingText、章节/行号/表格元数据、6阶段日志、归属只读查询 |
| 模型失败 | 空数组、缺条、重复index、乱序、1023维、NaN字符串/零向量、过大响应、到首头超时及慢响应体超时均覆盖；身份/修订变化和不可用明确失败，无代换模型 |
| 发布/恢复 | 真实向量INSERT触发器故障回滚generation/Chunk/vector与发布；模型失败保留旧active，重试代号不复用；调用模型期间修改文档阻止发布；过期job恢复且陈旧worker被拒；首发钉住AI修订，后续变更不再调用POST且保留旧证据 |
| 结构/边界 | 标题路径、相邻段合并/跨结构不误合并、Unicode代理对不切断、重叠、表头重复/整行分组、字段语义文本、整篇模式、超硬预算和无可入库内容、解析器重复认领测试通过 |
| 迁移/兼容 | V1旧数据→V3升级成功，未自动绑定模型；3迁移validate和重复执行通过，P1管理/上传安全和补偿测试继续通过 |

原工作目录root verify在AI重打包时被Windows文件锁阻止：有10月18日启动的现有AI进程使用原target JAR，协议测试已通过但rename失败。未停止这些进程；将155个构建源码文件复制到.cache/stage3-build，复用原依赖缓存完成最终verify，并核对业务源码/POM/迁移/应用资源哈希一致。业务后端可执行产物已复制回常规target；新版AI另存rag-ai-service-0.1.0-SNAPSHOT-stage3.jar，避免覆盖现有进程使用的路径。无业务源码因构建问题而改写。

OpenAPI为13路径/19操作，152引用解析有效；脚本/JSON/Markdown/链接/空白和产物资源一致性检查已完成。Testcontainers自动回收；未启动新增业务调度器或生产模型调用，未提交或推送。

### 使用方式与剩余限制

RUNBOOK给出精确验收命令、内部kernel.ingest(principal,documentId)、只读任务/阶段查询和显式恢复方法，以及S3/AI/预算配置。上传不自动入库；接口仍只提交原文PENDING。样例源为services/backend/src/test/resources/knowledge-base/sample.md，完整Chunk在docs/knowledge-base/SAMPLE_CHUNKS.json，本地导出也位于services/backend/target/knowledge-base/sample-chunks.json。

需迁移账户安装/启用public.vector，实际维度固定1024；无pgvector扩展的旧本地数据库不能运行本阶段。部署真实AI前更新服务以提供descriptor/修订校验，模型密钥仍在AI端，backend仅访问受控内网/网关。metadata的active表示配置启用，不证明供应商账户与固定模型版本已联调；真实供应商、真实生产S3仍需设施接入验证，不能把HTTP桩结果当作语义质量验收。

本轮没有自动扫描、自动重试、任务提交202、人工Chunk维护、公开知识检索、URL/OCR、回答或界面。历史代保留，尚无GC；deadline在阶段边界、读取循环与模型等待中生效，S3阻塞另受配置超时，不声称可强制中断CPU解析。VectorStore删除为内部事务原语，不是已交付文档/Chunk删除业务。

本轮无必须用户补充才能完成实现的阻塞问题。下一轮仅在明确授权后做可靠异步任务（PLAN P3），复用本轮内核、job和日志，不提前增加调度。真实模型联调需要部署方确认服务地址/固定版本策略与安全凭据注入，不在聊天发送密钥。

---


## 历史：第 2 阶段，基础管理与本地上传（2026-10-21，已完成）

用户本轮“第2阶段”对应PLAN的P1。已先读方案/进度并复核当前代码，无适用AGENTS.md；扩展已有业务后端，不重建服务、不改V1、不扩展后续解析/检索阶段。以下D1记录为历史，当前能力以本节与API A–D为准。

### 实际改动

- 复用Spring MVC/Bean Validation、MyBatis-Plus3.5.17、PostgreSQL、Boot管理的Flyway11.7.2与现有Basic/principal/统一Result、版本CAS和真实错误码。新 `/api/kbs` 与旧 `/api/v1/knowledge-bases` 共用服务；创建、详情、名称字面筛选、分页、PATCH重命名和空库删除均实现。
- 新增model/EmbeddingCatalog及GET `/api/kb-models`：只从服务端enabled目录绑定，保存modelId/provider/1024维/配置SHA-256/配置快照/服务端UUID namespace和审计。绑定不可变，变更409明确提示新库重建。旧库保留UNBOUND，显式PUT embedding-binding后才允许上传。
- 新增V2__documents_and_upload_operations.sql：扩展现有kb_knowledge_base，新增kb_document、kb_upload_operation，包含复合归属外键、大小/状态约束、名称和幂等唯一索引、恢复索引。不创建Chunk/vector/ingestion_job/refresh_schedule或启用向量扩展。
- 新增document/{Document,DocumentMapper,DocumentTypes,DocumentService,DocumentController,PreparedUpload,UploadOperations}：TXT/Markdown multipart上传、列表/名称/状态筛选、详情、版本化名称/spec修改和授权流式原文读取。入库spec严格校验；所有上传保留PENDING、generation=null、chunk_count=0，无模型调用。
- 新增storage/{ObjectStorageClient,StorageProperties,StorageConfiguration,S3ObjectStorageClient}，AWS SDK2.55.8，提供put/open/delete；Tika Core3.2.3仅做内容检测。扩展persistence/JsonbTypeHandler与现有错误处理。稳定objectKey不拼接原名，默认20MiB、磁盘spool/8KiB计数哈希、严格UTF-8/真实MIME检测，不把整文件载入内存。下载attachment/no-store/nosniff，先授权后S3访问。
- 上传在S3 PUT前提交持久化意图，PUT在事务外；文档与COMPLETED在同一短事务提交。失败先确认无已提交引用，再领取清理租约并事务外DELETE。失败保留CLEANUP_PENDING、次数、建议重试时间与固定脱敏code；数据库不可用/提交不确定时保留意图，不盲删。提供归属隔离的存储操作分页与retry-cleanup，claim_token防止过期结果覆盖；活跃租约及有文档引用对象受保护。未完成操作也阻止删库。
- 扩展原Compose加入私有MinIO及bucket初始化，环境示例不含真实密钥。新增scripts/knowledge-base/upload_acceptance.py，保留旧acceptance.py兼容验收；更新PLAN/API/RUNBOOK、静态OpenAPI和README。

### 关键决定及与D1差异

1. 本阶段只保存模型配置，不需模型HTTP调用。后端允许目录默认与现有AI的qwen-embed/bailian/text-embedding-v4/1024一致，**不是供应商在线探测结果**。AI不可变元数据/expectedConfigRevision协议延至P2，开始向量化前必须校验真实身份、空间、维度与修订；禁止fallback混用。未改AI服务或传入供应商密钥。
2. 本轮默认路由为用户指定 `/api/kbs`、`/api/documents`，旧知识库CRUD继续兼容。绑定一经建立即锁定，比“有向量才锁定”更严格，避免绑定语义漂移。首版向量后端仍仅pgvector，本轮没有向量列/索引。
3. 文档显示名在库内按存活lower(name)唯一，比D1允许重名更严格；内容相同且名称/幂等键不同允许上传，没有内容去重。上传幂等键保留，失败键完成清理后也不复用。配置实质变化才configRevision+1，所有有效PATCH version+1。
4. kb_upload_operation是上传/清理意图，不是解析任务或完整Outbox框架。**本阶段只提供人工查询与重试，没有自动扫描器**；状态可跨进程恢复，自动调度留给可靠任务阶段。S3和本地事务不是分布式原子提交；临时磁盘崩溃残留与外部管理员修改对象需按RUNBOOK处理。

### 真实验证结果

本机Java17.0.20、IDEA Maven3.9.16、Docker29.4.0。只用一次性PostgreSQL17.11（pgvector镜像0.8.7-pg17-bookworm，未启扩展）和MinIO RELEASE.2025-09-07T16-13-09Z；不接触系统PostgreSQL或业务数据。

| 验证 | 真实结果 |
|---|---|
| 根Maven verify，最终全10模块 | SUCCESS；136项总计，124通过，0失败/错误，12跳过（未配置的可选LocalPostgresTest第二套环境） |
| 后端 | 33项通过：原真实PostgreSQL管理12、上传/模型/迁移集成14、流超限1、既有错误/认证配置6；可选本地数据库12项跳过；PostgreSQL/MinIO容器测试均未跳过 |
| 正常上传/下载 | 实际AWS SDK对MinIO PUT/GET/HEAD/DELETE；UTF-8 TXT/Markdown、内容MIME覆盖客户端伪造类型、原名basename、大小/SHA-256、配置默认值、PENDING状态和下载原始字节验证 |
| 输入/隔离 | 空文件400、声明与实际流超限413、二进制/非UTF-8/不支持类型415、非法spec/未知归属字段400、库/文档重名409、跨用户/跨租户404、未认证原文401且无存储open调用 |
| 一致性/恢复 | 真实数据库触发器使文档INSERT失败，已存对象被补偿删除；注入删除失败保留CLEANUP_PENDING并经API成功重试；PUT结果丢失可补偿；COMPLETED对象不能清理；过期意图可清理、活跃租约不可；上传暂停时其他请求能读状态、重复提交/删库拒绝，最终只一个文档 |
| 升级与兼容 | 在独立schema先执行V1插入旧数据再V2，数据/归属保留且未自动绑定；显式绑定接口与跨归属拒绝通过；原元数据CRUD和Flyway校验/重复执行通过 |
| 可执行JAR的真实HTTP | 独立Compose项目kb-stage2-check创建PostgreSQL/MinIO/bucket，JAR端口18080启动；acceptance.py与upload_acceptance.py通过，包含20MiB+1字节请求413。重启JAR后文档/版本/key/COMPLETED仍在，原文下载SHA-256一致 |
| 文档/部署 | Compose config校验及设施启动/bucket初始化成功；OpenAPI YAML解析10路径/16操作/124引用可解析；脚本语法、JSON示例与diff空白检查完成。应用Docker镜像本轮未构建，完整应用容器启动未验证；可用JAR+Compose设施路线已实测 |

验收完成后停止本轮JAR，回收仅本轮创建的kb-stage2-check容器、网络与两个临时卷；Testcontainers自动回收。验收脚本自身会保留非空库并打印ID，RUNBOOK明确这一行为；本次因整个独立测试环境回收而一并移除测试数据。原工作区已有未提交内容保留，不提交/推送。

### 使用与限制

按RUNBOOK配置数据库、可信账号与S3凭据→启动私有bucket→运行JAR，GET模型目录后创建库，再multipart上传。API A–D及OpenAPI给出可调用路径，RUNBOOK含PowerShell请求、两个Python验收命令及失败恢复命令。密钥只通过环境/默认凭据链或外置配置提供。

生产S3/OSS、供应商真实模型尚未联调；本地MinIO已完成真实SDK联调。默认storage=false，未配S3时元数据仍可用、上传503。模型目录是服务端允许配置，实际模型服务身份与维度握手仍是后续前置项。没有自动清理扫描、解析、Chunk、向量、检索、URL、文档删除/启停、回答或界面；本轮不扩展。

下一轮须再次读PLAN/PROGRESS，仅在明确授权后实施同步入库内核（PLAN P2）。本轮没有需要用户补充才能正确实现的阻塞问题；部署到真实环境时需提供S3 endpoint/region/bucket/path-style和安全凭据注入，模型联调时再提供服务地址/固定版本策略，不在聊天发送密钥。

---


## 历史：D1 调研与设计（2026-10-21，已完成）

本轮按用户明确范围只调研、设计和维护文档。**不修改业务代码，不增加依赖、迁移、配置或公开接口，不启动下一实施阶段。**已有知识库元数据代码保留复用，不能把上轮实现回滚，也不能把新增设计当作已有功能。

### 仓库与记录对齐

- 已先读原 PLAN.md/PROGRESS.md，再检查根/子模块POM、两个启动类及配置、认证/归属、V1迁移、知识库实体/Controller/Service/Mapper、HTTP客户端、AI Registry/协议/供应商映射、缓存与线程工具、测试、部署、前端/Agent目录。
- 未发现适用AGENTS.md。当前已有backend元数据CRUD及安全边界；缺文档/对象存储、解析、持久化任务、Chunk/vector、搜索、刷新与前端工程。PLAN第1节逐项记录实际路径、复用接口与差距。
- 原PLAN中的“没有ORM/认证、backend原为空”等是首次建设历史，不是当前事实；本轮计划已按实际实现重写。逻辑kb_base映射现有kb_knowledge_base，通过后续增量迁移扩展，避免双表/双CRUD。
- 现有RUNBOOK与静态OpenAPI没有扩写，仍只说明可调用的元数据功能；API.md以A部分“已实现”、B–J部分“拟实现”标明能力边界。
- 新的D1与P1–P9编号取代原五阶段规划；下面保留的历史“第一阶段已完成”仅指上一轮元数据基础，不表示本轮设计已实现。

### 本轮交付

| 文档 | 已完成内容 |
|---|---|
| PLAN.md | 能力盘点/差距、关系库/S3/单一pgvector/解析/AI/任务/调度映射；必要依赖与配置；七类核心职责、复合归属约束及profile/generation/幂等/Outbox/人工策略辅助账本；事务/fencing/发布/刷新规则；按指定顺序的P1–P9实际拟新增路径、步骤、依赖和验收；完整调用链与所需设施信息 |
| API.md | 当前CRUD兼容契约；未来上传/查询、入库任务及日志、Chunk维护/独立启停、检索证据、URL导入、刷新计划与回答接口；分页/错误/状态码、幂等与CAS、权限和状态机；AI配置元数据与expectedConfigRevision增量协议 |
| PROGRESS.md | 本轮事实、关键设计决定、验证边界、问题和下一步，保留上轮真实测试记录 |

### 关键决定

1. 继续Java17/Boot3.5.16/MyBatis-Plus/PostgreSQL17/Flyway。首版只有PgvectorVectorStore；ES仅P8可选BM25，不做第二个向量后端。
2. 依据现有qwen-embed配置与百炼官方协议，首版固定bailian/text-embedding-v4/1024维。column固定vector(1024)，与profile、模型实测返回及索引约束共同校验；没有真实模型成功调用的新增证据。
3. AI当前响应仅含逻辑alias/dimensions，缺provider/space/revision。设计P1/P2最小兼容增量：模型描述、期望AI修订、响应身份；KB完整profile修订与AI配置修订分层，不能只认“同维兼容”。
4. 归属来自现有KnowledgeBasePrincipal，首版private owner，不建设IAM；namespace/objectKey/修订/操作人不能由请求自由传入。
5. P1先安全上传和可恢复对象确认，P2同步内核已包含向量化/存储/原子发布，P3复用内核做异步可靠性，P4才开放检索API。全部外部IO在短DB事务外。
6. 文档enabled与Chunk人工enabled独立；人工策略有跨代账本，无法对齐时WAITING_REVIEW，文档重新启用不恢复人工禁用Chunk。人工正文修改以新正文/新向量同代发布。
7. 任务/阶段/代/租约/token持久化；claim、续租、批写与发布都做fencing；过期重试耗尽转FAILED，不能永久RUNNING。
8. URL默认关闭。远程检查下载与固定源输入的INGEST子job分离，只有子job发布才确认源validators；安全出站策略缺失时不启用，避免“正则校验即安全”的假设。

### 本轮验证与限制

本轮只做文件/协议/文档一致性检查，不重跑Maven、不执行SQL、不启动Docker/数据库/应用，不调用真实S3、模型、MinerU或ES。下列是文档验证，不是未来功能验收：
- 盘点表路径与仓库清单、现有V1模型/CRUD/错误契约交叉核对。
- 对照七类必需数据职责，检查generation/config/manual revision、独立启停和实际维度的关系。
- 检查P1–P9顺序、配置默认值、现有/拟新增状态、API/PLAN的幂等/任务/刷新语义。
- 检查文档JSON示例、内部链接与Markdown结构，并用本轮开始时的文件SHA-256快照核对只改三份指定文档；最终结果在本节末记录。
- 阅读官方pgvector、百炼同步Embedding、Spring CronExpression、PostgreSQL与AWS SDK文档；来源链接集中在PLAN和相应决策旁。供应商真实账户可用性/静默权重变更无法由本轮调研确认。

上轮121项测试通过及重启/停库恢复只证明当前元数据实现。它们不覆盖本轮新增设计的上传、任务、向量检索或刷新；不能用历史测试数替代新功能联调。应用Dockerfile/整套Compose在历史记录中尚无成功实跑证据，本轮未改变此结论。

最终文档检查结果：本轮开始/结束的SHA-256快照差异恰为PLAN.md、PROGRESS.md、API.md，其他145个受检文件/缺失占位状态保持一致；无业务代码、依赖、迁移、配置或运行手册变更。七类数据模型必需字段核对无缺项；P1–P9顺序正确；14个JSON示例可解析，代码围栏/表格列数/现有内部文档链接检查通过，git diff --check通过。此结果为只读调研与文档检查，不代表已实现新接口或完成外部服务联调。

### 下一轮与真正所需信息

本轮没有必须向用户提问的阻塞问题。下一轮若授权实施，范围应从P1“基础管理与上传”开始，先复用CRUD并新增V2，不自动执行P2。

真实设施接入前，仅需用户/部署方提供：
- S3 endpoint/region/bucket/path-style要求及安全凭据注入或工作负载角色；
- 百炼实际区域/工作空间、选定模型可用/固定版本策略、AI服务地址与密钥注入方式；
- P7开启前的URL允许域名及安全出站代理/ACL；
- 仅开启可选功能时的ES版本/连接、MinerU协议与OCR/VLM设施。

不要求在聊天中发送密钥，不再询问已从仓库确定的Java/ORM/归属规则，也不要求同时选择第二种向量库。完整“上传→可检索证据”链路见PLAN第6节。

---

## 历史记录说明

以下保留上一轮元数据实现与实际验证事实；其中旧阶段编号、旧“下一步”已由本轮PLAN的P1–P9替代，不构成本轮实施授权。

## 历史：元数据基础实现（2026-10-19）

初始核对：PLAN/PROGRESS 不存在，backend 仅占位；无 AGENTS.md；没有可复用业务数据库/认证，已有 AI 服务保持复用方向。本轮范围是元数据 API 和运行基础，文件、模型调用和检索不在本轮实现范围。

### 完成项与关键决策

- services/backend 接入根 Maven 构建，沿用 JDK 17 / Boot 3.5.16、公共 Result/PageResponse/Web 组件。没有重构 AI 服务或其他模块。
- PostgreSQL + MyBatis-Plus 3.5.17；Flyway 11.7.2 core 和 PostgreSQL 模块由已有 Boot BOM 管理，JDBC 42.7.11。V1 建立知识库表、存活名称唯一索引和归属分页索引；启动校验、禁止 clean 和自动 baseline。
- Spring Security 无会话 Basic，环境/外置配置绑定账号的 tenantId/userId；无默认可用身份，密码在运行时 BCrypt。读、写、列表、计数全部限定可信 tenant + owner；对非本人、其他租户、墓碑统一 404。JSON 归属字段被拒绝，租户头不参与授权。
- POST/GET/PUT/DELETE 私有知识库 API、真实 HTTP 错误码、请求 ID；同一所有者内 PostgreSQL lower(name) 唯一、版本 CAS 更新/删除、删除墓碑、分页快照。PUT 完整替换可变元数据，省略 description 会清空。
- OpenAPI 静态文档、Python 标准库 HTTP 验收脚本、Dockerfile/受限构建上下文、Compose/环境示例、API 与运行手册。第一阶段不启用 vector 扩展，不引入解析/模型/任务框架。

### 真实验证结果

环境：Java 17.0.20、IDEA Maven 3.9.16。最初 Docker daemon 未运行，后续恢复可用。新建项目缓存中的独立 PostgreSQL 18.3 临时集群（127.0.0.1:55439），不接触已有 PostgreSQL 服务或业务数据库；另外用 Testcontainers 的 pgvector/pgvector:0.8.7-pg17-bookworm 运行 PostgreSQL 17.11。

| 验证 | 结果 |
|---|---|
| 根 Maven `verify`（配置 KB_TEST_JDBC_URL，最终使用默认容器镜像） | 全部 10 个模块成功；121 tests，0 failures/errors/skipped |
| 新后端测试 | 30 项通过：真实 PostgreSQL 17、18 各 12 项行为测试，另 6 项认证配置/故障单元测试 |
| 数据库行为 | 创建/持久化读取/分页/更新/软删除；跨用户与跨租户读写拒绝；伪造归属拒绝；并发更新只有一个成功；重名与重命名失败回滚；陈旧版本拒绝；迁移 validate 与重跑无损 |
| 输入和失败 | 空白/Unicode 空白、超长、NUL、错误 JSON/UUID/分页/版本、未知字段、数字到文本/浮点到整数拒绝；未认证/错误密码；错误响应隐藏内部信息；请求 ID 一致 |
| 可执行 JAR 的真实 HTTP 验收 | 在 127.0.0.1:18082 启动打包产物，`python scripts/knowledge-base/acceptance.py` 全部通过，覆盖认证、健康、OpenAPI、CRUD 与冲突；自动软删除验收数据 |
| 真实停库与恢复 | 停止独立临时 PostgreSQL，health=503/DOWN，列表与创建 API=503/KB_DATABASE_UNAVAILABLE；恢复后同一应用进程可读取原记录，失败创建的记录数为 0 |
| 应用进程重启 | 重启 JAR 后详情与已提交记录完全一致，Flyway 校验/启动正常 |
| 部署静态检查 | `docker compose ... config --quiet` 通过；Python 语法检查与 `git diff --check` 通过 |
| 完整应用容器部署 | 未完成：Temurin 17 JRE 基础镜像拉取发生 EOF/short read；数据库目标镜像的容器集成已验证，不能据此声称 backend Dockerfile 构建或整套 Compose 已实跑 |

本地原始验证输出在忽略的 .cache/kb-final-verify.log、各模块 target/surefire-reports、.cache/kb-app*.log；它们不是交付依赖。验收完成后已停止本轮启动的应用和独立数据库进程，临时数据保留在被忽略的 .cache 目录，18082/55439 不再监听。

### 问题、修复与限制

- 测试发现当前 Bean Validation 的 NotBlank 不能阻止全角空格在 strip 后变为空名；补充服务端归一化后校验，返回 400，避免落到数据库约束错误。测试发现 Jackson 的 scalar coercion 开关不包含数字到 String；显式禁止文本类型的数字/布尔转换，保持 OpenAPI 类型契约。相应回归已通过。
- Flyway 11.7.2 对 PostgreSQL 18.3 有“最新正式支持版本为 17”的提示；18.3 实测不代替官方支持承诺，默认部署使用已验证的 PostgreSQL 17。
- 当前身份绑定来自部署配置，尚未接入独立 IAM/用户管理或共享授权；账号归属应保持稳定，配置变更重启生效。生产须使用 HTTPS 入口与受控密钥配置。
- 不提供文件、S3、任务、解析、向量、重排、混合检索、回答、定时同步或前端；相关表与接口仅在 PLAN 中规划，没有伪实现。没有调用真实模型或存储供应商接口。

### 当时的下一步（已由新计划替代）

第二阶段：文件与持久化入库任务；实现 ObjectStorageClient/S3 适配、上传校验与 objectKey、数据库任务状态/租约/重试/查询恢复、事务外解析及补偿；接入文档后扩展知识库删除流程，避免遗留对象/任务。开始前再次核对 PLAN/PROGRESS、实际代码和新增设施配置。本轮到此停止。
