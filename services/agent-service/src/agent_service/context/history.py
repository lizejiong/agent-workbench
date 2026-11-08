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

    @property
    def retained_turns(self):
        return len({row["runId"] for row in self.rows})


def select_history(
    turns,
    current_run,
    budget,
    max_turns=6,
    request_budget=None,
    token_limit=None,
    allow_empty=False,
    *,
    request=None,
    retain_latest=False,
):
    """选择连续的近期完整轮次。

    ``budget`` 是保守的 UTF-8 兜底；给出 ``request_budget`` 时同时按与模型请求
    相同的计数器控制历史份额。传入 ``request`` 时，在其最后一条当前问题之前
    插入历史，以完整请求的增量控制份额，并检查实际硬上限。``retain_latest``
    允许最近一轮超过软份额，但不能突破完整请求或证据依赖硬限制。
    """
    selected = []
    used = used_tokens = 0
    inherited = {}
    messages = []
    baseline = request_budget.measure(request) if request is not None else None
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
        candidate = []
        for row in rows:
            text = OLD_REFERENCE.sub("", row["content"])
            if not assistant:
                text = (
                    "历史请求（该轮没有当前可用的完整答复，不代表操作已完成）：\n"
                    + text
                )
            message = HumanMessage if row["role"] == "user" else AIMessage
            candidate.append(message(content=text, id=row["id"]))
        candidate += messages
        hard_limit = (
            len(inherited) + len(additional) > 64
            or len(encode([*inherited.values(), *additional.values()])) > 131072
        )
        if baseline is not None:
            size = request_budget.measure(
                request,
                [*request.messages[:-1], *candidate, request.messages[-1]],
            )
            next_bytes = size.bytes - baseline.bytes
            next_tokens = size.tokens - baseline.tokens
            hard_limit |= not request_budget.fits(size)
        else:
            next_bytes = used + len(encode([row["content"] for row in rows])) + 256
            next_tokens = used_tokens + (
                sum(
                    request_budget.measure_message(m).tokens
                    for m in candidate[: len(rows)]
                )
                if request_budget
                else 0
            )
        soft_limit = (
            next_bytes > budget
            or request_budget is not None
            and token_limit is not None
            and next_tokens > token_limit
        )
        keep_over_target = retain_latest and baseline is not None and not selected
        if hard_limit or soft_limit and not keep_over_target:
            if not selected:
                if allow_empty:
                    return HistorySelection([], [])
                raise AgentError(
                    413, "CONTEXT_BUDGET", "最近完整轮次过长，请缩小问题范围或新建会话"
                )
            break  # 保留连续的近期轮次，不跳过大答复后继续拼接更早消息。
        inherited.update(additional)
        selected.append((turn, rows))
        messages = candidate
        used, used_tokens = next_bytes, next_tokens
    retained = []
    for _, rows in reversed(selected):
        retained.extend(rows)
    return HistorySelection(messages, retained)
