"""从 Python 的正式契约导出跨语言 JSON Schema 与稳定事件样例。"""

import json
from pathlib import Path

from agent_service.tools.events import ToolEvent

root = Path(__file__).resolve().parents[2]
destination = root / "packages/contracts"
destination.mkdir(parents=True, exist_ok=True)
(destination / "tools-v3.schema.json").write_text(
    json.dumps(ToolEvent.model_json_schema(), ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
rows = []
for index, status in enumerate(
    [
        "running",
        "success",
        "empty",
        "invalid_arguments",
        "denied",
        "timeout",
        "unavailable",
        "failed",
        "cancelled",
    ]
):
    phase = (
        "start"
        if status == "running"
        else "success"
        if status in {"success", "empty"}
        else "cancel"
        if status == "cancelled"
        else "failure"
    )
    call_id = "T" + f"{index:032x}"
    block = {
        "blockId": "B" + f"{index:032x}",
        "kind": "tool",
        "toolCallId": call_id,
        "name": "fixture_read",
        "displayName": "只读工具",
        "batchId": "B" + "a" * 32,
        "callIndex": index,
        "status": "running"
        if status == "running"
        else "interrupted"
        if status == "cancelled"
        else "done"
        if status in {"success", "empty"}
        else "failed",
        "arguments": {"query": "已脱敏参数"},
        "evidenceIds": [],
    }
    data = ToolEvent(
        eventType=phase,
        callId=call_id,
        toolKey="mcp:fixture:read",
        displayName="只读工具",
        batchId=block["batchId"],
        order=index,
        status=status,
        startedAt="2026-11-16T10:00:00Z",
        durationMs=None if status == "running" else 23,
        summary="已脱敏摘要",
        block=block,
    ).model_dump(mode="json")
    rows.append({"event": "tool." + phase, "data": data})
(destination / "tools-v3.fixtures.json").write_text(
    json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
