"""旧示例的兼容入口；唯一实现已提升到 services/agent-service。"""

import sys
from pathlib import Path

sys.path.insert(
    0, str(Path(__file__).resolve().parents[3] / "services/agent-service/src")
)

from agent_service.knowledge.adapter import (  # noqa: E402
    GUIDANCE,
    KnowledgeGateway,
    TrustedContext,
    TurnReferences,
    digest,
    encode,
)

__all__ = [
    "GUIDANCE",
    "KnowledgeGateway",
    "TrustedContext",
    "TurnReferences",
    "digest",
    "encode",
]
