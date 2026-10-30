# AI 服务目录与分层

本模块采用“业务包优先，业务内分层”的组织方式，基础包仍为 `com.lzj.rag.ai`。沿用 Java 17、Spring Boot、Maven 和 WebFlux；不为目录示例引入数据库、MyBatis 或新的 Maven 模块。

## 业务归属

| 包 | 负责的能力 | 入口 |
| --- | --- | --- |
| `model` | 模型元数据、对话、视觉、向量与重排，以及共用的模型路由、降级、批处理和熔断 | `ModelController` / `ModelService` |
| `text` | 粗略 Token 估算、完整代码围栏移除 | `TextController` / `TextService` |
| `infra.provider` | 供应商认证、HTTP/SSE 传输、原生协议适配及模型调用错误分类 | `ProviderClient` / `ModelInvocationException` |

通用能力直接依赖仓库的 `packages/java-starters`，本服务内部不再创建 `framework`。公共 `rag-convention` 提供响应与异常契约，公共 Web starter 提供 WebFlux 请求标识、响应构造和异常处理。

对话、视觉、向量和重排共用同一套逻辑模型配置、调用预算和熔断状态，因此统一归属 `model` 业务。`ModelRegistry` 放在 `model.routing`，`RequestGate` 放在 `model.resilience`，二者不是全局通用配置，也不放入 `service.impl`。

## 调用关系

普通模型请求：

```text
ModelController
  → ModelWebConverter：Request → BO
  → ModelService / ModelServiceImpl：规则校验、路由、调用预算、降级与结果校验
  → ModelProviderConverter：BO / 模型配置 → 客户端 DTO
  → ProviderClient：供应商协议与传输
  → ModelProviderConverter：供应商工具、用量、流片段 → BO
  → ModelWebConverter：业务结果 BO → VO
  → Result<VO>
```

流式调用返回 `Flux<ModelEventBO>`。业务层判断首个有效片段、降级时机、结束与错误，并产生业务事件；Controller 把事件数据转为 `ModelEventVO`，再包装为 `ServerSentEvent`。WebFlux 的请求、响应、`Result` 和 SSE 类型只出现在 Web 边界；供应商客户端内部解析上游 SSE 属于其协议适配职责。

文本接口同样经过 `TextService`。其输入和围栏处理结果是单个字符串，直接使用简单类型；只有包含 Token 数和估算标识的业务结果定义 `TokenEstimateBO`。

## 对象和转换器

| 边界 | 命名与位置 | 约束 |
| --- | --- | --- |
| HTTP 输入 | `ChatRequest`、`EmbedRequest`、`RerankRequest`，位于业务的 `controller.request` | 格式约束可使用 Jakarta Validation |
| HTTP 输出 | `ChatVO`、`EmbedVO`、`RerankVO`，位于 `controller.vo` | 对外只返回 VO，保持既有 JSON 字段 |
| 业务契约 | `ChatBO`、`EmbedBO`、`ChatResultBO` 等，位于 `service.bo` | 不依赖 Controller、HTTP 类型或 HTTP 校验注解 |
| 供应商契约 | `ProviderModelDTO`、`ProviderChatRequestDTO` 等，位于 `infra.provider.dto` | 客户端拥有技术契约，不引用业务层类型 |
| 业务枚举 | `ModelTier`、`ModelCapability`，位于 `model.enums` | 沿用既有 JSON/YAML 枚举值 |

消息、图片、工具和向量等从属对象用带有对应后缀的嵌套 record 表达；被多种业务结果共用的 `UsageBO`、`MetricsBO`、`ToolCallBO` 独立定义。

`ModelWebConverter` 仅由 Controller 使用，处理 Request、BO、VO。`ModelProviderConverter` 仅处理 BO、配置值和客户端 DTO，不查询注册器、不决定路由、不发请求。Service 仍保留完整的输入和业务规则检查，因此其他入口直接调用 Service 时也适用相同规则。

## 依赖与扩展

依赖方向为 `model → infra.provider`，各层按需使用公共组件；Controller 可以依赖自己的 Service、Converter 和通用 Web 能力。公共组件不引用业务；`infra` 不引用 `model`、`text` 或应用配置。`ModelConfiguration` 使用 `ai.limits` 的技术参数装配 `ProviderClient`，基础设施本身不读取业务配置注册器。

统一响应通过公共 `Results` 构造。公共 Web starter 根据应用类型选择 MVC 或 WebFlux 自动配置；本服务已有 WebFlux starter，排除公共 starter 默认携带的 MVC starter。请求 ID 存放在 Exchange 和 Reactor Context，Controller 显式传给 `Results` 和业务服务，不依赖线程本地变量。

`ModelInvocationException` 继承公共 `ServiceException`，增加模型调用的 HTTP 状态、可否重试和是否计入熔断，公共异常处理器通过 `HttpStatusAware` 读取状态。`text` 使用公共 `ClientException`。输入和未知错误的默认文案通过 `rag.web.reactive.*` 配置为本服务已有约定；供应商错误分类不进入公共组件。流开始后的错误继续由业务层产生 SSE 事件。启动类位于基础包根部，公共 Web 能力通过自动配置装配。

当前没有数据库和数据表，不创建空的 `dao/entity`、`dao/mapper`、DO、Mapper XML 或迁移脚本。后续增加真实持久化功能时，在拥有该功能的业务包内增加 `dao.entity.*DO` 和 `dao.mapper.*Mapper`，在 Service 中编排数据库事务。跨业务调用通过对方 Service 或明确契约进行。

测试源码按对应生产包组织。模型接口测试继续使用本地 HTTP 假供应商，覆盖路由、推理与工具续接、流式回退、截断错误、嵌入身份、整组重试及重排映射；文本接口在 AI 禁用时独立验证，熔断状态机独立测试。
