import { useState } from 'react';
import { App, Button, Drawer, Tooltip } from 'antd';
import { CheckOutlined, CopyOutlined, DownloadOutlined, FileTextOutlined } from '@ant-design/icons';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Link } from 'react-router-dom';
import { useApi } from '../state';
import { Source } from '../components';
import { citationMarkdown, copyAnswer } from './format';
import type { AgentReply, ChatBlock, Citation } from './types';
import Timeline from './Timeline';

export default function Answer({ reply, blocks }: { reply: AgentReply; blocks?: ChatBlock[] }) {
  const api = useApi(); const { message } = App.useApp();
  const [source, setSource] = useState<Citation>(); const [copied, setCopied] = useState(false); const [downloading, setDownloading] = useState(false);
  const citations = reply.citations;
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
          return match ? <button type="button" className="chat-citation" aria-label={`查看来源 ${citations.indexOf(match) + 1}：${match.documentName}`} onClick={() => setSource(match)}>{children}</button> : <span>{children}</span>;
        },
        img: ({ alt }) => <span>{alt || '图片'}</span>,
        table: ({ children }) => <div className="chat-table-scroll"><table>{children}</table></div>,
      }}>{citationMarkdown(text, citations)}</Markdown>
    </div>
  );
  return <>
    {blocks?.length ? <Timeline blocks={blocks} answer={renderAnswer} source={setSource} /> : renderAnswer(reply.answer)}
    {!!citations.length && <div className="chat-sources" aria-label="回答来源">{citations.map((item, index) => <button key={item.evidenceId} type="button" onClick={() => setSource(item)} title={item.documentName}><FileTextOutlined /><span>{index + 1}. {item.documentName}</span></button>)}</div>}
    <div className="chat-answer-meta">
      <Tooltip title={copied ? '已复制' : '复制回答'}><button className="chat-icon-button" type="button" aria-label="复制回答" onClick={() => void copy()}>{copied ? <CheckOutlined /> : <CopyOutlined />}</button></Tooltip>
      {statuses.has('UNAVAILABLE') ? <span className="chat-result-note">{statuses.has('READY') ? '部分检索暂不可用' : '检索暂不可用'}</span>
        : statuses.has('NEEDS_CLARIFICATION') ? <span className="chat-result-note">需要补充信息</span>
          : statuses.has('NO_EVIDENCE') && !citations.length ? <span className="chat-result-note">当前资料不足</span> : null}
      {reply.retrievals.some(item => item.degraded) && <span className="chat-result-note">部分检索降级</span>}
      {reply.retrievals.some(item => item.toolTruncated) && <span className="chat-result-note">资料已截取</span>}
    </div>
    <Drawer title="来源资料" open={!!source} onClose={() => setSource(undefined)} size={420} className="chat-source-drawer">
      {source && <div className="chat-source-detail"><span className="chat-source-file"><FileTextOutlined /></span><h2>{source.documentName}</h2>
        <Source metadata={source.sourceMetadata || {}} />
        {source.content && <blockquote className="chat-source-excerpt">{source.content}</blockquote>}
        {(source.truncated || source.toolTruncated) && <p className="chat-source-hint">本次回答使用了资料摘录，完整内容请查阅原文件。</p>}
        <Button block icon={<DownloadOutlined />} loading={downloading} onClick={() => void download()}>下载原文件</Button>
        <Link to={`/documents/${source.documentId}?tab=chunks`} onClick={() => setSource(undefined)}>在后台查看文档</Link>
      </div>}
    </Drawer>
  </>;
}
