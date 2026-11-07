"""按显式模型配置估算完整模型请求，并保留独立的 UTF-8 硬限制。"""

import math
from dataclasses import dataclass

from langchain_core.messages import convert_to_openai_messages
from langchain_core.utils.function_calling import convert_to_openai_tool

from agent_service.knowledge.adapter import digest, encode

POLICY_VERSION = "context-v1"


@dataclass(frozen=True)
class ModelProfile:
    provider: str
    model: str
    window: int
    output: int
    safety: int
    counter: str
    encoding: str


@dataclass(frozen=True)
class RequestSize:
    tokens: int
    bytes: int
    counter: str


class RequestBudget:
    def __init__(self, settings):
        self.profile = ModelProfile(
            settings.model_provider,
            settings.model,
            settings.model_context_tokens,
            settings.max_output_tokens,
            settings.context_safety_tokens,
            settings.token_counter,
            settings.token_encoding,
        )
        self.byte_limit = settings.context_byte_budget
        self.soft_ratio = settings.context_soft_ratio
        self.input_limit = (
            self.profile.window - self.profile.output - self.profile.safety
        )
        self.scale = 1.15
        self.encoding = None
        if self.profile.counter == "tiktoken":
            import tiktoken

            self.encoding = tiktoken.get_encoding(self.profile.encoding)
        self.version = (
            POLICY_VERSION
            + ":"
            + digest(repr((self.profile, self.byte_limit, self.soft_ratio)))[:16]
        )

    def measure(self, request, messages=None):
        messages = request.messages if messages is None else messages
        system = [request.system_message] if request.system_message else []
        payload = {
            "model": self.profile.model,
            "messages": convert_to_openai_messages([*system, *messages]),
            "tools": [convert_to_openai_tool(tool) for tool in request.tools],
            "max_completion_tokens": self.profile.output,
            "tool_choice": request.tool_choice,
        }
        # HTTP 认证头不是模型上下文。当前 Agent 不启用结构化答案策略。
        if request.response_format is not None:
            payload["response_format"] = (
                request.response_format
                if isinstance(request.response_format, dict)
                else str(request.response_format)
            )
        wire = encode(payload)
        base = (
            len(self.encoding.encode(wire.decode(), disallowed_special=()))
            if self.encoding is not None
            else len(wire)
        )
        # 即使 tokenizer 匹配，这仍是兼容协议序列化估计，而非供应商精确计数。
        return RequestSize(
            math.ceil(base * self.scale) + 128,
            len(wire),
            self.profile.counter
            + (":" + self.profile.encoding if self.encoding else ""),
        )

    def fits(self, size, *, soft=False):
        ratio = self.soft_ratio if soft else 1
        return (
            size.tokens <= self.input_limit * ratio
            and size.bytes <= self.byte_limit * ratio
        )

    def observe(self, size, response):
        for message in response.result:
            usage = getattr(message, "usage_metadata", None) or {}
            actual = usage.get("input_tokens")
            if isinstance(actual, int) and actual > size.tokens:
                self.scale = min(4.0, self.scale * actual / size.tokens * 1.1)
