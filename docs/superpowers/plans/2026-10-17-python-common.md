# Python 公共包首批能力实施计划

> **给 agentic workers：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪状态。

**目标：** 为未来 FastAPI + LangGraph 服务提供可直接依赖的轻量 Python 公共包，只同步近期大概率会使用的 Java 公共能力。

**架构：** `packages/python-common` 是独立安装的 `rag-common` 分发包，Python 导入名为 `rag_common`，采用 `src` 布局。统一跨语言协议，Python 使用异步客户端和 ContextVar；普通 JSON 显式返回 Result，流式响应保持原样。Redis 通过 extra 按需安装，由业务在 lifespan 中管理连接。

**技术栈：** 本机 Python 3.12、uv、FastAPI、Pydantic 2、可选 redis.asyncio；pytest、pytest-asyncio、httpx、ruff；Hatchling 构建 wheel/sdist。

---

## 范围与接口

首批只做响应、异常、请求上下文与日志、普通缓存。暂不做 MQ、鉴权、数据库、LangGraph 封装、AI SDK、策略注册器、布隆过滤器、分布式锁、自动重试、通用配置中心和线程工具。服务目录保持现有 `.gitkeep`。

| 文件 | 职责 |
| --- | --- |
| `packages/python-common/pyproject.toml` | 包元信息、运行依赖、redis extra、开发工具配置 |
| `packages/python-common/uv.lock` | 锁定本包开发与验证依赖 |
| `packages/python-common/src/rag_common/__init__.py` | 少量公共导出，不隐式导入可选 Redis 模块 |
| `packages/python-common/src/rag_common/context.py` | ContextVar 与可复位请求 ID 上下文 |
| `packages/python-common/src/rag_common/result.py` | 与 Java 一致的四字段泛型 JSON 响应 |
| `packages/python-common/src/rag_common/errors.py` | 错误码与 ClientError、ServiceError、RemoteError |
| `packages/python-common/src/rag_common/web.py` | 一次接入 FastAPI、异常处理、纯 ASGI 请求 ID/日志中间件 |
| `packages/python-common/src/rag_common/cache.py` | 注入 Redis 异步客户端、JSON 缓存、毫秒 TTL、Key 构造 |
| `packages/python-common/src/rag_common/py.typed` | 声明提供类型标注 |
| `packages/python-common/tests/test_result.py` | 协议、泛型数据、请求 ID 选择和上下文复位 |
| `packages/python-common/tests/test_web.py` | 正常/异常 HTTP、并发隔离、日志和流式行为 |
| `packages/python-common/tests/test_cache.py` | JSON、TTL、Key、底层失败传播 |
| `packages/python-common/README.md` | 中文安装、最小 FastAPI/Redis 使用示例和边界 |
| `README.md`、`.gitignore` | 更新包入口、Python 构建产物忽略规则 |

### 响应与异常的约束

```python
from rag_common import ClientError, Result, get_request_id, install_web

# 普通 API 使用显式 Result，成功消息与 Java 相同。
result = Result[dict[str, str]].success({"status": "ok"})
assert result.code == "0"
assert result.message == "success"
assert set(result.model_dump(by_alias=True)) == {"code", "message", "data", "requestId"}

# 项目异常支持业务自定义 code/message；默认 code 对齐 Java。
error = ClientError("问题不能为空")
assert error.code == "A000001"
```

错误类别：ClientError=`A000001`/400，ServiceError=`B000001`/500，RemoteError=`C000001`/502；默认中文消息分别为“客户端请求错误”“系统执行错误”“远程服务调用错误”。请求校验用客户端错误码、HTTP 422；保留框架 HTTPException 的状态和响应头；未知异常返回通用系统消息与 HTTP 500，不能把异常内容作为响应正文。

Python 内部字段为 `request_id`，序列化名为 `requestId`。Result 创建时 ID 优先级为显式值、当前上下文、新生成 UUID hex。中间件复用有效 `X-Request-Id`，无效或缺失则生成；写入 request.state 和响应头，finally 复位上下文。FastAPI 未知异常处理位于外层时，也必须通过 request.state 保证错误正文和响应头 ID 一致。并发请求不得互相覆盖。

`install_web(app)` 注册中间件和异常处理；重复调用应明确拒绝或无副作用。中间件只处理 HTTP，透传 lifespan/WebSocket。不读取或缓冲请求/响应正文，不自动包装响应。访问日志仅含方法、路径、状态、耗时和请求 ID，默认不记录查询参数、请求头和正文。未知异常需有诊断日志；已开始的流出错时记录并传播，不发送第二个 JSON 响应。取消需要保持取消语义。

### 缓存约束与使用

```python
from datetime import timedelta
from redis.asyncio import Redis
from rag_common.cache import RedisCache, cache_key

async def cache_example(client: Redis) -> dict[str, str] | None:
    cache = RedisCache(client)
    key = cache_key("conversation", "42")
    assert key == "rag:conversation:42"
    await cache.put(key, {"title": "示例"}, ttl=timedelta(minutes=5))
    value = await cache.get(key)
    await cache.delete(key)
    return value
```

缓存只做 `get`、`put`、`delete`，不做 loader/锁/布隆；`None` 表示未命中，JSON null 不作为有效缓存值。值为普通 JSON 数据，UTF-8 且拒绝 NaN/Infinity；Pydantic 对象由业务先转为 JSON 数据。put 必须显式给出至少 1ms 的 timedelta，使用 Redis PX；不足 1ms、零和负值在调用 Redis 前拒绝，防止永久缓存。Key 为 prefix/namespace/标识按冒号拼接并去除两端空格，None/空白片段拒绝，默认前缀 `rag`，与 Java 普通 Key 格式一致。客户端由调用方创建和关闭，公共包不会连接 Redis；底层 Redis/JSON 错误向调用方传播。

## 任务 1：实现并验证首批公共包

- [x] **步骤 1：创建包配置及行为测试，运行确认缺失实现导致失败。**

先创建 pyproject（Python >=3.12，FastAPI <1、Pydantic >=2.11,<3、可选 redis），src 空包与上述三个测试文件。测试不仅验证字段，还需要覆盖：422/404/405/500、HTTPException 自定义头、请求 ID 并发和复位、同步路由、streaming chunk 原样发送、开始响应后的异常、日志正文排除、TTL 的 999us/0/负数和 1ms、JSON 中的中文/false/0/空容器、缓存未命中与删除、Redis 异常。

```powershell
Set-Location packages/python-common
uv sync --all-extras
uv run pytest -q
```

预期：依赖解析成功、生成 uv.lock；测试因公共实现尚未提供而失败。

- [x] **步骤 2：实现 context/result/errors/web/cache，运行测试。**

按上述固定接口和约束提供最小实现；`__init__` 仅导出常用响应、异常、上下文和 `install_web`。中间件使用纯 ASGI，不使用 BaseHTTPMiddleware；可选缓存不得被默认导入。保留服务骨架，不增加模型供应商配置。

```powershell
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
```

预期：全部通过；Redis 只做客户端替身行为测试，不依赖本机 Redis 服务器。

- [x] **步骤 3：编写完整中文接入文档并验证构建与可选依赖隔离。**

README 给出 `pip install -e "./packages/python-common[redis]"`、uv 同步/测试命令、FastAPI Result/异常最小应用、Redis lifespan 的 async with 关闭示例、Java 跨语言契约与现有 HTTP 状态差异、流式接口边界。主 README 增加 Python 使用入口，修正“Python 尚未创建工程内部结构”的说明。忽略 `.egg-info/`、`build/`、coverage 产物，移除包的 `.gitkeep`。

```powershell
uv build
uv run --isolated --no-default-groups python -c "from rag_common import Result, install_web; import importlib.util; assert importlib.util.find_spec('redis') is None; assert Result.success().code == '0'"
git diff --check
```

预期：wheel/sdist 成功，wheel 含 py.typed；无 Redis 的隔离环境正常导入默认公共包。

- [x] **步骤 4：进行规格检查与代码质量检查，修复问题并完成本地集成。**

规格检查重点：只同步四类近期能力、四字段契约一致、流式和可选依赖正确、服务内部未创建。质量检查重点：并发上下文、异常中间件层级、流响应已开始后的行为、取消语义、TTL 与 JSON。检查通过后提交到 `codex/python-common`，快进合并回原项目目录的 `main`，保留功能分支便于回看，不推送远程。

```powershell
git add README.md .gitignore docs/superpowers/plans/2026-10-17-python-common.md packages/python-common
git diff --cached --check
git commit -m "feat: add lightweight Python common package"
git status --short --branch
git -C C:/Users/lzj/Documents/ChatGPT/rag merge --ff-only codex/python-common
uv run --directory C:/Users/lzj/Documents/ChatGPT/rag/packages/python-common --locked --all-extras --python 3.12 pytest -q
```

## 执行记录

- 已确认主工作区 `main` 干净，起点 `1bf7e44`；独立工作区位于 `C:/Users/lzj/.codex/worktrees/python-common/rag`，分支 `codex/python-common`。
- 原项目当前只有 Java 测试且本次不修改 Java；已有 83 项 Java 测试通过记录。Python 骨架无可运行基线，首批测试随本次能力创建。
- 实现提交：`09bd5a7996ebd49fed50b4420e757ebc5ab65eeb`，首轮测试先因缺失模块失败；空白 Result ID 对齐 Java 的补充测试也先失败再修复。
- CPython 3.12.13 下 `uv run pytest -q`：64 项通过；`ruff check`、`ruff format --check` 通过。`uv build` 成功生成 wheel/sdist，wheel 含 `py.typed`。
- `uv run --isolated --no-default-groups --python 3.12 ...` 确认基础导入不包含 Redis。另用 `uv run --isolated --no-project --python 3.12 --with <built-wheel> python -` 安装构建好的 wheel，在 Redis 缺失的环境执行 README 第一个 FastAPI 示例：`/items/42` 返回 200/`0`，`/items/0` 返回 400/`A000001`，四字段以及响应头/正文请求 ID 均匹配。
- Redis 验证使用客户端替身，未运行实际 Redis 服务器；不据此宣称真实网络、认证和服务端集成已验证。
- 规格检查发现 Result 直接构造和 `model_validate` 的空白 ID 未回退；提交 `a99bbd0e6717853e5f36b2d6f832c93d40b8aa9a` 在字段校验入口统一处理空白/None，同时保留有效 ID 和类型校验。先复现失败后修复，完整测试增至 84 项并全部通过，ruff 与构建再次通过。规格复核已批准，独立运行 29 项 Result 测试通过。
- 本地集成方式：开发期间 `main` 保留基线；完成检查后将功能分支快进合并回原目录，避免用户需要切换长期开发路径。此操作不涉及远程发布。
- 质量检查复现 HTTPException(204/304) 带 JSON 正文引起 h11 协议错误。提交 `cb58e4f28b6ce84bffafe44f8db9299bfc3e7631` 对 1xx/204/205/304 保留空响应、状态、自定义头和请求 ID；其余状态保持 Result JSON。新增失败测试先复现，再修复；完整测试增至 97 项并通过，ruff 与 wheel/sdist 构建通过。
- 最终质量复核批准，无未解决问题，独立定向运行 22 项相关测试通过。
- 已在原项目目录快进合并代码回 `main`，运行 `uv run --directory packages/python-common --locked --all-extras --python 3.12 pytest -q`：97 项全部通过（CPython 3.12.13）。未修改 Java、服务和前端内部代码；未推送远程。
