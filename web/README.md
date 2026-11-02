# Workbench 聊天与知识后台

React + Ant Design + Vite 应用。登录后进入 `/#/chat`，通过 Python 主 Agent 对话；右上角进入原知识后台，提供知识库、上传/URL 导入、文档配置、入库任务、Chunk 维护、刷新及检索测试，不建设新的账号/角色管理。

聊天页提供本次登录的对话、新建/切换/搜索、知识范围、Markdown/真实来源、复制和停止。当前 Agent 为完整 JSON 响应，不模拟流式；跨刷新历史、重命名/删除和 SSE 尚缺服务端接口。功能和接口缺口见 [Web 聊天说明](../docs/web/CHAT.md)。

## 界面设计

聊天页使用独立的浅色侧栏、留白消息区、圆角输入框和黑色操作按钮，样式在 src/chat/chat.css，不改变后台主题。后台沿用深色导航、紫色主操作与浅灰工作区，主题集中在 src/theme.ts。Ctrl/Cmd+K 搜索知识库；分块桌面默认表格、手机默认卡片，可手动切换，点击正文打开详情抽屉。批量操作只作用于明确选择的块，全量操作位于“更多操作”并另行确认。后台设计见 [界面设计说明](../docs/knowledge-base/WEB_DESIGN.md)。

界面采用紧凑布局：侧栏 208px、顶栏 56px、常规控件 32px，去掉工作流/指南、统计引导卡和装饰性图标。列表筛选并入标题栏；文档下载/删除收进“更多”。上传和 URL 导入先显示必要字段，入库参数在“高级设置”中修改；折叠时仍提交并校验默认值，长内容仅在弹窗正文区滚动，底部操作保持可见。分块仅在选中后显示批量工具栏，卡片来源为摘要，详情仍完整保留。

## 本地开发

使用 Node 20.19+（20.x）或 Node 22.12+，先按 [RUNBOOK](../docs/knowledge-base/RUNBOOK.md) 启动后端、数据库、对象存储和所需模型服务。

```powershell
cd web
npm ci --registry=https://registry.npmjs.org
# 后端默认 http://127.0.0.1:8080；不同地址在启动 Vite 的进程设置：
$env:KB_BACKEND_ORIGIN='http://127.0.0.1:8080'
$env:KB_AGENT_ORIGIN='http://127.0.0.1:8090'
npm run dev
```

打开 <http://127.0.0.1:5173>，使用后端已有账号登录后进入聊天页。KB_BACKEND_ORIGIN 和 KB_AGENT_ORIGIN 只在 Vite 服务端使用，不会进入浏览器包。也可首次复制 `.env.example` 为 `.env.local`，不要覆盖已有配置。`/api/agent/*` 优先代理到 Python，其他 `/api/` 和 `/openapi/` 发往 Java，不用放开 CORS。Python 先按 [服务说明](../services/agent-service/README.md) 配置并启动，Java 需启用原内部委托能力；未启动 Python 时仍可从聊天页进入后台。生产使用 HTTPS；凭据只存内存，不写入 URL、Cookie、localStorage/sessionStorage，刷新需重新登录。

登录预填：在 Git 忽略的 `.env.local` 设置 `KB_LOGIN_DEFAULT_USERNAME`、`KB_LOGIN_DEFAULT_PASSWORD`。开发和生产构建均将其作为可编辑的表单初值，仍需点击登录；未设置或清空时为空白。预填值会随前端资源下发，访问者可读取，不属于保密凭据；不要提交真实值，也不要填入模型或存储密钥。修改后重启开发服务或重新构建生产包；仅修改容器运行时环境不会改变已生成的静态文件。

```powershell
npm run check
npm test
npm run build
npm run preview
```

preview 为本地构建预览（4173），复用同一代理配置，不作为生产服务器。Hash 路由支持复制文档链接；受保护资源仍由后端重新校验归属。

## 容器与静态部署

仓库根目录，已有后端 Compose `.env` 配置后：

```powershell
docker compose --env-file deploy/knowledge-base/.env -f deploy/knowledge-base/compose.yml --profile web up -d --build
```

入口 <http://127.0.0.1:8088>，Nginx 将 Agent 路径转到运行时 `KB_AGENT_UPSTREAM`（默认 `http://agent-service:8090`），其他 API 仍发往 `backend:8080`。现有 Compose 不自动部署 Agent，请接入同网络服务或配置可达受控地址；Agent 延迟解析，不因缺少其 DNS 阻止后台启动。可叠加 `--profile keyword`，相关配置仍须启用。镜像构建只传 web 目录并排除本地环境文件/依赖，不包含模型密钥。镜像和生产设施需部署验收；本轮未执行应用镜像/Nginx 真联调。生产可通过 build args 固定 WEB_NODE_IMAGE、WEB_NGINX_IMAGE 为组织镜像 digest。

也可将 `dist/` 部署到现有静态服务器。`nginx.conf` 是 envsubst 模板，镜像 entrypoint 会生成实际配置；手动部署须替换 KB_AGENT_UPSTREAM、Java 地址和 Docker resolver，保留 Nginx 自身变量。HTTPS 上游使用 SNI/CA 校验，私有 CA 需配置信任文件。保留 Authorization、同源路由、190 秒预算和安全响应头，入口使用 HTTPS。上传默认 20 MiB，Nginx 21 MiB 包含 multipart 开销，改上限时同步调整。

## 操作约定

- 创建知识库只能选择服务端模型配置；不提供任意模型名输入或直接更换嵌入空间。
- 本地上传只保存原文件，随后显式提交入库。URL 导入可选择保存后提交任务。
- 任务按后端状态轮询：活动 2 秒，页面隐藏 30 秒；终态停止，离开页面取消读取。网络失败显示错误与重载入口。
- 内容维护携带文档版本，409 不自动重试写入。重新入库遇人工维护时，先显示覆盖范围，再用当前版本显式确认。
- 批量启停只操作已选 ID；“全部块”是独立且需确认的操作。文档重新启用保留单独禁用的 Chunk。
- 后台检索测试使用 `/api/knowledge/retrieve`，显示实际策略、通道失败/精排回落、各类分数与来源，不调用生成；聊天独立调用 `/api/agent/chat`。
- 原文件经授权接口下载；界面不展示 objectKey、内部服务地址、原始异常堆栈或模型密钥。
- 范围选择器读取全部分页，超过 100 页明确报错。首版 UI 检索预算为最多 16 库、100 文档、topK 50、问题 4000 字符；更严格服务端配置仍以服务端校验为准。

技术基线：[Vite 运行要求](https://vite.dev/guide/)、[Vite 代理](https://vite.dev/config/server-options)、[Ant Design 上下文](https://ant.design/components/config-provider)。版本与完整依赖锁定在 package-lock.json。
