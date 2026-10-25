import { useEffect, useRef } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Space, Switch, Tabs, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, DownloadOutlined } from '@ant-design/icons';
import { useApi, useResource } from '../state';
import { DocumentInfo, ErrorPanel, IngestionFields, Loading, PageTitle, RefreshButton, Status, submitJob, useOperation } from '../components';
import Chunks from './Chunks';
import Jobs from './Jobs';
import Refresh from './Refresh';
import type { Capabilities, Cleanup, DocumentRecord, KeywordState } from '../types';

function Configuration({ doc, changed }: { doc: DocumentRecord; changed: () => void }) {
  const api = useApi(); const op = useOperation(); const [form] = Form.useForm(); const version = useRef(doc.version);
  useEffect(() => { form.setFieldsValue({ name: doc.name, ingestionSpec: structuredClone(doc.ingestionSpec) }); version.current = doc.version; }, [form, doc.id]);
  const save = async () => { const values = await form.validateFields(); await op.run(async () => { const updated = await api.send<DocumentRecord>('PATCH', `/api/documents/${doc.id}`, { ...values, ingestionSpec: { ...doc.ingestionSpec, ...values.ingestionSpec }, version: version.current }); version.current = updated.version; changed(); }, '配置已保存，需提交入库才能发布新的分块结果'); };
  return <Card title="文档与入库配置"><ErrorPanel error={op.error} /><Form form={form} layout="vertical"><Form.Item name="name" label="文档名称" rules={[{ required: true, whitespace: true, max: 255 }]}><Input maxLength={255} /></Form.Item><IngestionFields /><Space wrap style={{ marginTop: 24 }}><Button type="primary" loading={op.busy} onClick={() => void save().catch(() => {})}>保存配置</Button><Button onClick={() => { form.setFieldsValue({ name: doc.name, ingestionSpec: structuredClone(doc.ingestionSpec) }); version.current = doc.version; }}>重新载入当前配置</Button></Space></Form><p className="muted">保存以编辑开始时的文档版本进行校验。若遇版本冲突，先重新载入并审阅内容。</p></Card>;
}
function Keyword({ doc }: { doc: DocumentRecord }) {
  const api = useApi(); const op = useOperation();
  const caps = useResource(signal => api.get<Capabilities>('/api/knowledge/search-capabilities', signal), [api]);
  const state = useResource(signal => api.get<KeywordState>(`/api/documents/${doc.id}/keyword-index`, signal), [api, doc.id, doc.version], s => !!caps.value?.keywordConfigured && ['PENDING', 'RUNNING'].includes(s.status));
  return <Card size="small" title="关键词索引" style={{ marginTop: 20 }}><ErrorPanel error={caps.error} retry={caps.refresh} /><ErrorPanel error={state.error} retry={state.refresh} /><ErrorPanel error={op.error} />{caps.value && !caps.value.keywordConfigured ? <span className="muted">关键词服务未启用，向量检索仍可使用。同步记录将在服务启用后由后台处理。</span> : <Space wrap><Status value={state.value?.status} /><span className="muted">已同步修订 {state.value?.syncedRevision ?? '—'} / {state.value?.revision ?? '—'}</span>{state.value?.errorCode && <Tag color="error">{state.value.errorCode}</Tag>}<Button size="small" onClick={() => state.refresh()}>查询状态</Button>{state.value?.status === 'FAILED' && <Button size="small" loading={op.busy} onClick={() => void op.run(async () => { await api.send('POST', `/api/kbs/${doc.kbId}/keyword-sync/${doc.id}/retry`); state.refresh(); }, '已提交索引同步重试')}>重试同步</Button>}</Space>}</Card>;
}
export default function DocumentDetail() {
  const { documentId } = useParams(); const [params, setParams] = useSearchParams(); const navigate = useNavigate(); const api = useApi(); const op = useOperation();
  const resource = useResource(signal => api.get<DocumentRecord>(`/api/documents/${documentId}`, signal), [api, documentId], d => d.processStatus === 'PROCESSING');
  const doc = resource.value;
  const toggle = () => op.run(async () => { if (!doc) return; await api.send('PATCH', `/api/documents/${doc.id}/enabled`, { version: doc.version, enabled: !doc.enabled }); resource.refresh(); }, doc?.enabled ? '文档已禁用' : '文档已启用，独立禁用的 Chunk 保持禁用');
  const ingest = () => op.run(async () => { if (!doc) return; const job = await submitJob(api, doc, op.confirm); if (job) { setParams({ tab: 'jobs', job: job.id }); resource.refresh(); } });
  const remove = async () => { if (!doc || !await op.confirm('删除文档', '将立即停止检索和刷新，使在途任务失效，并持久化清理所有版本、原文件与索引。此操作无法撤销。', true)) return; await op.run(async () => { const receipt = await api.send<Cleanup>('DELETE', `/api/documents/${doc.id}?version=${doc.version}`); navigate(`/kbs/${doc.kbId}?cleanup=${receipt.id}`); }, '文档已删除，后台将持续清理资源'); };
  return <Loading loading={resource.loading} error={resource.error} retry={resource.refresh}>{doc && <>
    <Link to={`/kbs/${doc.kbId}`} className="back-link"><ArrowLeftOutlined /> 返回文档列表</Link>
    <PageTitle eyebrow="DOCUMENT / WORKBENCH" title={doc.name} description={<Space wrap><Status value={doc.processStatus} /><Tag>{doc.chunkCount} 个 Chunk</Tag><Tag>{doc.sourceType === 'URL' ? '远程文档' : '本地文件'}</Tag>{doc.hasManualChanges && <Tag color="gold">含人工维护</Tag>}</Space>} actions={<><RefreshButton onClick={resource.refresh} /><Button icon={<DownloadOutlined />} disabled={op.busy} onClick={() => void op.run(() => api.download(`/api/documents/${doc.id}/file`, doc.originalFilename))}>原文件</Button><Button type="primary" disabled={!doc.enabled} loading={op.busy} onClick={() => void ingest()}>提交入库</Button></>} />
    <ErrorPanel error={op.error} /><Card className="doc-overview"><div className="toolbar"><Space><Switch aria-label="文档启用状态" checked={doc.enabled} disabled={op.busy} onChange={() => void toggle()} /><span>{doc.enabled ? '文档已启用' : '文档已禁用'}</span></Space><Space><Typography.Text type="secondary" className="compact-code" copyable>{doc.id}</Typography.Text><Button size="small" danger disabled={op.busy} onClick={() => void remove()}>删除文档</Button></Space></div><DocumentInfo doc={doc} /></Card>
    {!doc.enabled && <Alert showIcon type="warning" title="文档已退出检索范围。重新启用时只恢复本身仍启用的 Chunk。" />}
    <Tabs activeKey={params.get('tab') || 'chunks'} onChange={tab => setParams({ tab })} destroyOnHidden items={[
      { key: 'chunks', label: 'Chunk 内容', children: <Chunks doc={doc} changed={resource.refresh} /> },
      { key: 'config', label: '入库配置', children: <Configuration doc={doc} changed={resource.refresh} /> },
      { key: 'jobs', label: '入库任务', children: <Jobs doc={doc} changed={resource.refresh} initialJob={params.get('job') || undefined} /> },
      ...(doc.sourceType === 'URL' ? [{ key: 'refresh', label: '刷新设置', children: <Refresh doc={doc} changed={resource.refresh} /> }] : []),
    ]} /><Keyword doc={doc} />
  </>}</Loading>;
}
