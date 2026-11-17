# 工具事件共享契约

正式来源为 Python `agent_service.tools.events.ToolEvent`，外部协议为本仓库 SSE v3 的稳定 data-tool 投影。事件不包含框架对象、隐藏推理或原始 MCP 响应。

从仓库根目录导出：

```powershell
uv run --project services/agent-service python scripts/tools/export_contract.py
```

`tools-v3.schema.json` 为 Pydantic v2 导出的 JSON Schema，`tools-v3.fixtures.json` 为各状态的固定跨语言样例。Web `src/chat/toolContract.ts` 使用 Zod 校验；`toolContract.test.ts` 消费这些样例，`Timeline.test.tsx` 验证显示状态和 MCP 来源隔离。

通用 runId/seq/trace 外壳及内容块继续沿用原流式契约。升级和历史兼容见 [TIMELINE.md](../../docs/rag/TIMELINE.md)、[TOOLS.md](../../docs/rag/TOOLS.md)。
