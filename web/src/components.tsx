import { App, Alert, Button, Card, Descriptions, Empty, Form, InputNumber, Select, Space, Spin, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { ApiError, type ApiClient } from './api';
import type { DocumentRecord, IngestionSpec, Job } from './types';

export const activeJob = (status: string) => status === 'PENDING' || status === 'RUNNING';
export const activeRefresh = (status: string) => ['PENDING', 'RUNNING', 'WAITING_INGESTION'].includes(status);
export const date = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
export const bytes = (value: number) => value < 1024 ? `${value} B` : value < 1024 ** 2 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 ** 2).toFixed(1)} MB`;
export function Status({ value }: { value?: string | null }) {
  const labels: Record<string, string> = { PENDING: '待处理', RUNNING: '执行中', PROCESSING: '入库中', READY: '可用', SUCCEEDED: '成功', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消', SKIPPED: '已跳过', WAITING_INGESTION: '等待入库' };
  const color = ['SUCCEEDED', 'SUCCESS', 'READY'].includes(value || '') ? 'success' : value === 'FAILED' ? 'error' : ['RUNNING', 'PROCESSING', 'WAITING_INGESTION'].includes(value || '') ? 'processing' : 'default';
  return <Tag color={color}>{labels[value || ''] || value || '未执行'}</Tag>;
}
export function ErrorPanel({ error, retry }: { error?: Error; retry?: () => void }) {
  if (!error) return null;
  return <Alert type="error" showIcon title={error.message} description={error instanceof ApiError ? <Space wrap><span>错误码：{error.code}</span>{error.requestId && <Typography.Text copyable>请求标识：{error.requestId}</Typography.Text>}</Space> : undefined}
    action={retry && <Button size="small" onClick={retry}>重新加载</Button>} />;
}
export function Loading({ loading, error, children, retry }: { loading: boolean; error?: Error; children: ReactNode; retry?: () => void }) {
  return <><ErrorPanel error={error} retry={retry} />{loading ? <div className="loading"><Spin description="正在加载" /></div> : children}</>;
}
export function PageTitle({ eyebrow, title, description, actions }: { eyebrow: string; title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return <div className="page-title"><div><div className="eyebrow">{eyebrow}</div><Typography.Title level={2}>{title}</Typography.Title>{description && <div className="page-description">{description}</div>}</div><Space wrap>{actions}</Space></div>;
}
export function RefreshButton({ onClick }: { onClick: () => void }) { return <Button icon={<ReloadOutlined />} onClick={onClick}>刷新</Button>; }
export function Nothing({ text = '暂无数据' }: { text?: string }) { return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />; }
export const defaultSpec: IngestionSpec = { schemaVersion: 1, parser: 'AUTO', parseProfile: 'fast', splitter: { kind: 'STRUCTURAL', maxChars: 1024, overlapChars: 128, tableRows: 50, toleranceChars: 512 } };
export function IngestionFields() {
  const kind = Form.useWatch(['ingestionSpec', 'splitter', 'kind']);
  return <>
    <div className="form-grid">
      <Form.Item label="解析档位" name={['ingestionSpec', 'parseProfile']} tooltip="fast 使用基础结构解析；fidelity 的 PDF 需要已配置 MinerU，图片需要 VLM。外部服务失败不会自动降档。">
        <Select options={[{ value: 'fast', label: '快速解析 · fast' }, { value: 'fidelity', label: '高保真 · fidelity' }]} />
      </Form.Item>
      <Form.Item label="分块方式" name={['ingestionSpec', 'splitter', 'kind']}><Select options={[{ value: 'STRUCTURAL', label: '按结构分块' }, { value: 'WHOLE_DOCUMENT', label: '整篇一个 Chunk' }]} /></Form.Item>
      <Form.Item label="目标长度（字符）" name={['ingestionSpec', 'splitter', 'maxChars']} rules={[{ required: true }]}><InputNumber min={128} max={4000} disabled={kind === 'WHOLE_DOCUMENT'} /></Form.Item>
      <Form.Item label="重叠（字符）" name={['ingestionSpec', 'splitter', 'overlapChars']} dependencies={[[ 'ingestionSpec', 'splitter', 'maxChars' ]]}
        rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value: number) => value < getFieldValue(['ingestionSpec', 'splitter', 'maxChars']) ? Promise.resolve() : Promise.reject(new Error('重叠必须小于目标长度')) })]}>
        <InputNumber min={0} max={1000} disabled={kind === 'WHOLE_DOCUMENT'} />
      </Form.Item>
      <Form.Item label="每块表格行数" name={['ingestionSpec', 'splitter', 'tableRows']} rules={[{ required: true }]}><InputNumber min={1} max={1000} disabled={kind === 'WHOLE_DOCUMENT'} /></Form.Item>
      <Form.Item label="结构容忍上限（字符）" name={['ingestionSpec', 'splitter', 'toleranceChars']} tooltip="保留完整代码行或表格行时允许超出目标长度，仍受服务端硬上限约束。" rules={[{ required: true }]}><InputNumber min={0} max={4096} disabled={kind === 'WHOLE_DOCUMENT'} /></Form.Item>
    </div>
    <Typography.Text type="secondary">修改配置后需显式提交入库；新版本成功发布前，旧版本仍可检索。</Typography.Text>
  </>;
}
export function Source({ metadata }: { metadata: Record<string, unknown> }) {
  const chapter = Array.isArray(metadata.chapterPath) ? metadata.chapterPath.filter(v => typeof v === 'string').join(' / ') : '';
  const fields: [string, string][] = [['pageNumber', '页码'], ['pageStart', '起始页'], ['pageEnd', '结束页'], ['slideNumber', '幻灯片'], ['sheetName', '工作表'], ['rowStart', '起始行'], ['rowEnd', '结束行'], ['lineStart', '文本起始行'], ['lineEnd', '文本结束行']];
  const positions: string[] = [];
  for (const [key, label] of fields) if (typeof metadata[key] === 'number' || typeof metadata[key] === 'string') positions.push(`${label} ${metadata[key]}`);
  const blocks = Array.isArray(metadata.sourceBlocks) ? metadata.sourceBlocks as Record<string, unknown>[] : [];
  for (const block of blocks.slice(0, 8)) {
    const values = fields.filter(([key]) => typeof block[key] === 'number' || typeof block[key] === 'string').map(([key, label]) => `${label} ${block[key]}`);
    if (values.length) positions.push(values.join(' · '));
  }
  return <div className="source"><span>{metadata.manual ? '人工维护 · 原文件不包含此正文' : chapter || '原文档'}</span>{[...new Set(positions)].map(p => <span key={p}>{p}</span>)}</div>;
}
export function useOperation() {
  const { message, modal } = App.useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const run = async <T,>(work: () => Promise<T>, success?: string): Promise<T | undefined> => {
    setBusy(true); setError(undefined);
    try { const result = await work(); if (success && result !== null) void message.success(success); return result; }
    catch (failure) { setError(failure instanceof Error ? failure : new Error('操作失败')); return undefined; }
    finally { setBusy(false); }
  };
  const confirm = (title: string, content: ReactNode, danger = false): Promise<boolean> => new Promise(resolve => {
    modal.confirm({ title, content, okText: danger ? '确认操作' : '确认', cancelText: '取消', okButtonProps: { danger }, onOk: () => { resolve(true); }, onCancel: () => { resolve(false); } });
  });
  return { run, busy, error, confirm };
}
/** Confirmation uses a freshly fetched version and never silently replays a conflicting destructive request. */
export async function submitJob(api: ApiClient, doc: DocumentRecord, confirm: (title: string, content: ReactNode, danger?: boolean) => Promise<boolean>, retryOf?: string) {
  const current = await api.get<DocumentRecord>(`/api/documents/${doc.id}`);
  const request: { requestId: string; confirmOverwriteEdited?: boolean; expectedDocumentVersion?: number } = { requestId: crypto.randomUUID() };
  if (current.hasManualChanges) {
    if (!await confirm('重新入库将覆盖人工维护', <p>将重建全部自动块，覆盖人工正文、新增、删除以及 Chunk 独立启停设置。当前文档版本为 {current.version}，请确认已审阅这些修改。</p>, true)) return null;
    request.confirmOverwriteEdited = true; request.expectedDocumentVersion = current.version;
  }
  return api.send<Job>('POST', retryOf ? `/api/ingestion-jobs/${retryOf}/retry` : `/api/documents/${doc.id}/ingestion-jobs`, request);
}
export function ModelInfo({ model, provider, dimension }: { model: string; provider: string; dimension: number }) {
  return <Space size={4} wrap><Tag>{model}</Tag><Typography.Text type="secondary">{provider} · {dimension} 维</Typography.Text></Space>;
}
export function SummaryCard({ title, value, detail }: { title: string; value: ReactNode; detail?: string }) {
  return <Card size="small" className="summary-card"><div className="summary-label">{title}</div><div className="summary-value">{value}</div>{detail && <div className="summary-detail">{detail}</div>}</Card>;
}
export function DocumentInfo({ doc }: { doc: DocumentRecord }) {
  return <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small" items={[
    { key: 'filename', label: '原始文件', children: doc.originalFilename }, { key: 'size', label: '文件大小', children: bytes(doc.fileSize) },
    { key: 'mime', label: '文件类型', children: doc.mimeType }, { key: 'generation', label: '当前索引版本', children: doc.activeGeneration ?? '未入库' },
    { key: 'revision', label: '配置版本', children: doc.configRevision }, { key: 'last', label: '最近入库', children: date(doc.lastIngestedAt) },
  ]} />;
}
