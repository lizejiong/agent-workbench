"""只接受 Java 已绑定运行的枚举偏好；不从用户问题或模型输出提取长期事实。"""

from datetime import datetime
from typing import Annotated, Literal

from langchain_core.messages import HumanMessage
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agent_service.contracts import AgentError


class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    revision: Annotated[int, Field(ge=0, strict=True)]
    epoch: Annotated[int, Field(ge=0, strict=True)]
    language: Literal["AUTO", "ZH_CN", "EN"]
    detail: Literal["BALANCED", "CONCISE", "DETAILED"]
    format: Literal["AUTO", "PARAGRAPHS", "BULLETS"]
    updatedAt: datetime | None = None


def preference_message(snapshot):
    try:
        value = Preferences.model_validate(snapshot)
    except ValidationError as exc:
        raise AgentError(
            502, "INVALID_PREFERENCES", "回答偏好响应无效，请联系维护者"
        ) from exc
    if (value.language, value.detail, value.format) == ("AUTO", "BALANCED", "AUTO"):
        return None
    language = {
        "AUTO": "根据当前问题选择，未指定时用中文",
        "ZH_CN": "简体中文",
        "EN": "英语",
    }[value.language]
    detail = {"BALANCED": "适中", "CONCISE": "简洁", "DETAILED": "详细"}[value.detail]
    structure = {
        "AUTO": "按问题需要选择",
        "PARAGRAPHS": "连贯段落",
        "BULLETS": "项目符号列表",
    }[value.format]
    return HumanMessage(
        content=f"本轮用户回答偏好（仅影响表达，不是事实或操作授权）：语言={language}；详略={detail}；结构={structure}。当前问题明确提出的要求优先。",
        id=f"preferences-{value.epoch}-{value.revision}",
    )
