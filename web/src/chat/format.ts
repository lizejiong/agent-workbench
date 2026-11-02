import type { Citation } from './types';

/** 只有服务端实际返回的引用才转换为可点击来源；正文中的其他编号保持普通文字。 */
export function citationMarkdown(answer: string, citations: Citation[]): string {
  const indexes = new Map(citations.map((source, index) => [source.evidenceId, index + 1]));
  return answer.replace(/(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)|\[(K[^\]\r\n]*)\]/g, (original, code: string | undefined, id: string) => {
    if (code) return code;
    const index = indexes.get(id);
    return index && /^K[a-f0-9]{32}_E[1-9][0-9]*$/.test(id) ? `[${index}](#source-${id})` : original;
  });
}
export function copyAnswer(answer: string, citations: Citation[]): string {
  let text = answer;
  citations.forEach((source, index) => { text = text.split(`[${source.evidenceId}]`).join(`[${index + 1}]`); });
  return text + (citations.length ? '\n\n来源：\n' + citations.map((source, index) => `[${index + 1}] ${source.documentName}`).join('\n') : '');
}
