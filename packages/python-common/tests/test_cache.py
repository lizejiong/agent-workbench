from datetime import timedelta
from unittest.mock import AsyncMock

import pytest

from rag_common.cache import RedisCache, cache_key


@pytest.mark.parametrize("value", ["中文", False, 0, [], {}, [None, {"值": 1}]])
async def test_json_values_round_trip(value):
    client = AsyncMock()
    cache = RedisCache(client)
    await cache.put("key", value, ttl=timedelta(milliseconds=1234))
    key, serialized = client.set.call_args.args
    assert key == "key"
    assert "\\u" not in serialized
    assert client.set.call_args.kwargs == {"px": 1234}
    for response in (serialized, serialized.encode("utf-8")):
        client.get.return_value = response
        assert await cache.get("key") == value


async def test_miss_delete_and_minimum_ttl():
    client = AsyncMock()
    client.get.return_value = None
    cache = RedisCache(client)
    assert await cache.get("absent") is None
    await cache.delete("absent")
    client.delete.assert_awaited_once_with("absent")
    await cache.put("key", False, ttl=timedelta(milliseconds=1))
    client.set.assert_awaited_once_with("key", "false", px=1)
    await cache.put("fractional", 0, ttl=timedelta(microseconds=1500))
    client.set.assert_awaited_with("fractional", "0", px=1)


@pytest.mark.parametrize(
    "ttl",
    [
        timedelta(microseconds=999),
        timedelta(0),
        timedelta(seconds=-1),
    ],
)
async def test_invalid_ttl_rejected_before_redis(ttl):
    client = AsyncMock()
    with pytest.raises(ValueError):
        await RedisCache(client).put("key", "value", ttl=ttl)
    client.set.assert_not_called()


@pytest.mark.parametrize(
    "value",
    [None, float("nan"), float("inf"), -float("inf"), (1, 2), {1: "value"}],
)
async def test_invalid_json_rejected_before_redis(value):
    client = AsyncMock()
    with pytest.raises((ValueError, TypeError)):
        await RedisCache(client).put("key", value, ttl=timedelta(seconds=1))
    client.set.assert_not_called()


async def test_underlying_errors_propagate():
    client = AsyncMock()
    cache = RedisCache(client)
    failure = RuntimeError("redis unavailable")
    for method, operation in [
        (client.get, lambda: cache.get("key")),
        (client.set, lambda: cache.put("key", 1, ttl=timedelta(seconds=1))),
        (client.delete, lambda: cache.delete("key")),
    ]:
        method.side_effect = failure
        with pytest.raises(RuntimeError) as caught:
            await operation()
        assert caught.value is failure
    client.get.side_effect = None
    for malformed in ("{", "NaN", "Infinity"):
        client.get.return_value = malformed
        with pytest.raises(ValueError):
            await cache.get("key")


def test_cache_key_matches_java_and_preserves_zero():
    assert cache_key(" conversation ", 42) == "rag:conversation:42"
    assert cache_key("conversation", 0, prefix=" app ") == "app:conversation:0"
    assert cache_key("conversation") == "rag:conversation"


@pytest.mark.parametrize(
    "args,kwargs",
    [
        ((None,), {}),
        ((" ",), {}),
        (("name", None), {}),
        (("name", ""), {}),
        (("name",), {"prefix": " "}),
        (("name",), {"prefix": None}),
    ],
)
def test_cache_key_rejects_missing_or_blank_segments(args, kwargs):
    with pytest.raises(ValueError):
        cache_key(*args, **kwargs)
