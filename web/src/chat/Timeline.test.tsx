import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import Timeline from './Timeline';
import { externalSources } from './externalSources';
import { citationMarkdown } from './format';
import type { ChatBlock } from './types';

describe('tool presentation and source isolation', () => {
  it.each([['success', '成功'], ['empty', '无结果'], ['invalid_arguments', '参数无效'], ['denied', '已拒绝'], ['timeout', '超时'], ['unavailable', '暂不可用'], ['failed', '失败'], ['cancelled', '已取消']])('renders %s using its own status and server duration', (status, label) => {
    const block: ChatBlock = { blockId: 'block', kind: 'tool', status: 'done', toolCallId: 'call', name: 'read', displayName: '只读查询', toolStatus: status, durationMs: 23, sourceType: 'mcp', result: { evidence: [], content: { value: '受控结果' } } };
    const html = renderToStaticMarkup(<Timeline blocks={[block]} citations={[]} answer={text => text} source={() => {}} />);
    expect(html).toContain(label); expect(html).toContain('23ms'); expect(html).toContain('MCP 来源');
    expect(html).not.toContain('没有可展示的证据正文');
  });
  it('keeps MCP IDs in a separate source type and assigns one numbering table', () => {
    const a = 'M' + 'a'.repeat(32), b = 'M' + 'b'.repeat(32);
    const blocks: ChatBlock[] = [a, b].map((sourceId, index) => ({ blockId: `${index}`, kind: 'tool', status: 'done', toolCallId: `call${index}`, sourceType: 'mcp', result: { evidence: [], content: { sourceId, content: { value: index } } } }));
    const sources = externalSources(blocks);
    expect(citationMarkdown(`[${a}][${b}]`, [], sources)).toBe(`[1](#mcp-${a})[2](#mcp-${b})`);
    expect(externalSources([{ ...blocks[0], sourceType: 'knowledge' }])).toEqual([]);
  });
});
