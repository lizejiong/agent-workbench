# RAG 中文提示词

模板使用 UTF-8 `.txt`，按“职责、输入、规则、输出协议”组织。用户问题、历史和证据由 Java 作为独立 JSON 数据消息发送，不通过字符串替换嵌入系统模板。字段名与 JSON 契约保持一致。

修改行为时复制为新版本，例如 `query-understanding-v2.txt`，并通过 `kb.rag.prompts.query-version` 选择。模板的实际版本和 SHA-256 会进入追踪；Java 仍负责字段、数量、授权范围与预算校验。提示词不承担授权职责，检查通过也不保证语义正确。

默认加载本目录。需要部署方独立维护时设置 `kb.rag.prompts.directory` 为受控目录，放入同名版本文件，修改后重启服务。模板第一次加载后保持快照；缺失、空白、超出 32 KiB 或非法版本使加载失败，不偷偷使用另一版本。文件路径和版本仅由服务端配置决定，普通请求和模型不能指定。

| 用途 | 默认模板 | 服务端配置 |
|---|---|---|
| 查询改写、拆分和澄清 | query-understanding-v1.txt | kb.rag.prompts.query-version |
| 基于授权证据回答 | rag-answer-v1.txt | kb.rag.prompts.answer-version |
| 一次有界引用修复 | rag-citation-repair-v1.txt | kb.rag.prompts.repair-version |

问答和修复模板在启动时加载，错误编码也会拒绝。引用协议使用正文 `[E1]`，来源映射只由 Java 生成。先修改模板并选择新版本，再用固定问题复查行为；格式校验与模型的语义效果分别验收。
