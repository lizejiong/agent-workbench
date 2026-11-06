import { describe, expect, it } from 'vitest';
import { reduceAgentEvent } from './stream';
import type { ChatTurn } from './types';

function setup() {
  let turn: ChatTurn = { id: 'request', question: '测试', status: 'pending', blocks: [] };
  let seq = 0;
  const event = (name: string, data: Record<string, unknown>) => {
    turn = reduceAgentEvent(turn, name, { protocolVersion: 2, runId: 'run', messageId: 'assistant', seq: ++seq, ...data }, 'conversation');
    return turn;
  };
  event('meta', { assistantMessageId: 'assistant' });
  return { event, current: () => turn };
}
describe('ordered Agent stream', () => {
  it('keeps one card per call and preserves text around reverse tool completions', () => {
    const { event, current } = setup();
    event('message', { type: 'answer', blockId: 'a', delta: '我先' });
    event('message', { type: 'answer', blockId: 'a', delta: '查询。' });
    const a = { kind: 'tool', blockId: 'tool-a', toolCallId: 'call-a', status: 'pending' };
    const b = { ...a, blockId: 'tool-b', toolCallId: 'call-b' };
    event('block', a); event('block', b);
    event('block', { ...b, status: 'done' }); event('block', { ...a, status: 'done' });
    event('message', { type: 'answer', blockId: 'z', delta: '查询结果。' });
    expect(current().blocks?.map(b => b.blockId)).toEqual(['a', 'tool-a', 'tool-b', 'z']);
    expect(current().reply?.answer).toBe('我先查询。\n\n查询结果。');
    const duplicate = reduceAgentEvent(current(), 'message', { protocolVersion: 2, runId: 'run', messageId: 'assistant', seq: current().eventSeq, type: 'answer', blockId: 'z', delta: '重复' }, 'conversation');
    expect(duplicate).toBe(current());
  });
  it('rejects missing frames and never treats an unpersisted finish as completion', () => {
    const { event } = setup();
    expect(() => event('message', { type: 'answer', blockId: 'x', delta: '片段', seq: 4 })).toThrow('不连续');
    const connected = setup();
    connected.event('finish', { status: 'COMPLETED', persisted: false });
    expect(connected.current().status).toBe('pending');
    expect(connected.current().runStatus).toBe('UNKNOWN');
  });
  it('hides derived text and tools on revoked evidence and ignores late content', () => {
    const { event, current } = setup();
    event('message', { type: 'answer', blockId: 'a', delta: '需要隐藏的旧内容' });
    event('finish', { status: 'FAILED', persisted: true, sourcesRestricted: true, blocks: [] });
    expect(current().reply?.answer).not.toContain('需要隐藏的旧内容');
    expect(current().blocks).toEqual([]);
    event('block', { blockId: 'late', kind: 'tool', toolCallId: 'call', status: 'done' });
    expect(current().blocks).toEqual([]);
  });
});
