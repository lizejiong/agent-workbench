import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { toolEvent } from './toolContract';
import { reduceAgentEvent } from './stream';
import type { ChatTurn } from './types';

const fixtures = JSON.parse(readFileSync(new URL('../../../packages/contracts/tools-v3.fixtures.json', import.meta.url), 'utf8')) as Array<{ event: string; data: Record<string, unknown> }>;
describe('Python → Web data-tool contract', () => {
  it('accepts every Python lifecycle status and preserves ordered identities', () => {
    let turn: ChatTurn = { id: 'turn', question: '问题', status: 'pending', runId: 'run', assistantMessageId: 'assistant', blocks: [] };
    fixtures.forEach(({ event, data }, index) => {
      expect(toolEvent.safeParse(data).success).toBe(true);
      turn = reduceAgentEvent(turn, event, { ...data, protocolVersion: 3, seq: index + 1, runId: 'run', messageId: 'assistant' }, 'conversation');
    });
    expect(turn.blocks).toHaveLength(9);
    expect(turn.blocks?.map(block => block.toolStatus)).toEqual(['running', 'success', 'empty', 'invalid_arguments', 'denied', 'timeout', 'unavailable', 'failed', 'cancelled']);
  });
  it('rejects identity mismatch and server duration outside its bound', () => {
    const turn: ChatTurn = { id: 'turn', question: '问题', status: 'pending', runId: 'run', assistantMessageId: 'assistant' };
    const data = fixtures[0].data;
    expect(() => reduceAgentEvent(turn, fixtures[0].event, { ...data, callId: 'different' }, 'conversation')).toThrow();
    expect(toolEvent.safeParse({ ...data, durationMs: 120001 }).success).toBe(false);
  });
  it('hides late tool results after a restricted terminal snapshot', () => {
    const turn: ChatTurn = { id: 'turn', question: '问题', status: 'completed', runId: 'run', sourcesRestricted: true, blocks: [] };
    const next = reduceAgentEvent(turn, fixtures[0].event, fixtures[0].data, 'conversation');
    expect(next.blocks).toEqual([]);
  });
});
