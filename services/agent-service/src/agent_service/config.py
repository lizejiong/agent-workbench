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
        )
