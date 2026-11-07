# 聊天工具与引用展示

2026-11-07。仅调整现有 Web 展示层，继续使用 React Context、Ant Design、SSE v2 和 Java 授权证据快照。没有修改检索算法、Python 主循环、API 字段或数据库。

## 设计参考

- [Open WebUI 工具](https://github.com/open-webui/open-webui/blob/main/src/lib/components/common/ToolCallDisplay.svelte)与[引用](https://github.com/open-webui/open-webui/blob/main/src/lib/components/chat/Messages/Citations.svelte)：折叠状态行、按来源聚合、逐层查看正文。
- [Dify 工具](https://github.com/langgenius/dify/blob/main/web/app/components/base/chat/chat/answer/tool-detail.tsx)与[引用](https://github.com/langgenius/dify/blob/main/web/app/components/base/chat/chat/citation/index.tsx)：聊天过程保持紧凑，文档下保留多个证据片段。
- [assistant-ui 引用](https://github.com/assistant-ui/assistant-ui/blob/main/packages/ui/src/components/react/assistant-ui/elements/inline-citation.tsx)：编号触发包含标题、位置和摘录的预览。
- [AI Elements 引用示例](https://elements.ai-sdk.dev/components/inline-citation)：来源预览与多个来源的切换。只借鉴交互，不引入其运行时，也不采用示例中由模型自行生成来源的做法。

## 组件与规则

- `Timeline` 保留既有 blockId、toolCallId、batchId/callIndex 与出现顺序。工具默认显示名称、执行状态、耗时、受控查询及取得的证据/文档数量；点击状态行展开 `SourceCards`，再展开调用详情查看已有脱敏参数与状态码。无证据、依赖失败、中断、通道降级和截断提示保持可见。
- `sourcePresentation` 按 kbId/documentId/generation 聚合。同名文档、不同知识库和不同版本分别展示；只对同一 evidenceId 去重，不能因相同正文或相同快照哈希丢弃其他检索调用的标识。
- `Answer` 的正文编号沿用 Java/Python 已返回的真实引用映射。桌面悬浮/键盘聚焦可预览，点击打开现有来源抽屉；手机端可直接点击。未知引用不变成可点击来源，代码块内编号不转换。
- 答案底部只展示 `reply.citations`；工具展开区展示本工具实际返回的证据。来源列表不把取得但未引用的资料算作回答引用。工具与引用列表共用 `SourceCards`。
- 抽屉按 evidenceId 直接选择目标片段，可切换本轮同文档同版本的其他片段。标题、定位、证据快照优先展示，不默认展示技术标识或分数。仅从契约允许的定位字段提取章节、页码或行号，缺少位置时显示“原文摘录”。人工维护内容不伪装成原文件中的页码。
- 下载继续走现有受授权的同源 API；证据快照和当前原文件分别标注。既有撤权后 `sourcesRestricted` 清除正文/块/引用及重挂载逻辑保留；抽屉所选标识不再存在于当前证据集合时也不继续显示旧对象。
- 只有界面展开和选择状态，无新的客户端证据缓存、隐式重提、后台工具执行或审批按钮。

## 验证

仅进行 Web 定向检查；Java/Python 协议与权限实现未变化，不重跑后端和模型测试。具体执行结果见 [PROGRESS.md](PROGRESS.md)。页面验证复用已有“检索时间线演示”的持久化记录，不生成新回答，也不重复执行其中的工具。
