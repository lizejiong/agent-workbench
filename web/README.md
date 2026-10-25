# 知识工作台

React + Ant Design + Vite 管理台，直接调用现有知识库后端。提供知识库、上传/URL 导入、文档配置、真实入库任务、Chunk 维护、刷新及检索测试；不建设账号/角色管理，不生成问答内容。

## 本地开发

使用 Node 20.19+（20.x）或 Node 22.12+，先按 [RUNBOOK](../docs/knowledge-base/RUNBOOK.md) 启动后端、数据库、对象存储和所需模型服务。

```powershell
cd web
npm ci --registry=https://registry.npmjs.org
# 后端默认 http://127.0.0.1:8080；不同地址在启动 Vite 的进程设置：
$env:KB_BACKEND_ORIGIN='http://127.0.0.1:8080'
npm run dev
```

打开 <http://127.0.0.1:5173>，使用后端已有账号登录。KB_BACKEND_ORIGIN 只在 Vite 服务端使用，不会进入浏览器包。也可首次复制 `.env.example` 为 `.env.local`；不要覆盖已有配置。代理仅转发 `/api/` 和 `/openapi/`，不用修改后端 CORS。生产必须在 HTTPS 下使用现有 Basic 认证；浏览器凭据仅存页面内存，不写入 URL、Cookie、localStorage 或 sessionStorage，刷新页面需重新登录。

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

管理台 <http://127.0.0.1:8088>，Nginx 同源代理到 `backend:8080`。可叠加 `--profile keyword`，关键词能力仍须对应后端环境配置启用。镜像构建只传入 web 目录，排除本地环境文件和依赖目录，不含密钥。镜像下载和生产设施需部署环境验证，实际执行结果见 [PROGRESS](../docs/knowledge-base/PROGRESS.md)。生产可通过 build args 固定 WEB_NODE_IMAGE、WEB_NGINX_IMAGE 为组织镜像 digest。

也可将 `dist/` 部署到既有静态服务器，参考 `nginx.conf`，将 upstream 改成真实内网后端。保留 Authorization 转发、同源路由、190 秒代理预算及安全响应头；入口提供 HTTPS。上传默认 20 MiB，示例 Nginx 21 MiB 包含 multipart 开销，调整服务端上限时同步调整入口限制。

## 操作约定

- 创建知识库只能选择服务端模型配置；不提供任意模型名输入或直接更换嵌入空间。
- 本地上传只保存原文件，随后显式提交入库。URL 导入可选择保存后提交任务。
- 任务按后端状态轮询：活动 2 秒，页面隐藏 30 秒；终态停止，离开页面取消读取。网络失败显示错误与重载入口。
- 内容维护携带文档版本，409 不自动重试写入。重新入库遇人工维护时，先显示覆盖范围，再用当前版本显式确认。
- 批量启停只操作已选 ID；“全部块”是独立且需确认的操作。文档重新启用保留单独禁用的 Chunk。
- 检索使用 `/api/knowledge/retrieve`，显示实际策略、通道失败/精排回落、余弦/BM25/融合/精排各自分数与来源；没有回答生成。
- 原文件经授权接口下载；界面不展示 objectKey、内部服务地址、原始异常堆栈或模型密钥。
- 范围选择器读取全部分页，超过 100 页明确报错。首版 UI 检索预算为最多 16 库、100 文档、topK 50、问题 4000 字符；更严格服务端配置仍以服务端校验为准。

技术基线：[Vite 运行要求](https://vite.dev/guide/)、[Vite 代理](https://vite.dev/config/server-options)、[Ant Design 上下文](https://ant.design/components/config-provider)。版本与完整依赖锁定在 package-lock.json。
