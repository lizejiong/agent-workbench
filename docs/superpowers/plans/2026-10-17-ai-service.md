# AI 服务首版实施计划

> **给 agentic workers：** 使用 superpowers:subagent-driven-development 按任务执行；规格检查通过后进行质量检查。用户明确要求精简测试，此要求优先于技能中逐项铺测试的默认方式。

**目标：** 实现服务自有业务的独立 Java AI 服务，提供 Chat、图片理解、向量化、精排及最小可靠性能力。

**架构：** 一个 Spring Boot 模块 `services/ai-service`，使用 WebFlux/WebClient 和 Reactor 处理流式、超时与取消，复用 `rag-convention` 的四字段响应契约。供应商地址、密钥、模型、能力和路由全部留在 YAML/环境变量中；服务不保存会话、执行工具或承担 Agent 编排。使用进程内熔断与并发控制，不引入数据库或模型网关产品。

**技术栈：** Java 17、Spring Boot 3.5.16、Jackson、WebFlux、Actuator；使用 IDEA 自带 Maven 和现有本地依赖缓存。

---

## 已确认范围

1. 普通与 SSE Chat；多轮消息、思考开关、FAST/STANDARD/STRONG 档位、优先逻辑模型；工具定义/调用的规范化传递、可选 JSON 对象输出。
2. 图片理解支持图片 URL 或 Base64，复用 Chat 消息与调度，工具实际执行由后续 LangGraph 服务负责。
3. 文本单条/批量向量化、指定逻辑模型、维度校验、批次拆分和稳定输入顺序；同一向量空间内才允许部署切换。
4. 精排整组文档，保留 chunkId、原始索引、相关性分数，返回 Top N；不拼接多次精排请求的相对分数。
5. 百炼、DeepSeek、硅基流动的配置与适配；模型能力声明，启动时检查模型/供应商引用、URL、参数以及 Chat 档位候选。
6. 有限次故障切换、进程内 CLOSED/OPEN/HALF_OPEN 熔断恢复、全请求时间预算、连接/首个有效片段/流空闲超时、并发与输入上限。
7. 流首个有效思考/正文/工具片段前可以切换，暴露模型内容后只报告错误；忽略心跳/空 role，取消必须关闭上游并释放并发和探测许可。
8. 普通错误统一分类与适当 HTTP 状态；SSE 用类型化事件表达错误。追踪实际模型、供应商、请求 ID、尝试次数、首包及总耗时、真实 usage。辅助提供粗略 Token 估算与显式代码围栏清理。

## 文件职责

| 路径 | 职责 |
| --- | --- |
| `pom.xml` | 聚合新模块；保持现有公共组件不变 |
| `services/ai-service/pom.xml` | 可执行 Boot jar，WebFlux/validation/actuator/convention；少量测试依赖 |
| `services/ai-service/src/main/java/com/lzj/rag/ai/AiApplication.java` | 应用入口 |
| `.../api/` | 紧凑请求/结果/流事件 DTO、HTTP 入口、响应/错误/请求 ID |
| `.../config/` | 配置绑定、校验、能力与模型/任务注册 |
| `.../provider/` | 统一供应商接口、WebClient 通信、报文与流事件转换 |
| `.../service/` | 调用流程、路由、时间预算、批次与结果校验 |
| `.../resilience/` | 小型进程内熔断和并发许可 |
| `.../util/` | Token 粗略估算、最外层代码围栏清理 |
| `services/ai-service/src/main/resources/application.yml` | 三家供应商、模型、档位与限制示例 |
| `services/ai-service/src/test/java/com/lzj/rag/ai/` | 约 6–10 个核心行为测试，集中 1–2 个类；按关键行为覆盖，不逐字段枚举 |
| `services/ai-service/README.md`、`README.md` | 中文配置、IDEA 启动、接口、SSE、Python 调用与限制说明 |

## 内部接口约定

```text
POST /ai/chat                  普通 JSON Chat
POST /ai/chat/stream           SSE Chat
POST /ai/vision                图片理解，复用 Chat 消息
POST /ai/vision/stream         图片理解 SSE
POST /ai/embed                 单条或批量文本向量化
POST /ai/rerank                文档块精排
POST /ai/utils/estimate-tokens 粗略 Token 估算
POST /ai/utils/strip-code-fence 显式清理最外层代码围栏
GET  /actuator/health          进程健康
```

```json
{"text":"今天星期几","tier":"STANDARD","thinking":false,"preferredModel":"chat-primary"}
```

Chat 的 text/messages 二选一；默认调用只需要 text。可选项为 task、tier、preferredModel、thinking、json、maxOutputTokens、tools。多轮消息携带 role/content、可选 images、toolCallId、toolCalls、reasoning，允许把工具调用相关思考内容原样传回上游。图片在 user 消息中，URL 与 Base64 二选一，Base64 附 mediaType。

```json
{"texts":["第一条","第二条"],"model":"embedding-primary","dimensions":1024}
```

向量请求 text/texts 二选一；model 和 dimensions 可省略以使用配置。结果始终为有原始 index 的向量列表，附实际逻辑模型、维度、usage 和耗时。

```json
{"query":"如何申请退款","documents":[{"id":"chunk-1","text":"退款规则"},{"id":"chunk-2","text":"配送说明"}],"topN":1}
```

普通响应使用 code/message/data/requestId。SSE 事件区分 start、reasoning、content、tool、usage、done、error；data 含 requestId、模型以及对应片段/元信息。响应头 X-Request-Id 与正文一致；请求 ID 在 Reactor 上下文传递，不使用 Servlet ThreadLocal。

## 固定行为规则

- Chat 优先模型必须存在且能力兼容；先尝试优先模型，再尝试选定任务/档位的去重候选。每个候选每次逻辑请求最多尝试一次，全部尝试共享次数和总时长预算。
- 普通上游调用与流式均保留取消语义。网络、超时、限流、服务不可用、无效上游响应按分类切换；调用方参数错误与取消不触发重试/熔断失败。认证错误有明确分类，不暴露密钥和供应商原始正文。
- 熔断按逻辑模型管理；冷却后仅放行一个真实业务请求探测，成功恢复，失败重新打开；取消释放探测许可。过期的并发调用结果不能关闭新一轮熔断。
- 首包超时只等待有效内容，思考片段也算；空 chunk 和心跳不延长等待。流开始后禁止拼接另一个模型的输出；必须识别正常结束，不能把断流当成功。下游取消传播给 WebClient。
- 向量默认锚定配置的默认模型（不是任意可用模型），指定 model 时锚定指定模型；只在显式相同 embeddingSpace 且维度相同的候选间切换。完整批次失败不返回残缺成功结果；检查索引、向量条数、维度与有限数值。
- 精排一次处理整组文档，校验返回索引，稳定排序，Top N 大于文档数时最多返回全部文档。默认不返回冗余原文。
- 百炼精排使用其原生嵌套 input/parameters 报文与可配置完整 rerank URL；硅基流动使用其 rerank 报文；Chat/向量/图片适配兼容格式，思考参数按供应商转换。DeepSeek 思考开关使用 thinking.type，百炼/硅基流动使用 enable_thinking。
- usage 只表示供应商实际返回值；缺失不伪造成精确统计。Token 估算标为 estimated，不用于准确计费或静默截断。清围栏仅处理整个输出由单个最外层代码块包裹的情况，不改写正常 Chat 输出。
- 启动校验只做本地结构、能力与候选检查，不调用付费模型探测。真实调用需配置至少一家提供商密钥；缺失密钥的提供商不参与路由。提供 ai.enabled=false 供无密钥进程启动和接口文档查看；该模式模型端点返回服务未启用。

## 实施步骤

- [x] **步骤 1：建立模块、配置和公共报文。** 增加 Boot 模块与父聚合；实现紧凑 DTO、配置绑定/校验、任务注册与错误分类，保留其他服务占位。
- [x] **步骤 2：实现供应商与调用流程。** 提供三家报文转换、WebClient JSON/SSE 与状态分类；实现档位选择、时间预算、有限切换、熔断恢复和取消；实现向量拆批与精排校验。
- [x] **步骤 3：接入 HTTP、追踪和辅助工具。** 配置 WebFlux 请求 ID/异常响应、普通接口与 SSE 事件、输入限制、实际 usage/耗时记录；加入估算与清围栏工具。
- [x] **步骤 4：精简验证与文档。** 用户明确要求控制测试体积，只保留少数核心集成/流程用例；配置/DTO/工具不单独铺测试。补齐中文使用说明，使用 IDEA Maven verify 和本地模拟上游验证可执行 jar 及 Python HTTP 调用。

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository' verify
java -jar services/ai-service/target/rag-ai-service-0.1.0-SNAPSHOT.jar --ai.enabled=false
```

预期：全部 Maven 模块通过，AI jar 可启动；关键测试验证档位/切换/熔断、流式首包与取消、向量顺序/维度/空间、供应商报文和精排关联。无需真实供应商密钥即可完成离线验证；真实供应商联调单独说明。

- [x] **步骤 5：规格与质量检查、本地集成。** 修复真实问题并完成复核，提交到 codex/ai-service，再快进合并 main；保留功能分支，归档临时工作区，不推送远程。

## 执行记录

- 基线为 main 的 `c0885f4`。工作区 `C:/Users/lzj/.codex/worktrees/ai-service/rag`，分支 `codex/ai-service`。
- 用户最新约束：减少测试代码，精简。只为关键跨边界行为编写少量测试，不继续扩张既有 Java/Python 测试。
- 已实现单模块 WebFlux 服务，首轮 IDEA Maven 全仓 verify 成功，9 个模块通过；新增 8 个核心测试集中在单个约 200 行测试类。使用本地 HTTP 假供应商，不调用真实付费模型。
- 初步代码检查已统一首包/空闲/总预算，避免超时前获取熔断许可导致泄漏；网络与内部错误分开分类；保留向量空间身份，补齐硅基流动精排 usage 映射。独立视觉模型配置为 qwen-vision，文本聊天模型不误声明视觉能力。
- 完整 Maven verify 共 91 个测试通过（既有 83 个、新增 8 个）；最终新增测试为单类 195 行，没有扩张其他公共组件测试。补充缺失 role 的输入回归断言后，模块 verify 再次通过。
- 使用 Python 标准库连接打包后的 Java 进程及本地模拟上游：健康检查、普通 JSON、SSE、11 条向量按两批处理并恢复逆序响应、精排 ID 关联、客户端断开后上游 socket 取消均通过。脚本及日志留在忽略的 .cache 中，不增加仓库测试体积；未使用真实供应商凭据。
- 规格审查已通过至 `86e4316`，未发现范围缺失或额外扩张；进入最终代码质量审查。
- 独立最终代码质量审查通过，确认流取消、切换边界、总预算、熔断代次、供应商映射及向量空间约束没有需修复的重要问题。
- `05964ab` 实现服务，`86e4316` 在现有测试内补回归断言；代码已快进合并至本地 main。合并后再次使用 IDEA Maven 执行 `-pl services/ai-service -am verify`，4 个相关模块成功，9 个既有 convention 测试和 8 个 AI 测试通过；主目录生成可执行 jar。功能分支保留，未推送远程。
