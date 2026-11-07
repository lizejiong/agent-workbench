import { useState } from 'react';
import { App, Button, Drawer, Popover, Tooltip } from 'antd';
import { CheckOutlined, CopyOutlined, DownOutlined, DownloadOutlined, FileTextOutlined, RightOutlined } from '@ant-design/icons';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Link } from 'react-router-dom';
import { useApi } from '../state';
import { Source } from '../components';
import { citationMarkdown, copyAnswer } from './format';
import type { AgentReply, ChatBlock, Citation } from './types';
import Timeline from './Timeline';
import SourceCards from './SourceCards';
import { groupSources, sourceExcerpt, sourceKey, sourceLocation } from './sourcePresentation';

function CitationMarker({ source, index, disabled, onSelect }: {
  source: Citation; index: number; disabled: boolean; onSelect: (source: Citation) => void;
}) {
  const [open, setOpen] = useState(false);
  const select = () => { setOpen(false); onSelect(source); };
  return <Popover trigger={['hover', 'focus']} placement="top" open={!disabled && open} onOpenChange={setOpen}
    content={<div className="chat-citation-preview">
      <span className="chat-citation-preview-meta">引用 {index + 1} · 版本 {source.generation}</span>
      <strong>{source.documentName}</strong><span className="chat-citation-preview-location">{sourceLocation(source)}</span>
      <p>{sourceExcerpt(source.content, 240) || '本条历史记录未保存证据正文。'}</p>
      <button type="button" onClick={select}>查看证据详情 <RightOutlined /></button>
    </div>}>
    <button type="button" className="chat-citation" aria-label={`查看来源 ${index + 1}：${source.documentName}`} onClick={select}>{index + 1}</button>
  </Popover>;
}

export default function Answer({ reply, blocks }: { reply: AgentReply; blocks?: ChatBlock[] }) {
  const api = useApi(); const { message } = App.useApp();
  const [sourceId, setSourceId] = useState<string>(); const [copied, setCopied] = useState(false); const [downloading, setDownloading] = useState(false);
  const citations = reply.citations;
  const citedGroups = groupSources(citations);
  const available = [...new Map([...citations, ...(blocks || []).flatMap(item => item.result?.evidence || [])].map(item => [item.evidenceId, item])).values()];
  const source = available.find(item => item.evidenceId === sourceId);
  const sourceGroup = source && groupSources(available).find(group => group.key === sourceKey(source));
  const citationIndex = source ? citations.findIndex(item => item.evidenceId === source.evidenceId) : -1;
  const selectSource = (value: Citation) => setSourceId(value.evidenceId);
  const copy = async () => {
    try { await navigator.clipboard.writeText(copyAnswer(reply.answer, citations)); setCopied(true); }
    catch { void message.info('浏览器未允许复制，请手动选择文字。'); }
  };
  const download = async () => {
    if (!source) return;
    setDownloading(true);
    try { await api.download(`/api/documents/${source.documentId}/file`, source.documentName); }
    catch (failure) { void message.error(failure instanceof Error ? failure.message : '原文件暂时无法下载。'); }
    finally { setDownloading(false); }
  };
  const statuses = new Set(reply.retrievals.map(item => item.status));
  const renderAnswer = (text: string) => (
    <div className="chat-markdown">
      <Markdown skipHtml remarkPlugins={[remarkGfm]} components={{
        a: ({ href, children }) => {
          const match = citations.find(item => href === `#source-${item.evidenceId}`);
          return match ? <CitationMarker source={match} index={citations.indexOf(match)} disabled={!!source} onSelect={selectSource} /> : <span>{children}</span>;
        },
        img: ({ alt }) => <span>{alt || '图片'}</span>,
        table: ({ children }) => <div className="chat-table-scroll"><table>{children}</table></div>,
      }}>{citationMarkdown(text, citations)}</Markdown>
    </div>
  );
  return <>
    {blocks?.length ? <Timeline blocks={blocks} citations={citations} answer={renderAnswer} source={selectSource} /> : renderAnswer(reply.answer)}
    {!!citations.length && <details className="chat-sources" aria-label="回答引用来源">
      <summary><FileTextOutlined /><span>引用来源</span><span className="chat-sources-count">{citedGroups.length} 份文档 · {citations.length} 处引用</span><DownOutlined className="chat-sources-chevron" /></summary>
      <SourceCards sources={citations} citations={citations} onSelect={selectSource} />
    </details>}
    <div className="chat-answer-meta">
      <Tooltip title={copied ? '已复制' : '复制回答'}><button className="chat-icon-button" type="button" aria-label="复制回答" onClick={() => void copy()}>{copied ? <CheckOutlined /> : <CopyOutlined />}</button></Tooltip>
      {statuses.has('UNAVAILABLE') ? <span className="chat-result-note">{statuses.has('READY') ? '部分检索暂不可用' : '检索暂不可用'}</span>
        : statuses.has('NEEDS_CLARIFICATION') ? <span className="chat-result-note">需要补充信息</span>
          : statuses.has('NO_EVIDENCE') && !citations.length ? <span className="chat-result-note">当前资料不足</span> : null}
      {reply.retrievals.some(item => item.degraded) && <span className="chat-result-note">部分检索降级</span>}
      {reply.retrievals.some(item => item.toolTruncated) && <span className="chat-result-note">资料已截取</span>}
    </div>
    <Drawer title="来源资料" open={!!source} onClose={() => setSourceId(undefined)} size={520} destroyOnHidden className="chat-source-drawer">
      {source && <div className="chat-source-detail"><span className="chat-source-file"><FileTextOutlined /></span><h2>{source.documentName}</h2>
        <div className="chat-source-snapshot-label">{citationIndex >= 0 ? `回答引用 [${citationIndex + 1}]` : '检索取得的证据'}<span>版本 {source.generation}</span></div>
        {!!sourceGroup && sourceGroup.evidence.length > 1 && <div className="chat-source-switcher" aria-label="同文档证据片段">
          <span>本轮取得的片段</span><div>{sourceGroup.evidence.map((item, index) => <button type="button" key={item.evidenceId}
            aria-pressed={item.evidenceId === source.evidenceId} title={sourceLocation(item)} onClick={() => selectSource(item)}>片段 {index + 1}</button>)}</div>
        </div>}
        <section key={source.evidenceId} data-evidence-id={source.evidenceId} aria-label="当前证据摘录">
          <Source metadata={source.sourceMetadata || {}} />
          {source.content ? <blockquote className="chat-source-excerpt">{source.content}</blockquote> : <p>本条历史记录未保存证据正文，可查看原文件。</p>}
        </section>
        {(source.truncated || source.toolTruncated) && <p className="chat-source-hint">本次回答使用了资料摘录，完整内容请查阅原文件。</p>}
        <p className="chat-source-hint">上方为本轮取得的证据快照；原文件可能已更新。</p>
        <Button block icon={<DownloadOutlined />} loading={downloading} onClick={() => void download()}>下载原文件</Button>
        <Link to={`/documents/${source.documentId}?tab=chunks`} onClick={() => setSourceId(undefined)}>在后台查看文档</Link>
      </div>}
    </Drawer>
  </>;
}
