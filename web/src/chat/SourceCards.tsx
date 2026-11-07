import { FileTextOutlined, RightOutlined } from '@ant-design/icons';
import { groupSources, sourceExcerpt, sourceLocation } from './sourcePresentation';
import type { Citation } from './types';

export default function SourceCards({ sources, citations, onSelect }: {
  sources: Citation[]; citations: Citation[]; onSelect: (source: Citation) => void;
}) {
  const indexes = new Map(citations.map((source, index) => [source.evidenceId, index + 1]));
  return <div className="chat-source-cards">{groupSources(sources).map(group => <section className="chat-source-card" key={group.key}>
    <div className="chat-source-card-heading"><FileTextOutlined /><strong title={group.documentName}>{group.documentName}</strong>
      <span>版本 {group.generation}</span><small>{group.evidence.length} 个片段</small></div>
    {group.evidence.map((source, index) => <button type="button" className="chat-source-row" key={source.evidenceId}
      aria-label={`查看${indexes.has(source.evidenceId) ? `引用 ${indexes.get(source.evidenceId)}` : `检索片段 ${index + 1}`}：${source.documentName}，${sourceLocation(source)}`}
      onClick={() => onSelect(source)}>
      <span className="chat-source-number">{indexes.get(source.evidenceId) || <FileTextOutlined />}</span>
      <span className="chat-source-row-copy"><span>{sourceLocation(source)}</span><small>{sourceExcerpt(source.content) || '查看来源详情'}</small></span>
      <RightOutlined className="chat-source-row-arrow" />
    </button>)}
  </section>)}</div>;
}
