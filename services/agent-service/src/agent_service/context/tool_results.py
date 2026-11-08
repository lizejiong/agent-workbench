"""按下一次完整模型请求，为同批工具独立预留额度；不依赖完成顺序。"""

import json
import math
from dataclasses import dataclass

from langchain.agents.middleware.types import ModelRequest, ModelResponse
from langchain_core.messages import AIMessage, ToolMessage

from agent_service.context.budget import RequestBudget, RequestSize
from agent_service.context.projection import prepare_request
from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import GUIDANCE, encode, model_view

POLICY_VERSION = "tool-results-v2"


@dataclass(frozen=True)
class ToolAllowance:
    budget: RequestBudget
    message: ToolMessage
    component_base: RequestSize
    tokens: int
    bytes: int
    byte_limit: int

    def accepts(self, result):
        if len(encode(result)) > self.byte_limit:
            return False
        # 与当前 langchain_core BaseTool 的 dict -> ToolMessage 序列化一致，
        # 包括空格、中文和外层 JSON 转义，不能只测证据正文长度。
        content = json.dumps(result, ensure_ascii=False, allow_nan=False)
        size = self.budget.measure_message(
            self.message.model_copy(update={"content": content})
        )
        return (
            size.tokens - self.component_base.tokens <= self.tokens
            and size.bytes - self.component_base.bytes <= self.bytes
        )


def allocate_results(
    request: ModelRequest,
    response: ModelResponse,
    budget: RequestBudget,
    byte_limit: int,
):
    messages = [*request.messages, *response.result]
    calls = [
        call
        for message in response.result
        if isinstance(message, AIMessage)
        for call in message.tool_calls
    ]
    if not calls:
        return {}
    first = len(messages)
    messages.extend(
        ToolMessage(content="", tool_call_id=call["id"], name=call["name"])
        for call in calls
    )
    future = request.override(messages=messages)
    # 先补齐占位结果，再按现有规则投影可省略的旧批次；原图消息不变。
    messages, base, _ = prepare_request(future, budget)
    count = len(calls)
    # 为不同 UUID/trace 的分词差异及消息边界留额外余量；仍是应用侧估计。
    tokens = (budget.input_limit - base.tokens) // count - math.ceil(80 * budget.scale)
    available_bytes = (budget.byte_limit - base.bytes) // count - 64
    allowances = {
        call["id"]: ToolAllowance(
            budget,
            messages[first + index],
            budget.measure_message(messages[first + index]),
            tokens,
            available_bytes,
            min(byte_limit, max(0, available_bytes)),
        )
        for index, call in enumerate(calls)
    }
    minimum = model_view(
        {
            "status": "UNAVAILABLE",
            "requestId": "00000000-0000-0000-0000-000000000000",
            "traceId": "f" * 32,
            "errorCode": "X" * 64,
            "retryable": False,
            "evidence": [],
            "guidance": GUIDANCE["UNAVAILABLE"],
        }
    )
    if any(not allowance.accepts(minimum) for allowance in allowances.values()):
        raise AgentError(
            413,
            "CONTEXT_BUDGET",
            "上下文无法容纳本批工具状态，请缩小问题范围或新建会话",
        )
    return allowances
