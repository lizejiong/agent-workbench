# Agent Workbench

面向智能体应用的工作台，规划涵盖模型接入、知识检索、Agent 编排、工具调用与任务执行。RAG 是其中的知识能力模块。

当前已实现可独立运行的 AI 服务、知识库后端和 Web 管理台，并提供 Java/Python 公共组件。Agent 运行时与编排尚未实现，`services/agent-service` 为后续扩展预留目录。现有包名和 Maven 坐标继续沿用 `com.lzj.rag`。

```text
packages/
  java-starters/       Java 公共组件（Maven 多模块）
  python-common/       FastAPI 公共包（响应契约、请求上下文、可选 Redis 缓存）
services/
  ai-service/          WebFlux AI 服务（对话、视觉、向量、重排与流式响应）
  backend/             Spring MVC 业务后端（知识库、S3上传、可靠入库、检索、人工维护与持久化删除、pgvector/Flyway）
  agent-service/       FastAPI + LangGraph 服务目录
web/                   React + Ant Design + Vite 知识库管理台
```

## Java 构建

- JDK 17
- Spring Boot 3.5.16（BOM 管理）
- Maven：优先使用 IntelliJ IDEA 自带版本，本机为 3.9.16；仓库同时提供可选 Wrapper
- 根坐标：`com.lzj.rag:rag-parent:0.1.0-SNAPSHOT`

在 IntelliJ IDEA 中导入根 `pom.xml`，Maven 使用 IDEA 自带版本，JDK 选择 17，在 Maven 窗口运行 `verify`。如需复用本次命令行验证缓存，设置本地仓库为项目下的 `.cache/maven/repository`。

未使用 IDEA 时，也可以使用 Wrapper。Windows：

```powershell
.\mvnw.cmd -B "-Dmaven.repo.local=.cache/maven/repository" verify
```

Linux / macOS：

```sh
sh ./mvnw -B -Dmaven.repo.local=.cache/maven/repository verify
```

首次构建需要下载 Maven（未缓存时）及依赖。上述命令将依赖缓存存放在忽略目录 `.cache/maven/repository`。Java 服务初始化后，通过 `<parent>` 继承根 POM 并按需引用组件；仅放入 `<modules>` 不会自动形成继承或引入依赖。

六个组件的职责、使用方式、配置和上游来源见 [Java 公共组件说明](packages/java-starters/README.md)。

## Python 公共包

Python 3.12+，从仓库根目录运行 `pip install -e "./packages/python-common[redis]"`；无需 Redis 时去掉 `[redis]`。FastAPI 示例、缓存生命周期与开发验证命令见 [Python 公共包说明](packages/python-common/README.md)。

AI 服务的启动、模型配置、HTTP/SSE 和 Python 调用示例见 [AI 服务说明](services/ai-service/README.md)。无需供应商密钥时，可用 `AI_ENABLED=false` 启动健康检查与工具接口。

知识库后端已接入根 Maven 构建；配置、启动、数据库迁移和验收见 [运行手册](docs/knowledge-base/RUNBOOK.md)，当前阶段与验证结果见 [进度](docs/knowledge-base/PROGRESS.md)。Web 管理台启动见 [web/README.md](web/README.md)，开发入口为 http://127.0.0.1:5173，使用已有账号登录，不新增用户管理。原 API 导航为`GET /api/knowledge/management`，HTTP 验收为`python scripts/knowledge-base/delivery_acceptance.py`；`agent-service`仍为目录占位。
