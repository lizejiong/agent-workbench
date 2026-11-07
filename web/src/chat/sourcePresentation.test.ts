import { describe, expect, it } from 'vitest';
import { groupSources, sourceExcerpt, sourceLocation } from './sourcePresentation';
import type { Citation } from './types';

const evidence = (evidenceId: string, extra: Partial<Citation> = {}) => ({
  evidenceId, kbId: 'kb-a', documentId: 'doc-a', generation: 1, documentName: '制度',
  chunkId: evidenceId, snapshotHash: 'snapshot', sourceMetadata: {}, ...extra,
}) as Citation;

describe('source presentation boundaries', () => {
  it('groups a document version while keeping separately retrieved evidence identifiers addressable', () => {
    const first = evidence('Kfirst_E1'), second = evidence('Ksecond_E1');
    const groups = groupSources([first, second, first]);
    expect(groups).toHaveLength(1);
    expect(groups[0].evidence).toEqual([first, second]);
  });
  it('does not merge same-name documents, different knowledge bases or different versions', () => {
    const groups = groupSources([evidence('one'), evidence('two', { documentId: 'doc-b' }),
      evidence('three', { kbId: 'kb-b' }), evidence('four', { generation: 2 })]);
    expect(groups).toHaveLength(4);
    expect(new Set(groups.map(group => group.key)).size).toBe(4);
  });
  it('uses actual chapter and block positions, respects manual edits and tolerates old metadata', () => {
    expect(sourceLocation(evidence('one', { sourceMetadata: { chapterPath: ['差旅制度', '报销材料'], sourceBlocks: [null, { pageNumber: 3 }], internalUrl: 'private' } }))).toBe('差旅制度 / 报销材料 · 第 3 页');
    expect(sourceLocation(evidence('two', { sourceMetadata: { manual: true, pageNumber: 9 } }))).toBe('人工维护的正文');
    expect(sourceLocation(evidence('old', { sourceMetadata: null }))).toBe('原文摘录');
    expect(sourceExcerpt('  报销\n\t材料  ', 3)).toBe('报销 …');
  });
});
