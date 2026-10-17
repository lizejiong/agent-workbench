# RAG Agent 项目

当前仓库包含 Java 公共组件、Python 公共包和服务目录骨架。

```text
packages/
  java-starters/       Java 公共组件（Maven 多模块）
  python-common/       FastAPI 公共包（响应契约、请求上下文、可选 Redis 缓存）
services/
  ai-service/          独立 Java AI 服务目录
  backend/             Java 业务后端目录
  agent-service/       FastAPI + LangGraph 服务目录
web/                   React 管理后台和对话页目录
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

业务服务和前端仍为目录占位，尚未创建工程内部结构。
