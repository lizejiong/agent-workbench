# Java 公共组件

首批迁移 `convention`、`common`、`web`、`designpattern`、`cache`、`log` 六个模块，业务服务按需依赖。

| 目录 | Maven artifactId | 职责 |
| --- | --- | --- |
| convention | rag-convention | 错误码、异常、分页、通用响应契约 |
| common | rag-common | 断言、枚举、对象和线程等工具 |
| web | rag-web-spring-boot-starter | Spring MVC 全局异常处理、响应构造、请求 ID |
| designpattern | rag-design-pattern | 不依赖 Spring 的策略选择、责任链和构建器接口 |
| cache | rag-cache-spring-boot-starter | Redis JSON 缓存、TTL、Key、按需启用的防穿透/击穿能力 |
| log | rag-log-spring-boot-starter | 注解式方法调用日志 |

## 业务服务接入

父 POM 使用 `com.lzj.rag:rag-parent:0.1.0-SNAPSHOT`，内部组件依赖版本由根管理。服务声明需要的组件即可：

```xml
<dependency>
    <groupId>com.lzj.rag</groupId>
    <artifactId>rag-web-spring-boot-starter</artifactId>
</dependency>
```

普通组件构建为 jar；Spring Boot 可执行打包由应用模块显式启用，父 POM 只管理插件配置。

## 缓存

普通缓存依赖 Spring Data Redis 自动配置提供的 `StringRedisTemplate`，值使用 UTF-8 JSON，支持 Duration TTL。TTL 最小为 1 毫秒，小于该精度或毫秒转换溢出时在操作 Redis 前拒绝，避免原子批量写入退化为永久缓存。跨 Java / Python 共用数据时，先约定 Key、JSON 字段和 TTL；Python 使用自己的 Redis 客户端。

```yaml
rag:
  cache:
    key-prefix: rag
    lock-key-prefix: 'rag:cache:lock:'
    bloom:
      enabled: false
      name: 'rag:cache:bloom'
      expected-insertions: 1000000
      false-positive-probability: 0.01
```

布隆过滤器默认关闭。需要高级缓存能力时，由应用提供 `RedissonClient` 并显式开启 Bloom；合法 Key 必须预热或通过 `safePut` 写入。组件保留 Redisson API 类型的核心 jar 依赖，但不自动配置或启动 Redisson 客户端。普通 `get`、`put`、`getOrLoad` 不要求高级组件；高级能力缺失时 `safeGet`/`safePut` 会给出明确配置错误。多 Key 原子写入要求所有 Key 使用相同 Redis Cluster hash tag。

## 响应与请求 ID

普通 JSON 响应约定为 `code`、`message`、`data`、`requestId`；成功 code 为字符串 `"0"`。Python 的响应模型可以对齐这一约定。`isSuccess()` 是 Java 便利方法，不额外输出为 JSON 字段。

Web starter 在请求入口读取或生成 `X-Request-Id`，写入响应头；`Results.success(...)` 和 `Results.failure(...)` 在请求期间复用同一 ID。显式传入 ID 优先，请求结束清理上下文。跨服务传播由各服务的 HTTP 客户端负责；异步线程需要显式传递上下文。

SSE 使用业务自己的事件格式，不包装为普通 JSON `Result`；响应已开始后的错误通过流事件表达。

## 日志

```java
@ILog("调用模型")
public String chat(String text) {
    return modelClient.call(text);
}
```

默认记录方法名、操作、耗时和异常，关闭完整参数及返回值记录。经过业务审查确需正文日志时，可显式设置 `recordArgs = true` / `recordResult = true`；长度受 `rag.log.max-content-length` 限制（默认 4096）。

方法 AOP 耗时只表示方法调用耗时。返回流或异步对象时，完整执行耗时、token 和最终结果由业务执行边界统计；此组件不订阅、消费或缓冲流。

## 上游来源和范围

迁移来源：[lizejiong/railway-ticketing-platform](https://github.com/lizejiong/railway-ticketing-platform)，固定提交 [`9529414959dea7fff966861131c3b95ff6ad14fb`](https://github.com/lizejiong/railway-ticketing-platform/commit/9529414959dea7fff966861131c3b95ff6ad14fb) 的 `backend/components`。保留六个模块的原有源码、自动配置声明和测试；Java 包名改为 `com.lzj.rag`，Maven 与配置前缀改为 `rag`。

本地调整：合并父 POM 与依赖管理；缓存高级能力按需装配，并修复亚毫秒 TTL 退化为永久缓存的边界；日志正文记录默认关闭；明确跨语言响应契约和请求 ID 关联。

用户、鉴权、持久层、分库分表、分布式 ID、幂等和 RocketMQ 封装后续按实际需求迁移。Maven Wrapper 脚本来自 Apache Maven Wrapper 3.3.4，保留其 Apache License 声明。
