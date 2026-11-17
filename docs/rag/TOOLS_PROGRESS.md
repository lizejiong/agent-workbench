# 统一只读工具交付进度

2026-11-17 已完成。本文件是原 2026-11-11 任务的恢复入口。验收时未提交、推送或部署；后续用户已授权提交和推送，具体版本以 Git 记录为准。详细交付见 [TOOLS_DELIVERY.md](TOOLS_DELIVERY.md)，机器记录见 [TOOLS_VALIDATION.json](TOOLS_VALIDATION.json)。

## 已核实的项目事实

- 初始仓库为 Agent Workbench，Spring Boot/JDBC/Flyway + React/Ant Design + FastAPI/LangGraph；不存在 Nest/Prisma/AI SDK/rag_ai/models。本次新增 Web Zod。
- 未找到适用 AGENTS.md。工作树开始时干净。
- Java 为业务历史、身份、知识 ACL、事务的唯一来源。Python 已有 ChatOpenAI、create_agent、受信任委托、预算和取消。Web 已有 SSE v2 内容块及 Java 历史。
- 不存在 GraphQueryTool/图谱服务；本次不虚构图谱查询。知识检索保留现有 Java 领域调用，新增 evidence_read 只读取重新鉴权后的本次运行证据。
- 当前 SQLite 检查点要求单 AI worker/state_dir；采用这一部署限制，运行和取消状态以 PostgreSQL 为准。

## 执行顺序

1. [完成] 读取边界/代码，锁定并核对官方 MCP 1.26.0，jsonschema 4.26。
2. [完成] Python tools 契约/执行器/服务端运行上下文/调用去重/知识检索及 evidence_read。
3. [完成] Java tools 包、Flyway V15、配置发现发布授权、运行绑定许可/审计/过期、双向服务 token + audience、聊天代理。
4. [完成] 显式 agent StateGraph；rag 保留原 create_agent 路径但工具通过统一执行器；非流式规划、流式最终回答。
5. [完成] SSE v3 data-tool 事件、Pydantic/Zod、通用工具卡、模式下拉框、Java 历史及摘要撤权检查。
6. [完成] fixture、真实 MCP/Java/PostgreSQL/浏览器闭环、全面回归、smoke、真实供应商冒烟及文档。

## 验证记录

| 最终检查 | 结果 |
|---|---|
| Python Agent 全测试 / 原 knowledge_tool 示例 | 55 passed / 4 passed |
| Ruff / 相关文件格式 | passed / 37 files |
| Web | 41 passed；TypeScript + Vite build passed |
| 根 Maven verify | 全部10模块 BUILD SUCCESS；各模块汇总328项，0失败/错误、2项条件跳过 |
| 配好环境后的定向 verify | backend 36项，0失败/错误/跳过；包含前述2项 |
| 最新 Java/Python + 真 MCP + HTTP 模型桩 | 双工具、连续 seq、历史、审计、脱敏、禁用隐藏通过 |
| 显式停止 / 真实断连 | run=CANCELLED、tool=cancelled；开始事件及时到达 |
| 已有供应商模型 | 1次真实 MCP 调用及流式回答，历史/审计/禁用隐藏通过 |
| 浏览器 | 并行卡片、服务端耗时、MCP引用、脱敏展开、重新读取时隐藏已禁用来源 |

关键修复：LangGraph 消息回调会令 ainvoke 自动流式，规划显式 stream=False；pytest 默认系统临时目录不可写，使用 .cache/tools-*-tests-* --basetemp。

资源收尾：
- 本任务 Java/Python/MCP/模型桩监督进程、两个 Vite 和临时 Windows PostgreSQL 已停止，相关端口核查为空。
- 专用容器 atlas-tools-test-20261116 已停止，保留数据便于重现；未停止原有知识库 PostgreSQL/MinIO。
- 仅使用独立 atlas_tools、atlas_tools_smoke、atlas_tools_real 测试库，未迁移原业务库，未修改既有模型配置。
- 临时数据与日志保留在 Git 忽略的 .cache/tools-*；output/tools 保存脱敏回执与截图。

生产部署、多 AI worker、用户 OAuth、业务写工具和广泛语义评测未纳入任务；协议通过不能替代真实模型质量指标。新增本地工具、接入 MCP、配置权限、查看失败及下线见 [TOOLS.md](TOOLS.md)。
