import type { Citation } from './types';

export interface SourceGroup {
  key: string;
  documentName: string;
  generation: number;
  evidence: Citation[];
}

export const sourceKey = (source: Citation) => JSON.stringify([source.kbId, source.documentId, source.generation]);

/** 只合并同一知识库、文档及版本的展示；每个证据标识仍独立保留。 */
export function groupSources(sources: Citation[]): SourceGroup[] {
  const groups = new Map<string, SourceGroup>();
  for (const source of sources) {
    const key = sourceKey(source);
    let group = groups.get(key);
    if (!group) {
      group = { key, documentName: source.documentName, generation: source.generation, evidence: [] };
      groups.set(key, group);
    }
    if (!group.evidence.some(item => item.evidenceId === source.evidenceId)) group.evidence.push(source);
  }
  return [...groups.values()];
}

export function sourceExcerpt(content: string | undefined, limit = 180): string {
  const text = (content || '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? `${text.slice(0, limit)}…` : text;
}

/** 只读取已有契约中的定位字段，不把任意元数据当作标题或链接。 */
export function sourceLocation(source: Citation): string {
  const metadata = source.sourceMetadata || {};
  if (metadata.manual) return '人工维护的正文';
  const chapter = Array.isArray(metadata.chapterPath) ? metadata.chapterPath.filter(v => typeof v === 'string').join(' / ') : '';
  const blocks = Array.isArray(metadata.sourceBlocks) ? metadata.sourceBlocks : [];
  const position = [metadata, ...blocks].filter((value): value is Record<string, unknown> => !!value && typeof value === 'object')
    .map(value => {
      const text = (key: string) => typeof value[key] === 'string' || typeof value[key] === 'number' ? String(value[key]) : '';
      const range = (start: string, end: string, unit: string) => {
        const first = text(start), last = text(end);
        return first ? `${first}${last && last !== first ? `–${last}` : ''} ${unit}` : '';
      };
      return [text('pageNumber') ? `第 ${text('pageNumber')} 页` : range('pageStart', 'pageEnd', '页'),
        text('sheetName'), range('rowStart', 'rowEnd', '行'), range('lineStart', 'lineEnd', '行'),
        text('slideNumber') ? `第 ${text('slideNumber')} 张幻灯片` : ''].filter(Boolean).join(' · ');
    }).find(Boolean);
  return [chapter, position].filter(Boolean).join(' · ') || '原文摘录';
}
