# 后端目录与分层

后端继续使用现有 `rag-backend` Maven 模块，基础包保持 `com.lzj.rag.backend`。先按真实业务划分包，再在业务内部划分技术层；配置键、HTTP 路径、JSON 字段及数据库迁移沿用原约定。

```text
src/main/java/com/lzj/rag/backend/
├── BackendApplication.java
├── knowledgebase/                   知识库、所有权与固定模型绑定
│   ├── controller/request/          HTTP 请求及格式约束
│   ├── controller/vo/               对外响应
│   ├── controller/advice/           知识库错误码及数据库错误的业务适配
│   ├── service/impl/                业务服务接口与实现
│   ├── service/bo/                  业务输入和结果
│   ├── service/internal/            实体读取及行锁的内部访问契约
│   ├── dao/entity/                  KnowledgeBaseDO
│   ├── dao/mapper/                  KnowledgeBaseMapper
│   ├── converter/                  Web 与持久化对象的纯转换
│   └── exception/                  知识库业务错误分类
├── document/                        文档上传、元数据与删除清理
│   ├── controller/、service/、dao/、converter/
│   ├── config/                     文档接参所需的 JSON 格式约束
│   ├── upload/                     临时文件校验、上传预留与失败补偿
│   └── storage/                    存储技术错误到既有业务错误的适配
├── chunk/                           人工切片维护
│   ├── controller/、service/、dao/、converter/、config/
│   └── splitting/                  结构切片算法
├── ingestion/                       持久化入库任务
│   ├── controller/、service/、converter/、config/
│   ├── pipeline/                   读取、解析、切片、嵌入和发布编排
│   ├── publishing/                 新代次事务写入与原子发布
│   ├── schedule/                   有界执行、扫描及心跳
│   └── cleanup/                    失效代次回收
├── parser/                          文件解析策略
│   ├── controller/、config/、service/bo/、enums/
│   ├── strategy/、routing/          具体解析策略及注册选择
│   ├── process/                    隔离解析子进程及结构提取
│   └── schedule/、support/          临时文件回收、格式及表格辅助
├── model/                           后端模型绑定与模型能力调用
│   ├── controller/、service/、converter/、config/
│   ├── catalog/                    嵌入配置校验与稳定身份计算
│   └── client/                     后端业务契约到 AI 服务的适配
├── refresh/                         URL 导入与定时刷新
│   ├── controller/、service/、converter/、config/
│   └── importing/、download/、state/、schedule/、cleanup/
├── search/                          向量及混合检索
│   ├── controller/、service/、converter/、config/
│   ├── channel/、postprocess/、budget/
│   ├── repository/                 有权限与当前版本语义的证据访问
│   │   ├── vector/dto/             pgvector 记录与范围契约
│   │   └── keyword/dto/            Elasticsearch 索引记录契约
│   └── schedule/                   关键词同步扫描与心跳
├── operations/                      管理导航、审计及业务指标
│   ├── controller/、service/、converter/
│   └── config/、metrics/、logging/
├── rag/                             查询理解与共用证据上下文
│   ├── controller/request/、controller/vo/
│   ├── service/、service/impl/、service/bo/
│   ├── config/                     查询、配额和模型窗口预算
│   └── prompt/                     中文模板按版本及哈希加载
├── security/                        认证身份和业务访问规则
│   └── config/、model/
└── infra/                           跨业务复用的技术能力
    ├── storage/                    S3 技术客户端
    │   └── config/、exception/
    └── persistence/                PostgreSQL UUID、JSONB 类型映射
```

## 对象和调用边界

调用方向是 `Controller → Service → 业务数据访问或技术客户端`。`controller/request` 保留 HTTP 输入格式约束；Controller 使用 Web Converter 转为 BO，服务返回 BO 后再转换为 `controller/vo`。BO 使用业务名称加 `BO` 后缀，数据库实体统一使用 `DO`，外部存储记录使用有明确访问边界的 `DTO`。

`UploadSourceBO` 只描述文件名、声明大小和输入流工厂。上传 Controller 将 `MultipartFile` 转为该业务输入，URL 下载直接生成相同输入；业务服务与文件校验流程因此可以被其他入口复用。文件真实大小、摘要及格式仍由同一流水线校验。

Service 的事务、版本检查和租约判断仍位于业务实现。`service/impl` 仅放对应 Service 的实现；切片算法、入库流水线、发布、下载和任务调度按各自职责组织。后台入口通过业务契约复用相同规则。

`KnowledgeBaseService` 与 `DocumentService` 面向 Controller、工具及其他业务入口，只使用业务对象。需要读取 DO 或协调行锁的后端流程分别依赖 `service/internal/KnowledgeBaseAccess` 和 `service/internal/DocumentAccess`，不得由 Controller 直接使用。公开服务与内部访问契约由同一个 Spring 组件实现；拆分接口不增加事务，不改变既有查询或锁顺序。`lockOwned` 必须在调用方已有的事务中使用，依次取得知识库、文档及任务锁，锁随该事务结束而释放。

源文件下载通过 `DocumentService.openFile` 校验文档及知识库归属，再读取对象存储，返回 `DocumentFileBO` 的元数据和内容流。Controller 仅构造下载响应，不接触 DO 或对象存储键；内容流由调用方关闭，HTTP 入口交由 Spring 资源响应管理。

Web Converter 只做字段转换；Data Converter 只做持久化对象与 BO 的转换。模型目录及检索所需的固定配置快照归业务所有。pgvector 和关键词索引含有权限、代次与发布修订语义，归 `search/repository`；共享的 `infra/storage` 只负责技术存取，不引用具体业务包，由 `document/storage` 转换技术失败。

## 公共组件

统一响应、分页及 Web 基础能力复用 `packages/java-starters`，后端不增加本地 `framework` 副本。知识库专用错误码、数据库错误映射、认证和 JSON 格式规则保留在对应业务中。

`BackendApplication` 位于基础包根部，组件及带 `@Mapper` 的访问接口可被扫描。MyBatis 的类型处理器包配置为 `com.lzj.rag.backend.infra.persistence`；知识库、文档和切片共同使用，不依赖其他业务包。Mapper 使用注解查询，不创建空 XML。Flyway 迁移继续保留在 `src/main/resources/db/migration`。

## 测试与新增代码

单元测试包跟随生产包，例如 `chunk/splitting`、`document/upload`、`model/client`、`search/service/impl`。跨业务 HTTP、数据库与存储场景归 `integration`，测试数据仍使用现有资源目录。

新增功能先选择归属业务，再按实际边界增加 Request、BO、VO、DO 或 Converter；不创建空目录，不复制公共能力，也不为目录示例引入中间件。结构迁移后的首次构建需要清理旧包遗留的编译类，避免 Spring 扫描到新旧两套组件。

注释说明事务范围、锁顺序、执行权、重试或资源生命周期等约束，不重复变量名和代码表面行为。事务模板回调的参数是事务状态，不是异常；忽略异常时应说明保留哪个结果、由哪个流程恢复。

从仓库根验证本服务及公共依赖：

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=C:/Users/lzj/Documents/ChatGPT/rag/.cache/maven/repository' -pl services/backend -am test
```

数据库、S3 和 Elasticsearch 集成测试使用 Testcontainers。Docker 不可用时按测试原有条件跳过；可选本地数据库测试只在显式配置专用测试数据库时运行。

本机需要保留已有构建产物时，可追加 `-Dbackend.build.directory=C:/Users/lzj/Documents/ChatGPT/rag/.cache/backend-structure-final`，将本服务的编译、测试报告和 JAR 写入独立目录。该参数不改变源码、配置或默认的 `target` 输出约定。
