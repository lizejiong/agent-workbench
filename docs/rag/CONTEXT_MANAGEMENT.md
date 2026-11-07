# Agent 上下文管理调研与实施建议

2026-11-07。状态：第一、二轮及新工具结果动态预算补充已实现；长期记忆及中断恢复仍为后续方案。第二轮实际契约、限制和验证见 [CONTEXT_SUMMARIES.md](CONTEXT_SUMMARIES.md)。

## 第一轮实际实现

- Python 新增 `context/history.py`、`budget.py`、`projection.py`，由现有 TurnLimits 中间件接入。Java 原内部 history 路径新增 `view=turns`，省略参数仍保留旧消息数组响应，无新数据库表。
- Java 按已授权 run 的用户消息 sequence 固定排除上界，按完整运行分页；每页最多8轮，Python每页最多4轮、默认最多6轮，每次送模前重新确认已选择的历史。失败、取消及证据失效轮次只允许用户请求作为明确标注的背景，助手正文不进入模型。
- Python 保留连续近期完整轮次，默认24,000字节内；较早一轮超额时停止选择，不跳过后拼接更早消息。最近一轮自身超额则明确 CONTEXT_BUDGET，不拆分或截断；原来的160字符问题摘录不再注入。未保留的旧约束不具备长期记忆保证，指代不明需澄清。
- 完整请求估计包括系统提示、OpenAI兼容消息视图、工具Schema、工具选择及输出设置，另预留输出和安全余量。默认应用窗口上限32768、输出1024、安全2048、字节硬上限60000；窗口需要部署方确认适合实际模型，不宣称自动识别供应商规格。
- `utf8` 默认按序列化字节进行保守Token估计；可显式配置匹配模型的 tiktoken encoding。二者均标为估计，保留15%估计余量及128单位协议余量，不保证精确等于供应商计费Token；模型返回 usage 时，仅在本轮内向上校准，最大系数4，不建立跨用户缓存。
- 软水位默认80%，压力下仅省略当前轮次中更早、已结算且全部属于search_knowledge的完整工具批次；保护最近两个批次。保留查询、状态、调用ID、来源编号，显式标记contextOmitted；无法缩小或仍超限则返回受控错误。不会裁剪当前问题，也不会修改图消息、Java证据快照或撤权依赖。
- 策略版本、配置摘要、输入字节、估计Token、计数器及投影批次数写受控服务日志，通过traceId关联；日志无query、证据正文或凭据。当前没有持久会话摘要、自动压缩模型调用、动态工具输出预算、图恢复或新增SSE事件。
- 新增直接依赖tiktoken，复用原锁定的0.14.0，没有升级其他依赖或引入框架。具体配置、接口及验证见 API、RUNBOOK、PROGRESS。

## 改造前基线与附件的差异（调研记录）

- Java 是会话、消息、运行和证据快照的唯一业务存储。Python 通过带服务身份及用户委托的内部接口读取历史，未直接读写 Java 表。
- `runtime.select_history` 从内部接口最近60条候选中逐条选择最多12条消息，正文最多 min(24000, context_byte_budget/2) UTF-8 字节；超额消息跳过。没有按完整 run 选择，未保证用户与助手成对保留。
- 未选入的用户问题最多8条、每条取前160字符，形成最多2048字节的临时提取摘要。没有持久化的语义摘要，也没有全会话历史检索。
- `graph.TurnLimits` 每次送模前检查已序列化消息字节、提示词字节及固定2048字节余量；默认总额60000字节。它没有精确计入实际供应商工具 Schema，也没有运行中压缩。
- `KnowledgeGateway` 已将工具视图限制为4096字节，并保留状态、来源和本轮唯一引用。Java 保存的是实际进入模型的证据快照，不是全部召回候选。
- 每轮图使用 runId 作为 thread_id，结束和启动时清理检查点。下一轮只从 Java 重建用户/助手历史，不加载旧 ToolMessage。因此“保护当前用户轮次，再清理更早工具周期”直接套用后，当前项目通常没有可清理对象。
- 附件中的字符水位、50%/80%/20%比例、长期记忆、Skills 和稳定会话图线程不是当前已具备的能力。
- 锁定依赖包含 LangChain 1.4.7、LangGraph 1.2.19、langchain-openai 1.7.3、tiktoken 0.14.0。本机中间件已提供 SummarizationMiddleware / ContextEditingMiddleware / ClearToolUsesEdit，无需引入新的 Agent 框架。

## 社区实践及取舍

1. [LangChain 短期记忆](https://docs.langchain.com/oss/python/langchain/short-term-memory)：区分模型请求裁剪与图状态更新，删除后必须保证工具调用和结果配对。保留官方 create_agent，通过现有 AgentMiddleware 接入；扩展其 AgentState/state_schema，不另写主循环。更新持久消息时保留 ID，删除用 RemoveMessage，不能把短列表误当成覆盖更新。
2. [LangChain 内置中间件](https://docs.langchain.com/oss/python/langchain/middleware/built-in)：摘要与旧工具输出编辑是两种能力。已安装版本的 ContextEditingMiddleware 深拷贝 request.messages 再 request.override，不修改原始图历史；这个请求投影模式适合本项目。ClearToolUsesEdit 默认按结果数量清理，不理解我们的证据状态、真实批次或权限，需增加受控策略。
3. [Pi compaction](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/compaction.md)：记录摘要及保留边界，用摘要加近期消息重建模型视图，原始条目继续存在。借鉴摘要水位、来源边界和完整调用周期；不照搬其分支管理、文件操作记录或压缩后自动启动新运行的恢复策略。
4. [OpenHands condenser](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/context/condenser/llm_summarizing_condenser.py)：摘要调用与主 Agent 分开，区分软压力和模型输入硬上限。借鉴最小压缩收益及成本归属，不照搬其多次硬重置重试。
5. [DeepSeek Token 说明](https://api-docs.deepseek.com/quick_start/token_usage/)：供应商提供模型计数方法，计数估计仍需用 API usage 校准。项目虽然安装 tiktoken，但 OpenAI 兼容传输并不代表 tokenizer 相同；配置必须按实际 provider/model 匹配。

这些是设计参考，不能据此声称摘要保留了全部语义或已经提高事实正确率。

## 数据与职责

| 数据 | 归属与建议 |
|---|---|
| 原始会话、答案、blocks、工具执行与证据快照 | Java 现有存储；模型压缩不删除或改写这些记录 |
| 本轮模型工作集、调用预算、请求投影 | Python 上下文模块；身份和委托仍通过可信 runtime.context 注入 |
| 可复用的会话摘要 | 建议由 Python 计算、Java 作为派生业务记录保存和授权；不保存第二份完整聊天历史 |
| LangGraph 检查点 | Python 管理；近期继续按 runId 隔离，不因新增摘要自动开启工具恢复 |
| 跨会话用户长期记忆 | 独立后续能力，需 Java 的用户可见管理、版本及清空规则；当前不自动抽取 |

摘要和记忆是背景资料，不能升级成系统指令。授权、审批决定和业务执行状态只能读取服务端结构化记录；从自然语言提取的“不要提交”等限制需要保留来源及原文，不能成为恢复执行的授权。

## 推荐的请求流程

```text
Java 检查会话归属并创建/复用 run（继续现有幂等与会话互斥）
  → 读取当前有效摘要和按 run 分组的授权历史
  → 保护当前问题、近期完整轮次及明确限制
  → Python create_agent 开始执行
      → 每次模型调用前进入 ContextManager
          1. 检查当前授权、来源依赖与工具调用/结果配对
          2. 按实际系统提示、消息、工具 Schema 计算请求预算
          3. 需要时压缩已结束的历史轮次，或编辑可重新查询的旧工具正文
          4. 再复核权限和完整请求，仍超额则明确失败
      → 模型输出 / 执行工具 / 回到 ContextManager
  → Java 保存答案、blocks、证据依赖和运行终态
```

“每次送模前”也包括摘要模型调用；不能先把失效历史送给摘要模型，再对摘要做权限过滤。

### Token 预算与字节兜底

`可用输入 = 模型窗口 - 输出预留 - 安全余量`。

完整输入需要包含系统提示、当前工具 Schema、摘要、历史、当前问题与本轮工具消息，不能只测 state.messages。ModelProfile 记录供应商、模型、窗口、输出上限、计数器及其版本；可估算时记录 estimated=true，保留现有字节硬限制，用实际 usage 做有界校准。未识别的模型不默认拥有某个大窗口。

设置软水位、压缩目标和硬上限；具体比例在回放评估后确定，附件中的50%/80%/20%仅可作为实验参数。软水位触发一次有预算的尝试，不保证压缩成功；最小压缩收益不足时不反复调用摘要模型。当前 config.from_env 未暴露所有 Settings 字段，落地时要同步补齐明确的上下文配置。

摘要调用与主调用共享总 deadline、取消与调用预算。已安装 SummarizationMiddleware 的默认模型路径包含独立重试，不能直接用默认行为绕过本项目 SDK retries=0 的预算规则；可注入受控 summarizer，或用薄中间件组合其策略。最终请求检查应在投影完成后执行，避免现有字节 guard 在压缩前提前拒绝。

### 完整轮次和工具投影

跨用户消息：用 Java runId 将用户与助手消息组成轮次，按轮次选择，不能只因助手较长而留下缺乏上下文的答案或跳过关键用户限制。失败/取消运行的用户问题可作为明确标记的历史请求背景，不能把未完成操作标为成功。

Java 内部 history 需要返回有界分页游标、消息 sequence、runId/status 等字段，并固定本次读取的历史上界。当前先查最近60条再过滤的方式，既可能在边界截断轮次，也无法支持可靠的全会话摘要水位；不能只在 Python 中改变一个长度常量。

本轮工具消息：当前每轮最多6次检索，优先在新工具返回时按剩余预算限制正文。只有确有压力才编辑已经结算、允许重新查询的较早检索结果；保护最近的完整工具批次、未完成调用、执行状态及当前问题。按 toolCallId/batchId 配对，不按名称或单条完成顺序猜测周期。

投影保留消息 ID、tool_call_id、检索状态、原查询与重新查询线索；省略正文必须明确标记，不把 READY 改成 NO_EVIDENCE，不把 UNAVAILABLE 改成无资料。被省略的内容不能仅凭留下的编号恢复成当前事实；需要精确引用时重新检索。Java 的已授权证据快照和派生依赖继续保留，不能为缩小模型上下文而移除撤权追溯关系。

默认优先使用 request.override(messages=...) 形成模型视图，保留图的原始执行记录。确实需要压缩图工作状态时才使用同 ID 更新或 RemoveMessage；两种操作不能混用含义。原始检索候选及调试数据不因新增压缩功能而默认扩大保存范围。

### 会话摘要与证据依赖

建议摘要内容包含目标、来源可追溯的限制、已确认结果、待解决问题、需要重新查询的线索，输出需经过结构和长度校验。中文提示词单独版本化，摘要调用无工具、有限输出并验证正常结束。模型输出不通过校验、超时或取消时，不提交新摘要、不推进水位、不删除原历史；剩余工作集仍超额时返回受控错误。

建议持久化字段：conversationId、throughSequence、sourceMessageIds/sourceHash、摘要内容、evidenceDependencies、model、promptVersion/hash、policyVersion、revision。来源覆盖范围、依赖与写入权限由服务端核对，不把模型返回的 ID 或 JSON 校验视为授权证明。通过 expectedRevision 和历史上界进行乐观锁提交；删除会话同时清理摘要。

摘要的授权范围至少包含租户、用户、会话和固定知识范围。每次复用时验证来源消息及其证据依赖，撤权、禁用、删除、generation/hash 变化后使摘要失效，从当前允许的原始记录重新生成或回退到有效近期历史。不能只按 conversationId/TTL 命中，也不能只剥掉摘要内引用编号后继续使用敏感事实。

摘要生成的依赖与原答案依赖一起传递到新回答，继续复用 ConversationEvidence 的回表规则。现有64/128份依赖等硬限制仍需保留；依赖超额必须明确降级或重新缩小摘要范围，不能静默丢弃来源关系。

摘要文字不进入用户答案增量或工具结果。沿用已有 lc_source=summarization/内部调用标记并增加明确的 stream 过滤；运行过程如需提示，只发送受控的“正在整理上下文”，不将摘要、内部提示或后台调用误渲染成 answer 块。

### 检查点与长期记忆

近期保留 runId 检查点、Java 权威历史与派生摘要。稳定会话 thread_id 并非使用摘要的前提；切换后还必须实现旧状态清洗、当前权限重验、恢复点选择及工具操作幂等，不能直接修改一个 ID 开启恢复。

未来确需多实例或中断续跑时，再接入 Python 管理的 AsyncPostgresSaver，独立管理其 schema/连接权限，避免 Python 访问 Java 业务表。Java 已有会话运行互斥可以复用，但它本身不完成图恢复。[LangGraph 持久化职责](https://docs.langchain.com/oss/python/langgraph/persistence)

长期记忆另行设计 Java 的偏好/事实管理和用户控制接口，明确是否允许自动抽取。若引入后台抽取，使用处理水位、版本及 memoryEpoch 防止清空前的旧任务重新写回；不能把历史知识证据中的敏感事实无条件转成跨会话用户记忆。

## 建议分轮交付

1. 预算与完整轮次：新增 Python context 模块、ModelProfile/TokenCounter、请求投影与最终检查，补 Java 内部历史分页/分组所需字段，保持现有对外聊天和 SSE 契约。没有语义摘要、长期记忆或图恢复。
2. 受授权会话摘要：Python 生成，Java 保存派生摘要及来源水位/依赖，接入受控摘要调用和失效回退。补齐摘要失效对新派生回答的追溯，保持原始聊天可恢复展示。
3. 按实际需求分别增加长期记忆、Postgres 检查点/人工中断恢复；它们是独立能力，不作为前两轮的隐含工作。

建议目录为 `services/agent-service/src/agent_service/context/`，包含 policy、budget、history、projection、summary 与 middleware；`runtime.py` 负责协调，`graph.py` 注册中间件。摘要持久化复用 Java backend/agent 的内部认证、事务、Flyway 和会话互斥。模块按实际代码量合并，不要求为每个概念都新建类。

## 验证与效果记录

采用小规模定向行为测试及既有历史回放，不为每个帮助函数堆测试，不重跑未受影响的全仓库验收。重点场景：

- 完整轮次保留，同名并行工具逆序完成后仍配对，未完成批次不能被裁剪。
- 当前问题、“先不要提交”等明确限制和关键编号在投影中保留；摘要失败原历史和水位不变。
- 撤权、禁用、删除、版本变化发生在摘要生成前、生成中或复用前时，失效正文都不能进入任何模型；派生回答继续受依赖约束。
- 重复请求/并发摘要写入被现有幂等、会话互斥或摘要revision拒绝，加载历史不执行工具。
- 中英文、较大工具Schema、工具结果、未知计数器、摘要超时/取消、总预算不足得到明确状态；无整个流重试。
- 摘要内容不会进入答案SSE；原有历史与时间线仍完整展示，已省略工具内容可按原授权重新查询。

效果评估另行对照当前基线，记录完整请求 Token估计/usage、上下文压缩率、额外摘要耗时与调用成本、限制/编号保留率、追问与引用正确使用情况。权限与协议验证用确定性断言，语义保留使用自有标注样本及人工核对；不把模型自评或格式校验当事实正确保证。第一轮没有执行摘要验证；第二轮定向验证记录见 [SUMMARY_VALIDATION.json](SUMMARY_VALIDATION.json)，仍无真实供应商的摘要语义效果评估。

## 动态工具结果预算补充（2026-11-07）

已新增 `context/tool_results.py`：主模型返回工具批次后，以系统提示、工具Schema、当前历史/摘要、已生成文字和调用参数建立下一次完整请求基线。补齐待执行结果占位后，复用原投影规则处理较早已结算批次，原图及Java记录不变；按原批次调用数量均分剩余估计Token和字节，每个结果另留80×计数校准系数的估计Token和64字节余量，仍受 `AGENT_TOOL_BYTE_BUDGET` 上限限制。

分配结果只保存在本轮可信AgentContext中，不作为模型可填的参数。并行调用各自预留，完成较早的工具不能借用后续工具额度。适配器在原有来源/哈希校验之后、引用注册及Java落库之前，同时按固定上限与动态额度裁剪新证据。增量测量沿用实际ToolMessage JSON序列化、同一计数器和外层转义，只重算工具部分，避免每次裁剪重复扫描全部历史；下一次送模前仍重新校验完整请求。

一批工具连最小受控失败状态都容纳不下时，返回413/CONTEXT_BUDGET，不调用Java检索；这可能发生在主模型已经返回大段文字之后，并不代表模型调用次数为0。某次检索取得READY但无法在额度内保留任何证据时，返回UNAVAILABLE/TOOL_RESULT_BUDGET，不能改成NO_EVIDENCE。通常保留READY并标记toolTruncated；旧证据依赖、历史和当前问题不会因新工具裁剪而删除。

模型、Java消息快照及最终引用只采用实际下发的片段和编号；未下发候选不注册。中文提示词补充TOOL_RESULT_BUDGET处理，不盲目重复相同查询。模型工具参数仍只有query，Java检索、权限和索引契约不变；独立使用KnowledgeGateway且未提供主Agent预算时仍沿用原固定上限。

上下文策略升级为context-v2，摘要复用键也随之变化，并加入工具字节上限配置，旧摘要按既有安全原记录重建。工具策略为tool-results-v1；`agent_tool_budget`日志仅记录trace、策略、调用数量和每结果上限，不记录正文。无新依赖、迁移、公开SSE事件或前端改动。

定向记录见 [TOOL_BUDGET_VALIDATION.json](TOOL_BUDGET_VALIDATION.json)。Token及分段估计并非供应商精确计费保证，保留安全余量和最终完整请求硬检查；裁剪也不保证原句、否定或例外条件完整。真实模型效果、准确率、成本及容量仍未评估。本轮在该补充范围结束。
