# Web 聊天入口与接口缺口

2026-11-02。本轮实现现有 Agent 的 Web 入口，不扩展服务端会话 API、图恢复策略或知识检索算法。

## 页面与实际能力

登录沿用原账号，通过 Java `GET /api/knowledge/management` 校验；成功后统一进入 `/#/chat`。聊天页参考当日 [ChatGPT 公开访客页面](https://chatgpt.com/) 的浅色侧栏、居中留白、圆角输入区和黑色发送按钮，保留 Workbench 品牌，不复制未实现的图片/语音/插件按钮。

右上角“后台管理”和侧栏“知识库管理”进入原 `/#/kbs`，后台增加“返回聊天”。知识库、文档和检索路由继续可用；后台仍按原权限服务，不增加管理员身份或跨租户范围。

已实现新对话、本次登录的对话列表/标题搜索、知识库多选、Enter 发送/Shift+Enter 换行和 IME 保护、等待与停止、Markdown/GFM 表格/代码、编号引用/来源抽屉、授权下载、复制问题/回答，以及桌面收起侧栏和手机模态侧栏。

会话列表、草稿和已显示消息在本次已认证 React Provider 内，切到后台再返回仍保留；退出、401 或刷新会清除前端状态，不把认证或对话写入 localStorage/sessionStorage。Python 检查点仍在服务端，清空前端不等于删除服务端记录；没有历史列表/删除 API，不假装跨刷新同步。

`POST /api/agent/chat` 返回完整回答。前端显示真实等待状态，完整返回后渲染，不按字符伪造 SSE；不绕到 Java `/answer` 替代主 Agent，也不自动重放失败请求。失败可重新编辑问题。停止表示停止等待，跨服务清理仍受原 deadline/取消语义约束。

## 复用的契约

| 用途 | 已有接口 | 本轮使用方式 |
|---|---|---|
| 登录校验 | Java `GET /api/knowledge/management` | 原 Basic，凭据只在页面内存 |
| 知识范围 | Java `GET /api/kbs` | 全部有界分页；未指定则按 Java 默认授权范围 |
| Agent 对话 | Python `POST /api/agent/chat` | 首轮 question + 可选 kbIds，后续仅 question/sessionId，不发送旧消息/证据 |
| 原文件 | Java `GET /api/documents/{id}/file` | ApiClient 重新认证下载，不访问响应中的任意 URL |
| 来源维护 | 原 `/#/documents/{id}?tab=chunks` | 跳转后台当前文档，继续原授权和版本规则 |

首次请求后固定知识范围，调整范围须新建对话。租户、用户、服务令牌、委托和模型配置不由浏览器作为工具参数填写；所选库只是请求意图，Java 保留最终授权、数量和嵌入空间校验。

只有同一回答实际返回的 citations 才转为可点击编号；不同请求的局部 E1 保持各自 K 映射。代码示例中的编号不链接化。来源展示真实文件名、定位与人工维护标识，无摘录 API 不拼接假正文。原始 HTML 被跳过，答案中的外部图片不加载，任意链接不激活；下载路径由 documentId 构造。引用展示不等于事实正确。

NO_EVIDENCE、NEEDS_CLARIFICATION、UNAVAILABLE、降级/截断按实际 retrievals 展示，依赖故障不说成资料不存在；不展示原始错误正文、内部地址、提示词或密钥。

## 代理配置

| 场景 | 路由 | 目标配置 |
|---|---|---|
| Vite dev/preview | `/api/agent/*` | `KB_AGENT_ORIGIN`，默认 `http://127.0.0.1:8090` |
| Vite dev/preview | 其他 `/api/*`、`/openapi/*` | 原 `KB_BACKEND_ORIGIN`，默认 `http://127.0.0.1:8080` |
| Nginx 镜像 | `/api/agent/*` | 运行时 `KB_AGENT_UPSTREAM`，默认 `http://agent-service:8090` |
| Nginx 镜像 | 其他 API | 原 `backend:8080` |

开发目标是无凭据/路径的 HTTP(S) origin，均为 Vite 服务端配置，不通过 VITE_* 下发。Vite 显式监听浏览器响应关闭并销毁 Python 上游请求；Nginx 关闭 Agent 缓冲、保留断连传播并转发请求/trace 标识。

`web/nginx.conf` 现在是 envsubst 模板，Dockerfile 放入 `/etc/nginx/templates/default.conf.template`，由官方 Nginx entrypoint 生成配置。Agent 使用 Docker DNS 延迟解析，未部署时不阻止原知识后台启动，只令聊天不可用。静态部署须替换模板变量、后端地址和 resolver；HTTPS 上游启用 SNI/CA 验证，私有 CA 配置对应信任文件。现有知识库 Compose 不自动新建 Agent，部署方须接入同网络服务或填写可达受控地址。

## 经代码确认的接口缺口

以下为建议契约，尚未实现或调用：

| 优先级 | 建议能力/路由 | 用途与约束 |
|---|---|---|
| P1 | 当前用户身份查询（Java 认证边界） | 历史列表不应依赖先授权某个库才能取得稳定 tenant/user；复用账号认证，不接受自报身份，不新增 IAM |
| P1 | `GET /api/agent/sessions` | 按当前身份分页列出会话 ID/标题/更新时间/运行状态，恢复跨刷新侧栏 |
| P1 | `GET /api/agent/sessions/{id}/messages` | 返回用户消息/答案及经当前权限确认的引用，禁止直接暴露原始图、ToolMessage、系统提示、凭据；明确旧答案/失效资料策略 |
| P1 | `PATCH /api/agent/sessions/{id}`、`DELETE /api/agent/sessions/{id}` | 服务端重命名/删除，检查版本和归属；协调运行中的图与检查点，前端移除不等于服务端删除 |
| P1 | `POST /api/agent/chat/stream` | Python 原生 Agent 流，区分 metadata、正文、来源、工具状态、错误和持久终态；不复用 Java 独立问答会话 |
| P1 | 运行查询/显式取消（例如 `/api/agent/runs/{id}`） | 首轮及时返回 runId/sessionId，断线后查询权威状态和主动取消；当前响应结束才返回 ID，无法可靠恢复丢失的首轮响应 |
| P2 | `GET /api/agent/capabilities` | 可用模型/工具和实际输入/范围/并发上限，支持有依据的模型选择；不展示凭据，不将已配置视为依赖健康 |
| P2 | 当前授权的证据摘录读取 | 来源预览须核对定位、generation/hash 与当前权限；原文件或当前 Chunk 列表不等于旧证据快照 |

聊天附件、语音和图片生成另需会话资源/工具契约，本轮不展示空按钮，也不把知识库上传假装成聊天附件。建议下一轮先做稳定身份、会话列表/历史和运行状态，再做流式及编辑/删除；本轮不自动实施。

## 验证

`npm run build` 和 21 项前端检查通过，本轮仅新增 2 项来源编号/代码示例边界。浏览器使用真实 Chromium、Vite、Uvicorn、现有 FastAPI/LangGraph/SQLite；Java 管理/检索和模型为明确 HTTP 协议桩。覆盖登录跳转、后台往返、范围/会话契约、多次引用、下载、四种状态、浏览器取消至 Python/Java 通知、IME、手机布局和退出清理。

机器回执见 [CHAT_VALIDATION.json](CHAT_VALIDATION.json)。未重跑真实 Java/PG/MinIO/供应商，不把前序验收计入本轮，也未运行全量 Maven verify。Nginx/Compose 已更新配置，应用镜像和真实生产代理尚未实跑；局部浏览器联调不等于生产部署或语义效果验收。
