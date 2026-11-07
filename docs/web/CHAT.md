# Web 聊天与 Java 历史

2026-11-05。当前能力替代首版“仅本次登录对话 + 完整 JSON”的范围；原界面风格、后台路由和知识检索职责保留。

## 已实现

登录后进入 /#/chat；浅色侧栏、居中消息区、圆角输入框、Markdown 和移动端布局。顶部/侧栏进入知识后台，后台可返回聊天。凭据仅在内存，刷新后重新认证，业务历史仍由 Java 保存。

侧栏从 Java 分页恢复本人历史，支持标题搜索、加载更多、新对话、重命名和删除。进入会话从最新消息页开始，向前加载更早消息并按 sequence 排序；重新登录和不同设备使用同一组接口。知识范围在 Java 创建时验证并固定。

发送前先创建 Java 会话，再把 conversationId/requestId/messageId/question 发往 Python SSE。真实展示增量正文与知识工具状态；取消/失败/超时仍显示已保存部分内容。刷新只读 Java 历史和真实状态，仍在运行的会话有有界间隔轮询；不自动重发问题或恢复工具。

只将实际引用变成来源按钮，保留原 Markdown HTML/图片/任意外链限制。历史来源抽屉展示经当前权限确认的证据摘录、定位和原文件下载；失效助手消息由 Java 整体隐藏。工具仅展示安全状态，不展示原始图或内部错误。

## 接口与分流

| 能力 | 目标 |
|---|---|
| 登录、知识范围、原文件、知识后台 | Java 原接口 |
| 创建/列表/重命名/删除会话、消息/运行查询、显式取消 | Java /api/conversations/* |
| 本人回答偏好、保存与恢复默认 | Java /api/agent/preferences、/reset |
| 主 Agent 原生流 | Python POST /api/agent/chat/stream |
| 兼容完整 JSON | Python POST /api/agent/chat，Web 正常聊天不使用此替代流 |

完整请求、响应、状态码和 SSE 见 [会话契约](../rag/CONVERSATIONS.md)。Vite dev/preview 同源 /api/agent/chat 及其流路径 → KB_AGENT_ORIGIN（默认 http://127.0.0.1:8090），偏好及其他 /api/* → KB_BACKEND_ORIGIN（默认 http://127.0.0.1:8080）。这些配置只在服务端，不下发浏览器。

2026-11-07新增账号菜单“回答偏好”：语言、详略、格式只保存在Java，之后新回答按run绑定版本读取；可恢复默认，旧页保存冲突后重新加载，不自动覆盖。无本地偏好缓存或自动事实记忆；实际浏览器记录见 [PREFERENCES_VALIDATION.json](../rag/PREFERENCES_VALIDATION.json)。

Nginx 模板继续用 KB_AGENT_UPSTREAM，关闭 Agent 缓冲、转发认证与 trace、传播断连。生产 Nginx/镜像仍需实际部署验收；既有知识库 Compose 不自动部署 Agent。

## 运行语义

每次明确发送分配新的 requestId/messageId。相同请求标识的传输重提不执行第二次图，但不提供流事件回放；缺失 done 或连接失败时查询 Java。加载历史、切换页面和轮询均不生成答案。

停止时既调用 Java 取消又终止当前连接，随后短暂复查历史，等 Python 保存取消前的最后片段。切换/退出会清理浏览器状态和请求；服务端删除才会删除业务历史。界面不会将未知的持久状态显示成已成功保存。

## 验证与剩余范围

当前验证见 [CONVERSATION_VALIDATION.json](../rag/CONVERSATION_VALIDATION.json)。包含真实 Java/PG/Python 原生流集成，浏览器层采用真实 Chromium/Vite/FastAPI/LangGraph，Java和模型为协议桩。首版15项交互验证作为历史记录保留在 CHAT_VALIDATION.json，不重算成本轮覆盖。

仍未实现附件/语音、模型选择能力接口、业务工具、人机中断恢复、流事件重放及生产部署。摘要是用户问题提取摘要；不把它描述为已实现完整长期记忆或真实供应商效果保证。
