"""按 Java 运行边界选择完整历史，绝不以半条旧问题冒充语义摘要。"""

import re
from dataclasses import dataclass

from langchain_core.messages import AIMessage, HumanMessage

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import encode

OLD_REFERENCE = re.compile(r"\[K[a-f0-9]{32}_E[1-9][0-9]{0,3}\]")


@dataclass
class HistorySelection:
    messages: list
    rows: list[dict]

    @property
    def assistants(self):
        return [row for row in self.rows if row["role"] == "assistant"]


def select_history(turns, current_run, budget, max_turns=6):
    selected = []
    used = 0
    inherited = {}
    for turn in reversed(turns):
        if turn["runId"] == current_run:
            continue
        if len(selected) >= max_turns:
            break
        user, assistant = turn["user"], turn.get("assistant")
        rows = [user, assistant] if assistant else [user]
        if (
            user["role"] != "user"
            or any(row["runId"] != turn["runId"] for row in rows)
            or assistant
            and assistant["role"] != "assistant"
        ):
            raise AgentError(502, "INVALID_HISTORY", "历史轮次响应无效")
        additional = {
            s["evidenceId"]: s
            for s in (assistant or {}).get("evidence", [])
            if s["evidenceId"] not in inherited
        }
        size = len(encode([row["content"] for row in rows])) + 256
        if (
            used + size > budget
            or len(inherited) + len(additional) > 64
            or len(encode([*inherited.values(), *additional.values()])) > 131072
        ):
            if not selected:
                raise AgentError(
                    413, "CONTEXT_BUDGET", "最近完整轮次过长，请缩小问题范围或新建会话"
                )
            break  # 保留连续的近期轮次，不跳过大答复后继续拼接更早消息。
        inherited.update(additional)
        selected.append((turn, rows))
        used += size
    messages, retained = [], []
    for turn, rows in reversed(selected):
        retained.extend(rows)
        for row in rows:
            text = OLD_REFERENCE.sub("", row["content"])
            if not turn.get("assistant"):
                text = (
                    "历史请求（该轮没有当前可用的完整答复，不代表操作已完成）：\n"
                    + text
                )
            message = HumanMessage if row["role"] == "user" else AIMessage
            messages.append(message(content=text, id=row["id"]))
    return HistorySelection(messages, retained)
