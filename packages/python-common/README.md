# Python 公共包

`rag-common` 面向 Python 3.12+ 的 FastAPI 服务，导入名为 `rag_common`。仅提供响应与错误契约、请求 ID 与最小访问日志，以及可选的异步 Redis JSON 缓存。服务业务、LangGraph 和 AI SDK 留在各服务中。

从仓库根目录安装（不需要 Redis 时去掉 `[redis]`）：

```sh
pip install -e "./packages/python-common[redis]"
```

在 `packages/python-common` 目录开发：

```sh
uv sync --all-extras --python 3.12
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
uv build
```

## FastAPI

```python
from fastapi import FastAPI
from rag_common import ClientError, Result, install_web

app = FastAPI()
install_web(app)  # 在启动前调用一次；重复调用会抛出 RuntimeError


@app.get("/items/{item_id}")
async def read_item(item_id: int) -> Result[dict[str, int]]:
    if item_id <= 0:
        raise ClientError("item_id 必须大于 0")
    return Result.success({"id": item_id})
```

普通业务接口显式返回 `Result`，中间件不会自动包装响应。默认 JSON 恰好包含四个字段：

```json
{"code":"0","message":"success","data":{"id":42},"requestId":"request-42"}
```

内部属性为 `request_id`，Pydantic 序列化默认使用别名 `requestId`。`Result.success(data=None, request_id=...)` 与 `Result.failure(code, message, request_id=...)` 的 ID 优先级均为非空白显式参数、当前请求上下文、随机 `uuid4().hex`；空白参数按未提供处理。`get_request_id()` 在请求外返回 `None`；独立任务可用 `rag_common.context.request_id_context("id")` 建立会自动复原的嵌套上下文。

| 错误 | 默认 code | 默认 message | HTTP |
| --- | --- | --- | --- |
| `ClientError` | `A000001` | 客户端请求错误 | 400 |
| `ServiceError` | `B000001` | 系统执行错误 | 500 |
| `RemoteError` | `C000001` | 远程服务调用错误 | 502 |

业务异常可传消息和关键字 `code`。请求校验失败使用 HTTP 422、`A000001` 和固定消息“请求参数校验失败”，不回显输入。`HTTPException` 保留状态和自定义响应头，4xx 使用客户端分类，5xx 使用系统分类；404、405 同样处理。未预期异常记录诊断信息，响应使用 HTTP 500 和默认系统错误，不暴露异常内容。

与 Java 约定相同的四个字段和错误分类保持一致；当前 Java 异常处理器使用 HTTP 200，而 Python 使用有意义的 HTTP 4xx/5xx。调用方应同时检查 HTTP 状态和业务 `code`。

HTTP 中间件复用长度 1–128、仅含 ASCII 字母、数字、`.`、`_`、`:`、`-` 的 `X-Request-Id`，其他值重新生成。ID 写入 `request.state.request_id`、`ContextVar` 和响应头，成功与错误响应的头、正文 ID 一致。上下文支持同步路由和并发异步请求，结束时复原；lifespan 与 WebSocket 原样传递。

访问日志使用标准库 logger `rag_common.web`，仅包含 method、path、status、duration_ms、request_id，不读取请求体，不记录 query、请求头或请求体。应用负责日志级别、输出和格式，库不会配置根 logger。耗时覆盖 ASGI 应用执行完成，包括流式响应和适用的后台任务；流已开始后的错误无法改写已发送的状态码。未预期异常的诊断日志可能包含异常详情，业务代码应避免把敏感值写入异常消息。

SSE 与 `StreamingResponse` 的业务事件和数据块保持原样，不加 `Result` 外壳、不缓存流。事件格式由服务定义，例如 `event: token\ndata: {"text":"你好"}\n\n`。发送开始后的异常与取消继续向上传播，不发送第二个 JSON 响应。

## 可选 Redis 缓存

```python
from contextlib import asynccontextmanager
from datetime import timedelta

from fastapi import FastAPI, Request
from redis.asyncio import Redis
from rag_common import Result, install_web
from rag_common.cache import RedisCache, cache_key


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with Redis.from_url("redis://localhost:6379/0") as client:
        app.state.cache = RedisCache(client)
        yield


app = FastAPI(lifespan=lifespan)
install_web(app)


@app.get("/conversations/{conversation_id}")
async def conversation(conversation_id: int, request: Request):
    cache: RedisCache = request.app.state.cache
    key = cache_key("conversation", conversation_id)
    data = await cache.get(key)
    if data is None:
        data = {"id": conversation_id}
        await cache.put(key, data, ttl=timedelta(minutes=5))
    return Result.success(data)
```

`RedisCache` 接收调用方管理的 `redis.asyncio.Redis`，不创建或关闭连接。基础安装和 `from rag_common import Result, install_web` 不导入 Redis。缓存接口仅含异步 `get`、`put`、`delete`：

- `get` 支持 bytes 与解码字符串，未命中返回 `None`；`False`、`0`、空数组和空对象正常返回。
- `put` 使用 UTF-8 JSON（不转义中文），仅接受 JSON 值；不接受顶层 `None`、NaN、Infinity、元组或非字符串对象键。Pydantic 模型请先调用 `model_dump(mode="json")`。
- `ttl` 是必填 `timedelta`，至少 1ms，使用整数运算向下取整为 Redis `PX` 毫秒，不提供永久缓存默认值。小于 1ms、零或负 TTL 在访问 Redis 前失败。
- Redis 与 JSON 解码错误原样向上传播，由调用方决定处理策略。
- `cache_key("conversation", 42)` 得到与 Java 相同的 `rag:conversation:42`；各段去除首尾空白、拒绝 `None` 和空白，保留整数 `0`，支持关键字 `prefix`。

测试通过模拟客户端验证缓存边界和调用参数，不连接实际 Redis，不验证网络、认证或 Redis 服务行为。

参考：[Starlette 中间件](https://starlette.dev/middleware/)、[FastAPI 异常处理](https://fastapi.tiangolo.com/tutorial/handling-errors/)、[redis-py 异步客户端](https://redis.io/docs/latest/develop/clients/redis-py/async/)。
