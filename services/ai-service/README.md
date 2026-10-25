# AI 服务

Java 17 / Spring Boot 3.5.16 / WebFlux 的独立服务，统一百炼、DeepSeek、硅基流动的 HTTP 协议。仅负责模型调用与结果归一化；工具实际执行、对话记忆和 Agent 编排由 LangGraph 负责。没有数据库、Redis 或启动时的付费模型探测。

## 构建与启动

在 IDEA 导入仓库根 `pom.xml`，选择 JDK 17、IDEA 自带 Maven，在 Maven 窗口执行根 `verify`。本机 PowerShell 等价命令（从仓库根运行）：

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository' verify
java -jar services/ai-service/target/rag-ai-service-0.1.0-SNAPSHOT.jar --ai.enabled=false
```

也可在 IDEA 运行 `com.lzj.rag.ai.AiApplication`。默认端口 8081，可设 `AI_PORT`。无密钥验收用 `AI_ENABLED=false` 或上述参数：`GET /actuator/health` 返回 UP，模型端点返回 503，两个文本工具接口仍可使用。真正调用时配置至少一个聊天供应商密钥并启用 AI；默认三个聊天档位均包含三家供应商，一把聊天密钥即可启动。

## 配置

配置集中在 `src/main/resources/application.yml`，支持外置 YAML（`--spring.config.additional-location=file:./ai-local.yml`）和环境变量。不要把实际密钥提交到仓库。

| 环境变量 | 作用 |
|---|---|
| `BAILIAN_API_KEY`、`DEEPSEEK_API_KEY`、`SILICONFLOW_API_KEY` | 各供应商密钥；空密钥表示该供应商不参与路由 |
| `BAILIAN_BASE_URL`、`DEEPSEEK_BASE_URL`、`SILICONFLOW_BASE_URL` | 兼容模式 API 根 URL，服务追加 `/chat/completions` 或 `/embeddings` |
| `BAILIAN_RERANK_URL`、`SILICONFLOW_RERANK_URL` | 完整原生重排 URL，不再拼接路径；可配置地域或工作空间变体 |
| `BAILIAN_CHAT_MODEL`、`DEEPSEEK_CHAT_MODEL`、`SILICONFLOW_CHAT_MODEL` | 聊天供应商模型名 |
| `BAILIAN_VISION_MODEL` | 视觉模型名，示例 `qwen3-vl-plus` |
| `BAILIAN_EMBED_MODEL`、`SILICONFLOW_EMBED_MODEL` | 向量模型名 |
| `BAILIAN_EMBED_CONFIG_VERSION` | 默认bailian-text-embedding-v4-1024-v1；嵌入空间语义改变时更新，并用新知识库重建 |
| `BAILIAN_RERANK_MODEL`、`SILICONFLOW_RERANK_MODEL` | 重排模型名 |
| `AI_EMBEDDING_MODEL`、`AI_RERANK_MODEL` | 默认逻辑别名，必须在 `ai.models` 中定义 |
| `AI_ENABLED`、`AI_PORT` | 启用状态和监听端口 |

YAML 中模型 ID 是逻辑别名，`model` 才是供应商模型名。默认名称是配置示例，账户、区域与供应商版本决定实际可用性；部署时按开通情况调整名称、能力、长度、维度与 URL。路由没有硬编码供应商模型名，也没有按供应商种类禁止未来的视觉能力。当前 DeepSeek 示例为可覆盖的 `deepseek-flash`。

`ai.models.<alias>` 可配置 `provider`、`model`、`enabled`、`capabilities`、`max-output-tokens`、可选 `timeout`。向量另有 `dimensions`、`allowed-dimensions`、`embedding-space`、`batch-size`。能力包括 CHAT、VISION、THINKING、TOOLS、JSON、EMBEDDING、RERANK。启动检查模型引用、URL、正数参数与聊天每档的可用候选；开启 AI 却没有聊天凭据会启动失败。视觉、向量或重排没有可用候选时返回明确 503，无需为启动准备三家密钥。

`ai.routes.<task>` 配置 `default-tier` 与 `candidates.FAST/STANDARD/STRONG` 的有序逻辑别名列表。`task` 默认 chat；视觉接口默认 vision。先按所需能力与启用状态过滤，再按顺序尝试。`preferredModel` 必须存在且兼容，放在本档候选前面并去重；不能绕过能力检查。`tier` 只决定候选优先级，`thinking` 独立控制推理，默认 false；DeepSeek 使用 `thinking.type`，百炼和硅基流动对声明 THINKING 的模型发送 `enable_thinking`，明确关闭供应商默认推理。

## HTTP 接口

所有普通响应复用 `rag-convention` 的 `code/message/data/requestId`，成功是 `"0"/"success"`。输入错误 HTTP 400，服务禁用或无可用路由 503，入口并发满 429，上游无效响应 502，总超时 504。`X-Request-Id` 可传入 1–128 位字母、数字、点、冒号、下划线或横线，否则生成；响应头、JSON、SSE 和日志使用同一个 ID。健康检查保留 Actuator 格式。

```sh
curl http://localhost:8081/actuator/health
curl -H 'Content-Type: application/json' -H 'X-Request-Id: demo-1' \
  -d '{"text":"你好"}' http://localhost:8081/ai/chat
curl -N -H 'Content-Type: application/json' \
  -d '{"text":"解释向量检索","tier":"STRONG","thinking":true}' http://localhost:8081/ai/chat/stream
```

`POST /ai/chat` 与 `/ai/chat/stream` 接收相同结构。`text` 与 `messages` 必须且只能提供一个。完整示例：

```json
{
  "messages": [{"role":"user","content":"返回一个 JSON 对象"}],
  "task":"chat", "tier":"STANDARD", "preferredModel":"qwen-chat",
  "thinking":false, "json":true, "maxOutputTokens":512
}
```

`json:true` 使用原生 `response_format:{"type":"json_object"}` 并验证完整返回对象；不会自动去掉 Markdown 围栏。每个模型按配置上限约束输出 tokens。结果 `data` 包含 `content`、独立的 `reasoning`、`toolCalls`、实际逻辑 `model`、`finishReason`、`usage` 和 `metrics`（attempts、durationMs、firstTokenMs）。普通非流式请求无法测量上游内部首 token，firstTokenMs 为 null。上游未返回真实 usage 时保留 null，缺少的计数字段也为 null，不用零或估算冒充。

工具定义和回合续接：

```json
{"text":"查询天气","tools":[{"name":"weather","description":"查询城市天气","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],"toolChoice":"auto"}
```

```json
{"preferredModel":"deepseek-chat","messages":[
  {"role":"user","content":"查询天气"},
  {"role":"assistant","reasoning":"上一轮返回的推理文本","toolCalls":[{"id":"call_1","name":"weather","arguments":"{\"city\":\"上海\"}","index":0}]},
  {"role":"tool","toolCallId":"call_1","content":"晴，25℃"}
]}
```

`system/user/assistant/tool` 消息映射到原生协议；`reasoning` 续接为 `reasoning_content`，工具调用返回 id/name/arguments/index。工具参数是 JSON 字符串，执行与校验由调用方负责，服务本身不会执行任何工具。

`POST /ai/vision`、`/ai/vision/stream` 使用同样请求，但要求至少一张图片并匹配 VISION 能力：

```json
{"messages":[{"role":"user","content":"描述图片","images":[{"url":"https://example.com/image.jpg"}]}]}
```

也可用 `{"base64":"...","mediaType":"image/png"}`，不能同时传 URL 与 base64。默认最多 4 张，每张 base64 解码后最多 2MiB，允许 PNG/JPEG/WebP/GIF；入站整体 JSON 默认 16MiB，上游单个响应/SSE 帧默认 4MiB。URL 仅做 HTTP(S) 格式校验并转发，不由本服务下载；实际下载范围与图片内容判断由供应商处理。普通聊天接口包含图片会返回 400。

## SSE 约定

事件是 `start/reasoning/content/tool/usage/done/error`；每条 data 都是 `{"requestId":"...","model":"逻辑别名","payload":...}`。content/reasoning 的 payload 是 `{"text":"增量片段"}`，tool 是 `{id,name,arguments,index}`（增量字段可 null，按 index 累加），usage 是真实计数，done 含 finishReason 与 metrics。例如：

```text
event: start
data: {"requestId":"demo","model":"qwen-chat","payload":{"attempt":1}}

event: content
data: {"requestId":"demo","model":"qwen-chat","payload":{"text":"你好"}}

event: done
data: {"requestId":"demo","model":"qwen-chat","payload":{"finishReason":"stop","metrics":{"attempts":1,"durationMs":120,"firstTokenMs":85}}}
```

服务先等待第一个非空 reasoning/content 或工具 id/name/arguments 片段，再发 start 与该片段；空白字符是有效文本，心跳、空 role 帧不会重置首片段期限。在此之前失败可换模型；可见输出后只发送 error 并结束，不拼接其他模型。无真实 finish 的 EOF 判为错误，`[DONE]` 本身不能代替 finish，usage 尾帧不会丢弃。流开始前可识别的参数、路由和供应商错误返回普通 JSON/HTTP 错误；开始后为 typed error SSE。调用方取消会取消上游订阅并释放并发与熔断探测名额。

## 向量与重排

`POST /ai/embed`，单条与批量二选一：

```json
{"text":"检索增强生成"}
```

```json
{"texts":["文档一","文档二"],"model":"qwen-embed","dimensions":1024}
```

返回 `embeddings:[{index,values}]`（输入原始顺序）、dimensions、实际 model、usage、metrics，以及provider/embeddingSpace/configRevision。显式model或expectedConfigRevision会固定锚点，失败或不可用时明确报错，禁止fallback。仅未指定上述两项的旧调用保留兼容降级，候选须在配置的ai.embedding.fallbacks中且embedding-space与实际维度都相同。`allowed-dimensions`非空才向供应商发送dimensions，否则只接受固定维度。按batch-size拆批，验证index/数量/维度/有限非零向量，绝不返回部分成功或混合向量；未知usage仍为null。默认最多128条、合计100万字符，空白文本无效。

`GET /ai/embedding-models/{alias}?dimensions=1024`返回modelId/provider/providerKind/modelName/dimensions/embeddingSpace/configVersion/configRevision/batchSize/active/protocolVersion，不返回密钥或内部URL，不做付费探测。随后可在embed请求附加expectedConfigRevision；不一致409/MODEL_CONFIG_CHANGED，调用供应商前拒绝。`ai.models.<alias>.config-version`标识部署方固定的嵌入语义版本，默认qwen-embed与backend配置一致；知识库首次发布还会钉住AI修订。元数据active只代表配置可用，真实供应商账户/固定模型版本仍需联调。服务继续依赖受控内网/网关保护。

`POST /ai/rerank`：

```json
{"query":"如何检索","documents":[{"id":"chunk-a","text":"向量检索"},{"id":"chunk-b","text":"全文检索"}],"topN":2,"model":"qwen-rerank"}
```

topN 默认 5、上限为文档数，必须为正数。返回 `results:[{chunkId,index,score}]`、实际 model、usage、metrics；验证索引不重复且 score 有限，按 score 降序、相同 score 按原索引升序。整组单次请求，不拆分后合并不同请求的分数。百炼使用完整原生 URL 与 `input/parameters` 请求、`output.results` 响应；硅基流动使用扁平请求与 results 响应，兼容 meta.tokens 的真实 usage。

## 文本工具与运行限制

```sh
curl -H 'Content-Type: application/json' -d '{"text":"你好 world"}' http://localhost:8081/ai/utils/estimate-tokens
curl -H 'Content-Type: application/json' -d '{"text":"```json\n{\"a\":1}\n```"}' http://localhost:8081/ai/utils/strip-code-fence
```

估算只用粗略汉字/字符比例，返回 `estimated:true`，不能用于计费或强制截断。围栏工具只去掉覆盖整个 trimmed 文本的一层完整围栏，保留混合 prose/多个代码块；不会自动修改模型回答。

`ai.limits` 默认 max-attempts=3（全逻辑请求且每候选只尝试一次）、concurrency=32、total-timeout=90s、connect-timeout=5s、first-token-timeout=20s、stream-idle-timeout=20s。总期限覆盖候选降级、所有向量批次及持续输出流；模型 timeout 可进一步限制一次候选。400/413/422 输入与上下文错误不自动降级；401/403 归类 AUTH 并只尝试下一个候选；429/5xx/网络/超时/无效响应可在预算内降级。进程内每逻辑模型熔断默认连续计数失败 3 次后打开 30s，再允许一个真实业务半开探测。成功关闭，取消和非计数失败释放探测名额；旧并发请求不能关闭新一代已打开的熔断。进程重启会清空状态，多实例互不共享。日志记录 requestId/model/provider/attempt/errorCategory/firstTokenMs/durationMs，不记录密钥、请求正文、响应正文或模型文本。

## 最小 Python 调用

```python
import requests
r = requests.post("http://localhost:8081/ai/chat", json={"text": "你好"}, timeout=100)
r.raise_for_status()
print(r.json()["data"]["content"])
```

```python
import asyncio, json, httpx

async def main():
    async with httpx.AsyncClient(timeout=httpx.Timeout(100, read=30)) as client:
        async with client.stream("POST", "http://localhost:8081/ai/chat/stream",
                                 json={"text": "你好", "thinking": True}) as response:
            response.raise_for_status()
            event, data = "", []
            async for line in response.aiter_lines():
                if line.startswith("event:"): event = line[6:].strip()
                elif line.startswith("data:"): data.append(line[5:].lstrip())
                elif line == "" and data:
                    value = json.loads("\n".join(data)); data = []
                    if event == "content": print(value["payload"]["text"], end="", flush=True)
                    elif event == "error": raise RuntimeError(value["payload"])
                    elif event == "done": break

asyncio.run(main())
```

仓库测试使用本地 HTTP 假供应商，覆盖关键路由、工具续接、首片段与截断、向量整组降级、原生重排、输入和围栏、熔断代次。没有实际供应商密钥时不做付费实时调用，不声称已验证账户模型可用性或真实供应商计费。
