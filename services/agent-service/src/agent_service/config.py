"""只读取部署配置；不允许模型或请求覆盖服务地址、模型和凭据。"""

import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit


def trusted_url(value: str, *, origin: bool = False) -> str:
    url = urlsplit(value)
    if (
        url.scheme not in {"http", "https"}
        or not url.hostname
        or url.username
        or url.password
        or url.query
        or url.fragment
        or origin
        and url.path not in {"", "/"}
        or url.scheme == "http"
        and url.hostname not in {"localhost", "127.0.0.1", "::1"}
    ):
        raise ValueError("服务地址必须为可信 HTTPS 地址，本机开发可用 HTTP")
    return value.rstrip("/")


@dataclass(frozen=True)
class Settings:
    java_origin: str
    service_id: str
    service_token: str = field(repr=False)
    model_base_url: str
    model: str
    model_api_key: str = field(repr=False)
    state_dir: Path = Path(".cache/agent-service")
    turn_timeout: float = 60
    concurrency: int = 4
    max_model_calls: int = 8
    max_tool_calls: int = 6
    context_byte_budget: int = 60000
    tool_byte_budget: int = 4096
    model_provider: str = "compatible"
    model_context_tokens: int = 32768
    max_output_tokens: int = 1024
    context_safety_tokens: int = 2048
    token_counter: str = "utf8"
    token_encoding: str = ""
    # 旧工具正文、会话摘要和近期原文是三种不同的预算动作，不能共用一个水位。
    tool_projection_ratio: float = 0.5
    summary_trigger_ratio: float = 0.8
    summary_keep_ratio: float = 0.2
    # 仅是读取和安全上限；最近原文保留量由 summary_keep_ratio 决定。
    history_turns: int = 24
    summary_enabled: bool = True
    summary_timeout: float = 10

    def __post_init__(self):
        trusted_url(self.java_origin, origin=True)
        trusted_url(self.model_base_url)
        if not self.model.strip() or not self.model_api_key.strip():
            raise ValueError("必须配置主 Agent 模型和凭据")
        if not 1 <= self.turn_timeout <= 120 or not 1 <= self.concurrency <= 32:
            raise ValueError("无效的回合耗时或并发配置")
        if not 1 <= self.max_model_calls <= 16 or not 1 <= self.max_tool_calls <= 16:
            raise ValueError("无效的模型或工具调用预算")
        if not 8192 <= self.context_byte_budget <= 262144:
            raise ValueError("无效的上下文预算")
        if not 1024 <= self.tool_byte_budget <= 16384:
            raise ValueError("无效的工具结果预算")
        if not 4096 <= self.model_context_tokens <= 1048576:
            raise ValueError("无效的模型上下文上限")
        if not 1 <= self.max_output_tokens <= 8192 or self.context_safety_tokens < 256:
            raise ValueError("无效的模型输出或安全预留")
        if (
            self.max_output_tokens + self.context_safety_tokens
            >= self.model_context_tokens
        ):
            raise ValueError("模型窗口没有可用输入预算")
        if (
            self.token_counter not in {"utf8", "tiktoken"}
            or self.token_counter == "tiktoken"
            and not self.token_encoding
        ):
            raise ValueError("Token计数器须显式配置，tiktoken须指定匹配的encoding")
        if not (
            0.2 <= self.tool_projection_ratio < self.summary_trigger_ratio <= 0.95
            and 0.05 <= self.summary_keep_ratio < self.summary_trigger_ratio
            and 1 <= self.history_turns <= 24
        ):
            raise ValueError("无效的上下文水位或历史轮次")
        if not 1 <= self.summary_timeout <= 30:
            raise ValueError("无效的摘要调用超时")

    @classmethod
    def from_env(cls):
        return cls(
            java_origin=os.environ["JAVA_RAG_ORIGIN"],
            service_id=os.environ["RAG_SERVICE_ID"],
            service_token=os.environ["RAG_SERVICE_TOKEN"],
            model_base_url=os.environ["AGENT_MODEL_BASE_URL"],
            model=os.environ["AGENT_MODEL"],
            model_api_key=os.environ["AGENT_MODEL_API_KEY"],
            state_dir=Path(os.getenv("AGENT_STATE_DIR", ".cache/agent-service")),
            turn_timeout=float(os.getenv("AGENT_TURN_TIMEOUT", "60")),
            concurrency=int(os.getenv("AGENT_CONCURRENCY", "4")),
            max_model_calls=int(os.getenv("AGENT_MAX_MODEL_CALLS", "8")),
            max_tool_calls=int(os.getenv("AGENT_MAX_TOOL_CALLS", "6")),
            context_byte_budget=int(os.getenv("AGENT_CONTEXT_BYTE_BUDGET", "60000")),
            tool_byte_budget=int(os.getenv("AGENT_TOOL_BYTE_BUDGET", "4096")),
            model_provider=os.getenv("AGENT_MODEL_PROVIDER", "compatible"),
            model_context_tokens=int(os.getenv("AGENT_MODEL_CONTEXT_TOKENS", "32768")),
            max_output_tokens=int(os.getenv("AGENT_MAX_OUTPUT_TOKENS", "1024")),
            context_safety_tokens=int(os.getenv("AGENT_CONTEXT_SAFETY_TOKENS", "2048")),
            token_counter=os.getenv("AGENT_TOKEN_COUNTER", "utf8"),
            token_encoding=os.getenv("AGENT_TOKEN_ENCODING", ""),
            tool_projection_ratio=float(
                os.getenv("AGENT_TOOL_PROJECTION_RATIO", "0.5")
            ),
            summary_trigger_ratio=float(
                os.getenv("AGENT_SUMMARY_TRIGGER_RATIO", "0.8")
            ),
            summary_keep_ratio=float(
                os.getenv("AGENT_SUMMARY_KEEP_RATIO", "0.2")
            ),
            history_turns=int(os.getenv("AGENT_HISTORY_TURNS", "24")),
            summary_enabled=(
                os.getenv("AGENT_SUMMARY_ENABLED", "true").lower() == "true"
            ),
            summary_timeout=float(os.getenv("AGENT_SUMMARY_TIMEOUT", "10")),
        )
