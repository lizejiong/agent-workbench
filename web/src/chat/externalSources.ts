import type { ChatBlock } from './types';

export interface ExternalSource { sourceId: string; callId: string; displayName: string; content: unknown }
export function externalSources(blocks: ChatBlock[]): ExternalSource[] {
  const sources = new Map<string, ExternalSource>();
  for (const block of blocks) {
    const result = block.result?.content;
    if (block.sourceType !== 'mcp' || !block.toolCallId || !result || typeof result !== 'object') continue;
    const value = result as { sourceId?: unknown; content?: unknown };
    if (typeof value.sourceId !== 'string' || !/^M[a-f0-9]{32}$/.test(value.sourceId)) continue;
    sources.set(value.sourceId, { sourceId: value.sourceId, callId: block.toolCallId, displayName: block.displayName || 'MCP 来源', content: value.content });
  }
  return [...sources.values()];
}
