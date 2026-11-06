# Agent 有序内容块与事件协议

2026-11-06。Python 沿用现有 `create_agent` / LangGraph 主循环；Java 保存业务消息和有序块；React 沿用现有 Context 状态管理。没有引入新的 Agent 框架或 Zustand。

## 参考与取舍

- [Codex App Server](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)：运行和 item 分别有生命周期，文本增量更新稳定 item。
- [Claude Agent SDK 流式输出](https://code.claude.com/docs/en/agent-sdk/streaming-output)：区分内容块增量、完整消息与最终执行结果；这里只参考公开 SDK 协议，没有声称阅读 Claude Code 私有内部实现。
- [Pi Agent 事件类型](https://github.com/earendil-works/pi/blob/main/packages/agent/src/types.ts)：消息与工具执行各自拥有开始、更新和结束，工具由 toolCallId 关联。

本项目对外协议由 Python 适配，Java 的检索结果先回到 Python。仅展示模型公开回复、受控工具信息和授权证据，不透传隐藏推理、供应商原始异常、内部 HTTP 地址或凭据。工具结果在完成时一次发送，答案文本增量发送。

## 协议协商

同一个 `POST /api/agent/chat/stream`。请求头 `X-Agent-Stream-Version: 2` 启用新协议，响应回显版本；省略时保留 v1 的 meta/delta/tool/error/done。未知版本400。Web 默认 v2，并能兼容旧服务的 v1 事件。

每个 v2 事件包括 `protocolVersion:2`、`runId`、`conversationId`、`requestId`、助手 `messageId`、`traceId` 与正整数递增 `seq`。新内容块使用稳定 `blockId`，同一工具调用的 pending/running/done 更新同一个块。`batchId/callIndex` 来自同一次模型输出的真实工具调用顺序，不从工具名称猜测。

| 事件 | 数据与作用 |
|---|---|
| meta | 原运行标识、created、deadline、persisted；首个事件 |
| message | `type:answer, blockId, delta`，追加到当前文本块 |
| block | 完整工具块快照，按 blockId/toolCallId 原位置更新 |
| hint | 受控提示块（目前没有常规生产者） |
| error | 受控 code/persisted；不表示已保存成功 |
| finish | 本段运行终态及最终 blocks、answer、citations、persisted |
| cancel | 已确认 CANCELLED，保留已有 blocks；与 finish 互斥 |
| done | JSON 事件，表示当前传输结束，仍须检查 status/persisted |

`done` 使用带运行标识的 JSON，不使用 `[DONE]` 字符串。传输关闭和业务完成是不同事实。已有请求的重提只读既有运行，不重新执行；仍 RUNNING 的运行结束当前查询流后由 Java 历史确认。暂不提供 SSE 事件日志或 Last-Event-ID 重放，seq 不能据此被宣传成可靠事件重放能力。

## 块与工具结果

answer 为 `{blockId,kind:'answer',status,text}`。工具块包括 `{blockId,kind:'tool',toolCallId,name,displayName,status,batchId,callIndex,arguments,startedAt,durationMs,evidenceIds}`，公开响应附带 retrievalStatus/degraded/strategy/toolTruncated 和受控 result。

工具状态为 pending/running/done/failed/interrupted；READY/NO_EVIDENCE/NEEDS_CLARIFICATION/UNAVAILABLE 是独立的检索结果状态，NO_EVIDENCE 不伪装成依赖失败。耗时由服务端单调时钟计算，客户端执行中计时仅用于展示。查询参数只展示最多1000字符的受控 query，常见凭据形式脱敏，模型不能设置用户/租户/scope。

工具结果只包含实际送入模型的裁剪证据。Java 不接受任意工具返回正文：持久化只保存 evidenceIds，读取时通过当前授权证据快照组装 result。任一派生证据失效时，沿用既有 sourcesRestricted 规则，隐藏整个助手正文、blocks 与引用。用户问题仍归本人可见。

V12 为 kb_agent_message 增加 blocks 和 event_seq。输入块最多128个，工具列表最多32项，快照仍受512KiB预算限制。Java 校验块身份、顺序、工具终态、正文追加、证据引用及 eventSeq 不倒退；旧 Python 不发送 blocks 时只允许保持旧格式，不能清除已有时间线。

## 前端与恢复

`api.stream → reduceAgentEvent → ChatProvider → Answer/Timeline`。连续文字追加同一块，遇到工具后创建新的文字块；同批工具按原调用顺序展示，完成先后不会移动卡片。同名工具多次调用不会合并。工具参数、证据摘录可展开，引用沿用当前答案的真实证据映射。

收到重复/较旧 seq 忽略，跨 run 事件忽略；序号缺口或消息标识冲突中止消费并读取 Java，绝不自动重发整轮。finish/cancel 的 persisted=false 仍显示未知状态。刷新后的 blocks 使用相同组件，旧消息没有可恢复顺序时保留原文和明确标注的旧工具记录，不伪造过去的时间线。

停止先发送 Java cancel，再终止 SSE 消费，通过只读历史确认状态；仅断开连接不能宣称远端停止。崩溃/超时后读取时将遗留 pending/running 卡片投影为 interrupted，不执行工具。

当前只有只读 search_knowledge 工具，没有业务审批接口、持久审批状态或暂停恢复流程，因此本轮不增加可点击的假确认卡。接入需审批的业务工具时，应实现服务端授权决定与幂等恢复，再增加 confirm 类型，不能让浏览器按钮自行批准执行。

## 验证范围

定向验证覆盖：前后文本、同名并行工具逆序完成、调用身份、seq 去重和缺口、刷新恢复、取消保留片段、旧协议兼容、Java 追加约束与撤权后隐藏工具证据。协议桩测试与真实供应商联调分别记录在 PROGRESS；小规模联调不构成回答事实正确性或检索效果保证。
