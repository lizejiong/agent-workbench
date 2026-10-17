"""Optional asynchronous Redis cache for JSON values; client ownership stays outside."""

import json
from datetime import timedelta
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from redis.asyncio import Redis

type JSONValue = (
    str | int | float | bool | None | list[JSONValue] | dict[str, JSONValue]
)


def _check_json(value: JSONValue) -> None:
    if value is None or isinstance(value, (str, bool, int, float)):
        return
    if isinstance(value, list):
        for item in value:
            _check_json(item)
        return
    if isinstance(value, dict) and all(isinstance(key, str) for key in value):
        for item in value.values():
            _check_json(item)
        return
    raise TypeError("Cache values must contain only JSON types and string object keys")


def _reject_constant(value: str) -> None:
    raise ValueError(f"Invalid JSON constant: {value}")


class RedisCache:
    def __init__(self, client: "Redis") -> None:
        self.client = client

    async def get(self, key: str) -> JSONValue:
        value = await self.client.get(key)
        if value is None:
            return None
        return json.loads(value, parse_constant=_reject_constant)

    async def put(self, key: str, value: JSONValue, *, ttl: timedelta) -> None:
        milliseconds = ttl // timedelta(milliseconds=1)
        if milliseconds < 1:
            raise ValueError("Cache TTL must be at least one millisecond")
        if value is None:
            raise ValueError("Top-level None is reserved for cache misses")
        _check_json(value)
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
        await self.client.set(key, encoded, px=milliseconds)

    async def delete(self, key: str) -> None:
        await self.client.delete(key)


def cache_key(namespace: str, *identifiers: str | int, prefix: str = "rag") -> str:
    segments = []
    for value in (prefix, namespace, *identifiers):
        if value is None or not (segment := str(value).strip()):
            raise ValueError("Cache key segments must not be None or blank")
        segments.append(segment)
    return ":".join(segments)
