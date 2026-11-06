import type { AgentReply, ChatBlock, ChatTurn, ToolEvent } from './types';

const ended = new Set(['done', 'failed', 'interrupted']);
function block(value: unknown): ChatBlock {
  if (!value || typeof value !== 'object') throw new Error('内容块格式无效，请重新加载历史。');
  const item = value as ChatBlock;
  if (typeof item.blockId !== 'string' || !['answer', 'tool', 'hint', 'error'].includes(item.kind)
    || !['streaming', 'pending', 'running', 'done', 'failed', 'interrupted'].includes(item.status)
    || (item.kind === 'tool' ? typeof item.toolCallId !== 'string' : typeof item.text !== 'string')) throw new Error('内容块格式无效，请重新加载历史。');
  return item;
}
function put(blocks: ChatBlock[], next: ChatBlock): ChatBlock[] {
  const index = blocks.findIndex(b => b.blockId === next.blockId);
  if (index < 0) {
    if (next.toolCallId && blocks.some(b => b.toolCallId === next.toolCallId)) throw new Error('工具调用标识冲突，请重新加载历史。');
    return [...blocks.map(b => b.kind === 'answer' && b.status === 'streaming' ? { ...b, status: 'done' as const } : b), next];
  }
  const previous = blocks[index];
  if (previous.kind !== next.kind || previous.toolCallId !== next.toolCallId) throw new Error('内容块身份变化，请重新加载历史。');
  if (ended.has(previous.status) && !ended.has(next.status)) return blocks;
  return blocks.map((b, i) => i === index ? next : b);
}
function finish(turn: ChatTurn, data: Record<string, unknown>): ChatTurn {
  const persisted = data.persisted !== false;
  const status = String(data.status);
  const restricted = turn.sourcesRestricted || data.sourcesRestricted === true;
  const blocks = restricted ? [] : Array.isArray(data.blocks) ? data.blocks.map(block) : (turn.blocks || []).map(b =>
    !ended.has(b.status) ? { ...b, status: (status === 'COMPLETED' && b.kind === 'answer' ? 'done' : 'interrupted') as ChatBlock['status'] } : b);
  const reply = typeof data.answer === 'string' ? data as unknown as AgentReply : turn.reply;
  return { ...turn, blocks, sourcesRestricted: restricted, persisted, runStatus: persisted ? status : 'UNKNOWN',
    status: !persisted || status === 'RUNNING' ? 'pending' : status === 'COMPLETED' ? 'completed' : status === 'CANCELLED' ? 'cancelled' : 'failed',
    reply: restricted && reply ? { ...reply, answer: '相关知识已变化或不可访问，本条回答已隐藏。请重新提问。', citations: [] } : reply };
}

/** 序号去重；缺帧停止消费并读取 Java 快照，不重提 Agent 请求。 */
export function reduceAgentEvent(turn: ChatTurn, name: string, data: Record<string, unknown>, conversationId: string): ChatTurn {
  let next = turn;
  if (data.protocolVersion === 2) {
    if (turn.runId && data.runId !== turn.runId) return turn;
    if (!Number.isSafeInteger(data.seq) || Number(data.seq) < 1 || typeof data.runId !== 'string') throw new Error('事件标识无效，请重新加载历史。');
    const seq = Number(data.seq), previous = turn.eventSeq || 0;
    if (seq <= previous) return turn;
    if (previous && seq !== previous + 1) throw new Error('事件流不连续，请查询已保存的消息。');
    if (name !== 'meta' && (!turn.runId || turn.assistantMessageId !== data.messageId)) throw new Error('事件不属于当前消息。');
    next = { ...turn, eventSeq: seq };
  }
  if (name === 'meta') return { ...next, runId: String(data.runId), assistantMessageId: String(data.assistantMessageId || data.messageId) };
  if (next.sourcesRestricted && ['message', 'delta', 'block', 'tool', 'hint'].includes(name)) return next;
  if (name === 'message' || name === 'delta') {
    if (turn.sourcesRestricted) return next;
    if (name === 'message' && data.type !== 'answer') return next;
    const text = String(name === 'delta' ? data.text || '' : data.delta || '');
    if (!text) return next;
    const blocks = next.blocks || [];
    const id = typeof data.blockId === 'string' ? data.blockId : blocks.at(-1)?.kind === 'answer' ? blocks.at(-1)!.blockId : `legacy-${crypto.randomUUID()}`;
    const old = blocks.find(b => b.blockId === id);
    if (old && (old.kind !== 'answer' || blocks.at(-1)?.blockId !== id)) throw new Error('文本分段顺序无效。');
    const updated = put(blocks, { blockId: id, kind: 'answer', status: 'streaming', text: (old?.text || '') + text });
    const answer = updated.filter(b => b.kind === 'answer').map(b => b.text).join('\n\n');
    return { ...next, blocks: updated, reply: { sessionId: conversationId, runId: next.runId || '', traceId: String(data.traceId || ''),
      status: 'RUNNING', answer, citations: [], retrievals: [], model: '', promptVersion: '', promptHash: '' } };
  }
  if (name === 'block') return { ...next, blocks: put(next.blocks || [], block(data)) };
  if (name === 'tool') {
    const tool = data as unknown as ToolEvent;
    const item: ChatBlock = { blockId: `legacy-tool-${tool.toolCallId}`, kind: 'tool', toolCallId: tool.toolCallId, name: tool.name,
      status: tool.status === 'RUNNING' ? 'running' : tool.status === 'UNAVAILABLE' ? 'failed' : 'done', retrievalStatus: tool.status as ChatBlock['retrievalStatus'] };
    return { ...next, blocks: put(next.blocks || [], item), tools: [...(next.tools || []).filter(t => t.toolCallId !== tool.toolCallId), tool] };
  }
  if (name === 'error') return { ...next, persisted: data.persisted !== false, error: new Error('本轮未完成，请查询会话中的实际运行和保存状态。') };
  if (name === 'hint') return { ...next, blocks: put(next.blocks || [], block({ ...data, kind: 'hint', status: 'done' })) };
  if (name === 'finish' || name === 'cancel' || name === 'done') return finish(next, data);
  return next;
}
