# Java 公共组件迁移实施计划

> **给 agentic workers：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐项执行本计划。步骤使用 checkbox（`- [x]`）语法跟踪状态。

**目标：** 将现有铁路项目中高概率使用的六个 Java 公共模块迁入当前仓库，统一构建，并保留服务目录为空的约束。

**架构：** 根 `pom.xml` 同时承担父 POM、依赖管理和 Java 模块聚合。`packages/java-starters` 聚合 `convention`、`common`、`web`、`designpattern`、`cache`、`log`；服务按需依赖组件。Python、前端和服务内部目录本次不初始化。

**技术栈：** Java 17（与当前机器 JDK 对齐）、Spring Boot 3.5.16、IDEA 自带 Maven 3.9.16、Fastjson2 2.0.65；Redisson 核心包支持按需装配的分布式锁和布隆过滤器。按用户后续指定，正式测试和打包使用 IDEA 自带 Maven，Wrapper 保留为可选入口。

---

### 任务 1：固定源快照并迁移基础代码

**文件：** `packages/java-starters/{convention,common,web,designpattern,cache,log}/pom.xml`、各模块原有 `src/main` 和 `src/test`，以及 `packages/java-starters/README.md`。

- [x] 从仓库 `lizejiong/railway-ticketing-platform` 的提交 `9529414959dea7fff966861131c3b95ff6ad14fb` 读取六个模块全部文件，保留原测试；拒绝读取失败或 SHA 不符的文件。
- [x] 对每个读取成功的 UTF-8 文件执行下列精确重命名，并按同样规则改写路径：

```javascript
function migrate(text) {
  return text
    .replaceAll('com.lzj.railway', 'com.lzj.rag')
    .replaceAll('com/lzj/railway', 'com/lzj/rag')
    .replaceAll('railway', 'rag')
    .replaceAll('Railway', 'RAG');
}
// 目标路径为 sourcePath.replace('backend/components/', 'packages/java-starters/').
```

- [x] 在组件说明中记录上游仓库、完整提交和本地调整；本次不迁移用户、持久层、分库分表、Snowflake 或幂等模块。

### 任务 2：父 POM 和构建入口

**文件：** 根 `pom.xml`、`packages/java-starters/pom.xml`、六个模块 POM、`.gitignore`、`.mvn/wrapper/maven-wrapper.properties`、`mvnw`、`mvnw.cmd`、根 `README.md`。

- [x] 根坐标使用 `com.lzj.rag:rag-parent:0.1.0-SNAPSHOT`，`packaging=pom`，仅聚合 `packages/java-starters`。组件聚合坐标为 `rag-java-starters`，父路径为 `../../pom.xml`。
- [x] 根 POM 导入 Spring Boot 3.5.16 BOM，管理六个内部模块和 Fastjson2 版本；Java release=17，UTF-8，编译时启用 `parameters`。使用原父 POM 的 compiler 3.16.0、surefire 3.6.0；Spring Boot 打包插件放在 pluginManagement，组件输出普通 jar。
- [x] 六个子模块继承 `rag-java-starters`，父路径 `../pom.xml`；第三方和内部依赖版本从根继承。
- [x] 为可重现构建加入 Maven 3.9.16 only-script wrapper；构建缓存使用忽略目录 `.cache/maven/repository`，不提交二进制和构建输出。
- [x] 先执行模型验证，再执行全量测试及打包：

```powershell
.\mvnw.cmd -B -Dmaven.repo.local=.cache/maven/repository validate
.\mvnw.cmd -B -Dmaven.repo.local=.cache/maven/repository verify
```

### 任务 3：缓存组件按需装配

**文件：** cache 模块中的 `RedisDistributedCache.java`、`CacheAutoConfiguration.java`、`CacheProperties.java`、`pom.xml` 及相应测试。

- [x] 保留普通 JSON 缓存、TTL、key builder、原子 Lua 操作；普通缓存只要求 `StringRedisTemplate`。
- [x] 增加 `rag.cache.bloom.enabled`，默认 false；只有开启时才初始化布隆过滤器。分布式锁和布隆过滤器为可选能力，未装配时高级调用抛出明确配置错误，普通缓存仍可正常使用。
- [x] 用 `redisson` 核心依赖代替强制引入 `redisson-spring-boot-starter`，由需要分布式能力的服务提供 `RedissonClient`。保留核心 jar 以兼容公开接口类型，按需装配客户端与高级能力。
- [x] 先补充以下行为测试，再调整实现，并在全量构建中验证：只有 Redis template 时能够装配普通缓存；默认不会初始化 Bloom；显式启用且存在客户端时才初始化；缺失高级能力的 `safeGet` 不得静默丢弃数据。
- [x] 运行缓存及所依赖模块测试（本次由全量 verify 覆盖，未重复执行单模块命令）：

```powershell
.\mvnw.cmd -B -Dmaven.repo.local=.cache/maven/repository -pl packages/java-starters/cache -am test
```

### 任务 4：日志、响应和请求关联

**文件：** log 模块 `ILog.java`、`ILogAspect.java` 及测试；web 模块 `Results.java` 和请求关联组件及测试。

- [x] 日志注解默认关闭完整参数和结果记录，保留显式开启的配置、长度限制和原有异常语义。避免把方法返回时间宣称为流式请求完成时间；流式全程统计留在业务执行边界。
- [x] `Result` 的跨语言 JSON 契约固定为 `code`、`message`、`data`、`requestId`；派生 `isSuccess` 不作为额外 JSON 字段。
- [x] HTTP 请求入口读取或生成 `X-Request-Id`，经请求上下文供 `Results` 复用并写回响应头；结束时清理线程上下文。显式传入 requestId 保持最高优先级。
- [x] 保留 MVC 异常处理；说明 SSE 不套普通 JSON 响应包装。
- [x] 增加契约序列化、请求关联清理和日志默认不记录正文的验证，运行 web、log 测试后运行全量 verify。

### 任务 5：复核和交付

- [x] 按用户范围检查：六个公共模块完成迁移，AI 模型服务、业务后端、Agent 服务和前端目录仍仅保留已有占位文件。
- [x] 独立检查依赖边界、可选装配、配置前缀和服务无关性；修复检查发现的问题并复验。
- [x] 执行下列检查，记录测试数量、构建结果及已知限制：

```powershell
rg -n 'railway|com/lzj/railway' packages/java-starters --glob '!README.md'
git diff --check
.\mvnw.cmd -B -Dmaven.repo.local=.cache/maven/repository verify
```

- [x] README 说明六个模块的用途、配置和 Maven 验证命令，后续模块按需迁移。

## 验证记录

- 2026-10-17：使用 IntelliJ IDEA 2026.2.1 自带 Maven 3.9.16 和本机 JDK 17.0.20 执行完整 `verify`，结果 `BUILD SUCCESS`。
- 本次实际命令：

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' -B -ntp "-Dmaven.repo.local=.cache/maven/repository" "-Daether.connector.requestTimeout=30000" "-Daether.connector.connectTimeout=10000" "-Daether.connector.http.retryHandler.count=1" verify
```

- 测试合计 83，失败 0、错误 0、跳过 0：convention 9、common 11、web 15、designpattern 7、cache 33、log 8。
- 六个组件 JAR 已生成，均无 BOOT-INF；web/cache/log 包含自动配置注册文件。
- 修复复核发现的亚毫秒 TTL 退化永久缓存问题；适配 Redisson 新重载，测试匹配器改为 anyString。
- 独立规格检查及代码质量检查通过。原始快照 64 个文件路径完整保留。
- 业务服务、AI 服务、Agent、Python 公共包与前端目录仅保留原占位文件；未创建服务内部工程。
- 测试使用 mock 验证缓存行为，未连接真实 Redis。根父 POM 模型验证、范围与包结构检查通过。
