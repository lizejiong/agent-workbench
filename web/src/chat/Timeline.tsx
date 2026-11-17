import type { ReactNode } from 'react';
import { CheckCircleOutlined, CloseCircleOutlined, DownOutlined, LoadingOutlined, PauseCircleOutlined, SearchOutlined } from '@ant-design/icons';
import SourceCards from './SourceCards';
import { groupSources } from './sourcePresentation';
import type { ChatBlock, Citation } from './types';

const labels: Record<ChatBlock['status'], string> = { pending: '待执行', running: '执行中', streaming: '生成中', done: '已完成', failed: '失败', interrupted: '已中断' };
const elapsed = (ms: number) => ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
function ToolCard({ item, citations, source }: { item: ChatBlock; citations: Citation[]; source: (value: Citation) => void }) {
  const evidence = item.result?.evidence || [];
  const groups = groupSources(evidence);
  const count = groups.reduce((total, group) => total + group.evidence.length, 0);
  const ms = item.durationMs;
  const statusLabels: Record<string, string> = { success: '成功', empty: '无结果', invalid_arguments: '参数无效', denied: '已拒绝', timeout: '超时', unavailable: '暂不可用', failed: '失败', cancelled: '已取消' };
  const label = statusLabels[item.toolStatus || ''] || labels[item.status];
  const notice = item.retrievalStatus === 'NO_EVIDENCE' ? '当前授权范围内没有相关证据'
    : item.retrievalStatus === 'NEEDS_CLARIFICATION' ? '问题需要补充信息'
      : item.retrievalStatus === 'UNAVAILABLE' ? '检索暂不可用，不代表资料不存在'
        : item.status === 'failed' ? '工具执行失败，已保留本轮内容'
          : item.status === 'interrupted' ? '执行已停止，已取得的内容仍保留' : undefined;
  return <article id={`tool-${item.toolCallId}`} className={`agent-tool is-${item.status}`} data-tool-call-id={item.toolCallId} aria-label={`${item.displayName || '工具'}：${label}`}>
    <details className="agent-tool-disclosure">
      <summary>
        <span className="agent-tool-heading"><span className="agent-tool-icon">{item.status === 'running' ? <LoadingOutlined spin /> : item.status === 'done' ? <CheckCircleOutlined /> : item.status === 'failed' ? <CloseCircleOutlined /> : item.status === 'interrupted' ? <PauseCircleOutlined /> : <SearchOutlined />}</span>
          <strong>{item.displayName || '工具'}</strong><span className="agent-tool-status">{label}{ms !== undefined && Number.isFinite(ms) ? ` · ${elapsed(ms)}` : ''}</span><DownOutlined className="agent-tool-chevron" /></span>
        {typeof item.arguments?.query === 'string' && <span className="agent-tool-query" title={item.arguments.query}>{item.arguments.query}</span>}
        {item.summary && <span className="agent-tool-summary">{item.summary}</span>}
        {!!count && <span className="agent-tool-summary">取得 {count} 条证据 · {groups.length} 份文档</span>}
      </summary>
      <div className="agent-tool-content">
        {item.sourceType === 'mcp' && <><p className="agent-tool-note">MCP 来源 · 按当前工具授权校验</p><pre className="agent-tool-result">{JSON.stringify(item.result?.content, null, 2)}</pre></>}
        {!!count ? <SourceCards sources={evidence} citations={citations} onSelect={source} />
          : item.sourceType === 'mcp' ? null : <p className="agent-tool-empty">{item.status === 'pending' || item.status === 'running' ? '工具执行结束后将在此展示结果。' : '没有可展示的证据正文。'}</p>}
        {(item.arguments || item.errorCode) && <details className="agent-tool-parameters"><summary>调用详情</summary>
          {item.arguments && <><h4>查询参数</h4><pre>{JSON.stringify(item.arguments, null, 2)}</pre></>}
          {item.errorCode && <p>状态码：{item.errorCode}</p>}
        </details>}
      </div>
    </details>
    {notice && <p className="agent-tool-notice" role={item.status === 'failed' ? 'status' : undefined}>{notice}</p>}
    {(item.degraded || item.toolTruncated) && <p className="agent-tool-note">{[item.degraded && '检索已降级', item.toolTruncated && '返回资料已截取'].filter(Boolean).join(' · ')}</p>}
  </article>;
}

export default function Timeline({ blocks, citations, answer, source }: { blocks: ChatBlock[]; citations: Citation[]; answer: (text: string) => ReactNode; source: (value: Citation) => void }) {
  const rows: Array<ChatBlock | ChatBlock[]> = [];
  for (const item of blocks) {
    const last = rows.at(-1);
    if (item.kind === 'tool') {
      if (Array.isArray(last) && item.batchId && last[0].batchId === item.batchId) last.push(item);
      else rows.push([item]);
    } else rows.push(item);
  }
  return <div className="agent-timeline">{rows.map(row => Array.isArray(row)
    ? <section className="agent-tool-batch" key={row[0].blockId} aria-label={row.length > 1 ? '同批工具调用' : '工具调用'}>
      {row.length > 1 && <div className="agent-batch-title">同批工具 · {row.length} 项</div>}
      {[...row].sort((a, b) => (a.callIndex || 0) - (b.callIndex || 0)).map(item => <ToolCard key={item.blockId} item={item} citations={citations} source={source} />)}</section>
    : row.kind === 'answer' ? <div key={row.blockId} data-block-id={row.blockId}>{answer(row.text || '')}</div>
      : <p key={row.blockId} className={`agent-timeline-${row.kind}`} role={row.kind === 'error' ? 'alert' : undefined}>{row.text}</p>)}</div>;
}
