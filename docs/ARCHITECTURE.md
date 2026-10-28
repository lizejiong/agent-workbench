# Agent Workbench 架构边界

确认日期：2026-10-28。用户确定的后续架构方向；后续设计和实现以本职责划分为依据。

## 能力归属

| 能力 | 归属 | 原因 |
|---|---|---|
| ReAct 决策、循环、动态分支 | Python / LangGraph | 属于 Agent 执行流程 |
| 工具选择、参数生成、结果反馈 | Python | 与模型决策紧密结合 |
| Graph 检查点、暂停恢复、运行取消 | Python | 与图执行状态保持一致 |
| 会话内上下文裁剪、摘要 | Python | 直接影响下一次模型请求 |
| 主 Agent 的模型调用 | Python | 直接处理工具调用与流式事件 |
| 知识库、文档、Chunk 管理 | Java | 完整的知识业务领域 |
| 文档解析、分块、Embedding、索引写入 | Java | 作为独立入库服务运行 |
| 向量、关键词检索、融合、Rerank | Java | 形成独立、可复用的检索服务 |
| 现有 infra-ai 能力 | Java 保留 | 继续服务 Java 的入库、检索和其他模型任务 |
| MCP 业务工具服务端 | Java | 承载业务规则、权限和事务 |
| MCP 客户端与工具装配 | Python | 服务 Agent 的运行与调用 |
| 用户、权限、配置、审计 | Java | 属于业务平台能力 |

## 协作规则

Python 持有 Agent 的决策循环、图状态和下一次模型请求所需的上下文。主 Agent 由 Python 发起模型请求，并处理工具调用及流式事件；Java 的模型基础设施继续用于 Java 自身的模型任务。

Java 持有知识业务、入库任务和检索链路。Python Agent 调用 Java 的检索服务获得结构化证据，结合自身上下文继续决策。入库任务的恢复与取消归 Java 入库系统，Graph 的检查点、恢复与取消归 Python 图执行系统。

业务工具的服务端放在 Java，执行权限检查、业务规则和事务；Python 通过 MCP 客户端发现、装配和调用工具，并把结果反馈给 Agent。权限与数据归属在 Java 服务端按现有规则校验。

用户、权限、配置和审计按实际需求扩展，沿用当前简单账号与数据归属机制；用户此前要求避免复杂用户管理。具体跨服务协议、Graph 存储和部署参数在对应实现阶段确定。

## 与当前仓库的对应

| 路径 | 当前情况与后续职责 |
|---|---|
| services/agent-service/ | 当前仅有占位文件；后续 Python / LangGraph Agent 运行时、上下文、主模型调用与 MCP 客户端 |
| services/backend/ | 已实现 Java 知识库、入库、检索及管理能力；后续业务平台和 MCP 业务工具服务端按需接入 |
| services/ai-service/ | 当前 Java 模型服务；承接上述保留的 infra-ai 能力，继续服务 Java 模型任务 |
| packages/java-starters/ | 已有 Java 公共组件，业务服务按需复用 |
| packages/python-common/ | 已有 Python 公共包，Agent 服务按需复用 |
| web/ | 现有 React / Ant Design / Vite 管理台，后续按实现范围增加 Agent 入口 |

当前 Agent 运行时、Graph 检查点和 MCP 工具装配尚未实现。本次仅记录边界，现有代码、接口、数据库和部署方式保持现状。知识库已实现能力与验收记录见 [方案](knowledge-base/PLAN.md) 和 [进度](knowledge-base/PROGRESS.md)。
