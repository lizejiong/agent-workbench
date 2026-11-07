"""仅修改本次模型请求副本；图状态、证据依赖和 Java 时间线保持原样。"""

import json

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import encode


def tool_batches(messages):
    pending, batches, current = {}, [], []
    for index, message in enumerate(messages):
        if isinstance(message, ToolMessage):
            if message.tool_call_id not in pending:
                raise AgentError(502, "CONTEXT_PROTOCOL", "工具调用与结果不匹配")
            call = pending.pop(message.tool_call_id)
            current.append((index, message, call))
            if not pending:
                batches.append(current)
                current = []
        else:
            if pending:
                raise AgentError(502, "CONTEXT_PROTOCOL", "存在未结算工具调用")
            if isinstance(message, HumanMessage):
                batches = []  # 只编辑当前用户轮次内的工具，跨轮历史由 Java 选择。
            if isinstance(message, AIMessage) and message.tool_calls:
                for call in message.tool_calls:
                    if not call.get("id") or call["id"] in pending:
                        raise AgentError(502, "CONTEXT_PROTOCOL", "工具调用标识无效")
                    pending[call["id"]] = call
    if pending:
        raise AgentError(502, "CONTEXT_PROTOCOL", "存在未结算工具调用")
    return batches


def prepare_request(request, budget):
    messages = list(request.messages)
    batches = tool_batches(messages)
    size = budget.measure(request, messages)
    projected = 0
    if not budget.fits(size, soft=True):
        # 按模型原始批次保护最近两个周期，不能用并行结果完成顺序猜测。
        for batch in batches[:-2]:
            if any(call["name"] != "search_knowledge" for _, _, call in batch):
                continue
            replacements = []
            for index, message, call in batch:
                try:
                    data = json.loads(message.content)
                    if not isinstance(data, dict) or data.get("status") not in {
                        "READY",
                        "NO_EVIDENCE",
                        "NEEDS_CLARIFICATION",
                        "UNAVAILABLE",
                    }:
                        break
                    result = {k: v for k, v in data.items() if k != "evidence"}
                    result.update(
                        evidence=[],
                        evidenceRefs=[
                            {k: e[k] for k in ("evidenceId", "documentName")}
                            for e in data.get("evidence", [])
                        ],
                        query=call["args"]["query"],
                        contextOmitted=True,
                        notice="较早检索正文已省略。状态与来源线索保留；需要精确条款时重新检索。",
                    )
                    content = encode(result).decode()
                    if len(content.encode()) >= len(message.content.encode()):
                        break
                    replacements.append(
                        (
                            index,
                            message.model_copy(
                                update={"content": content, "artifact": None}
                            ),
                        )
                    )
                except (ValueError, TypeError, KeyError):
                    break
            else:
                for index, message in replacements:
                    messages[index] = message
                projected += 1
                size = budget.measure(request, messages)
                if budget.fits(size, soft=True):
                    break
    if not budget.fits(size):
        raise AgentError(
            413, "CONTEXT_BUDGET", "完整模型请求超出预算，请缩小问题范围或新建会话"
        )
    return messages, size, projected
