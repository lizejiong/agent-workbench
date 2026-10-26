import { useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Checkbox, Collapse, Dropdown, Form, Input, Modal, Select, Space, Switch, Table, Tag, Upload } from 'antd';
import { CloudUploadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { activeJob, bytes, DateCell, defaultSpec, ErrorPanel, FileBadge, IngestionFields, ListHeading, PageTitle, RefreshButton, Status, submitJob, useOperation } from '../components';
import type { Cleanup, DocumentRecord, FormatCapability, KnowledgeBase, Page } from '../types';

export default function Documents() {
  const { kbId } = useParams(); const [params, setParams] = useSearchParams(); const api = useApi(); const navigate = useNavigate(); const op = useOperation();
  const [current, setCurrent] = useState(1); const [size, setSize] = useState(10); const [name, setName] = useState(''); const [status, setStatus] = useState<string>();
  const [dialog, setDialog] = useState<'file' | 'url' | null>(null); const [files, setFiles] = useState<UploadFile[]>([]); const [fileError, setFileError] = useState<string>(); const [form] = Form.useForm();
  const [expanded, setExpanded] = useState<string[]>([]);
  const cleanupId = params.get('cleanup') || undefined; const key = useRef({ payload: '', id: crypto.randomUUID() });
  const base = useResource(signal => api.get<KnowledgeBase>(`/api/kbs/${kbId}`, signal), [api, kbId]);
  const page = useResource(signal => api.get<Page<DocumentRecord>>(`/api/kbs/${kbId}/documents` + query({ current, size, name, status }), signal), [api, kbId, current, size, name, status], p => p.records.some(d => d.processStatus === 'PROCESSING'));
  const formats = useResource(signal => api.get<FormatCapability[]>('/api/knowledge/formats', signal), [api]);
  const cleanup = useResource(signal => cleanupId ? api.get<Cleanup>(`/api/document-cleanups/${cleanupId}`, signal) : Promise.resolve(null), [api, cleanupId], r => !!r && activeJob(r.status));
  const open = (type: 'file' | 'url') => { form.resetFields(); form.setFieldsValue({ ingestionSpec: structuredClone(defaultSpec), ingest: type === 'url' }); setExpanded([]); setFiles([]); setFileError(undefined); key.current = { payload: '', id: crypto.randomUUID() }; setDialog(type); };
  const save = async () => {
    let values;
    try { values = await form.validateFields(); }
    catch (error) {
      // Hidden settings remain registered; reveal the field that needs correction.
      if (form.getFieldsError().some(field => field.errors.length && (field.name[0] === 'ingestionSpec' || field.name[0] === 'name'))) setExpanded(keys => [...new Set([...keys, 'settings'])]);
      throw error;
    }
    const file = files[0]?.originFileObj;
    if (dialog === 'file' && (!file || file.size === 0)) { setFileError('请选择非空文件'); return; }
    const payload = JSON.stringify([dialog, values, file?.name, file?.size, file?.lastModified]);
    if (payload !== key.current.payload) key.current = { payload, id: crypto.randomUUID() };
    const saved = await op.run(async () => {
      if (dialog === 'url') return api.send<{ document: DocumentRecord; jobId?: string }>('POST', `/api/kbs/${kbId}/documents/import-url`, values, { 'Idempotency-Key': key.current.id });
      const data = new FormData(); data.append('file', file!); data.append('metadata', JSON.stringify({ name: values.name || undefined, ingestionSpec: values.ingestionSpec }));
      const document = await api.upload<DocumentRecord>(`/api/kbs/${kbId}/documents`, data, key.current.id);
      return { document };
    }, dialog === 'url' ? '远程文档已保存' : '原文件已上传，等待提交入库');
    if (saved) { setDialog(null); page.refresh(); base.refresh(); navigate(`/documents/${saved.document.id}`); }
  };
  const ingest = (doc: DocumentRecord) => op.run(async () => { const job = await submitJob(api, doc, op.confirm); if (job) navigate(`/documents/${doc.id}?tab=jobs&job=${job.id}`); });
  const toggle = (doc: DocumentRecord) => op.run(async () => { await api.send('PATCH', `/api/documents/${doc.id}/enabled`, { version: doc.version, enabled: !doc.enabled }); page.refresh(); }, doc.enabled ? '文档已禁用，已从检索范围排除' : '文档已启用，保留 Chunk 各自的启停设置');
  const remove = async (doc: DocumentRecord) => {
    if (!await op.confirm('删除文档', <>“{doc.name}”将立即不可检索，正在执行的任务将失效；原文件与所有索引版本由后台持续清理。此操作无法撤销。</>, true)) return;
    await op.run(async () => { const receipt = await api.send<Cleanup>('DELETE', `/api/documents/${doc.id}?version=${doc.version}`); setParams({ cleanup: receipt.id }); page.refresh(); base.refresh(); }, '文档已删除，资源清理已提交');
  };
  const columns: ColumnsType<DocumentRecord> = [
    { title: '文档名称', dataIndex: 'name', render: (_, d) => <div className="resource-cell"><FileBadge name={d.originalFilename} mime={d.mimeType} /><div><Link className="row-title" to={`/documents/${d.id}`}>{d.name}</Link><div className="muted">{d.sourceType === 'URL' ? 'URL 导入' : '本地上传'} · {d.ingestionSpec.splitter.kind === 'WHOLE_DOCUMENT' ? '整篇分块' : '结构分块'} · {d.ingestionSpec.parseProfile === 'fidelity' ? '高保真' : '快速解析'}</div></div></div> },
    { title: '状态', render: (_, d) => <Space orientation="vertical" size={3}><Status value={d.processStatus} />{!d.enabled && <Tag>已禁用</Tag>}</Space> },
    { title: '分块数', width: 85, render: (_, d) => <Link className="count-link" to={`/documents/${d.id}?tab=chunks`}>{d.chunkCount}</Link> },
    { title: '大小', width: 100, render: (_, d) => <span className="muted">{bytes(d.fileSize)}</span>, responsive: ['lg'] },
    { title: '最近入库', render: (_, d) => <DateCell value={d.lastIngestedAt} />, responsive: ['xl'] },
    { title: '启用', width: 70, render: (_, d) => <Switch size="small" aria-label={`启用文档 ${d.name}`} checked={d.enabled} disabled={op.busy} onChange={() => void toggle(d)} /> },
    { title: '操作', width: 184, render: (_, d) => <Space size={2}><Button type="text" size="small" onClick={() => navigate(`/documents/${d.id}`)}>管理</Button><Button type="text" size="small" aria-label={`入库 ${d.name}`} disabled={op.busy || !d.enabled} onClick={() => void ingest(d)}>入库</Button><Dropdown trigger={['click']} menu={{ items: [{ key: 'download', label: '下载原文件' }, { key: 'delete', label: '删除文档', danger: true }], onClick: ({ key }) => key === 'download' ? void op.run(() => api.download(`/api/documents/${d.id}/file`, d.originalFilename)) : void remove(d) }}><Button type="text" size="small" aria-label={`文档 ${d.name} 的更多操作`} disabled={op.busy}>更多</Button></Dropdown></Space> },
  ];
  return <>
    <PageTitle eyebrow="DOCUMENT LIBRARY" title="文档管理" breadcrumbs={[{ title: '知识库管理', to: '/kbs' }, { title: base.value?.name || '知识库' }, { title: '文档管理' }]} description={base.value && `${base.value.embeddingModelId} · ${base.value.embeddingDimension} 维`} actions={<><Button onClick={() => navigate(`/search?kb=${kbId}`)}>检索测试</Button><Button onClick={() => open('url')}>URL 导入</Button><Button type="primary" icon={<CloudUploadOutlined />} onClick={() => open('file')}>上传文档</Button></>} />
    <ErrorPanel error={base.error} retry={base.refresh} /><ErrorPanel error={page.error} retry={page.refresh} /><ErrorPanel error={op.error} />
    <ErrorPanel error={cleanup.error} retry={cleanup.refresh} />{cleanup.value && <Alert showIcon type={cleanup.value.status === 'FAILED' ? 'warning' : 'info'} title={<Space>资源清理 <Status value={cleanup.value.status} /><span>尝试 {cleanup.value.attempts} 次</span></Space>} description={<Space wrap><span className="compact-code">{cleanup.value.id}</span>{cleanup.value.errorCode && <span>{cleanup.value.errorCode}</span>}{cleanup.value.status === 'FAILED' && <Button size="small" onClick={() => void op.run(async () => { await api.send('POST', `/api/document-cleanups/${cleanupId}/retry`); cleanup.refresh(); })}>重试清理</Button>}</Space>} closable onClose={() => setParams({})} />}
    <Card className="table-card" title={<ListHeading title="文档" total={page.value?.total} />} extra={<div className="list-filters"><Input.Search aria-label="搜索文档名称" placeholder="搜索文档名称" allowClear onSearch={v => { setName(v); setCurrent(1); }} /><Select aria-label="筛选文档状态" placeholder="全部状态" allowClear value={status} style={{ width: 120 }} onChange={v => { setStatus(v); setCurrent(1); }} options={['PENDING', 'PROCESSING', 'READY', 'FAILED'].map(s => ({ value: s, label: <Status value={s} /> }))} /><RefreshButton onClick={() => { page.refresh(); base.refresh(); }} /></div>}>
      <Table rowKey="id" loading={page.loading} columns={columns} dataSource={page.value?.records || []} scroll={{ x: 720 }} pagination={{ current, pageSize: size, total: page.value?.total, showSizeChanger: true, showTotal: t => `共 ${t} 份`, onChange: (p, s) => { setCurrent(p); setSize(s); } }} />
    </Card>
    <Modal className="compact-dialog import-dialog" title={dialog === 'file' ? '上传本地文档' : '导入远程文档'} open={!!dialog} onCancel={() => !op.busy && setDialog(null)} onOk={() => void save().catch(() => {})} confirmLoading={op.busy} okText={dialog === 'file' ? '上传原文件' : '下载并保存'} cancelText="取消" width={560} forceRender>
      <ErrorPanel error={op.error} /><Form form={form} preserve={false} layout="vertical">
        {dialog === 'file' ? <Form.Item label="原始文件" validateStatus={fileError ? 'error' : undefined} help={fileError}><Upload.Dragger beforeUpload={() => false} maxCount={1} fileList={files} onChange={({ fileList }) => { setFiles(fileList.slice(-1)); setFileError(undefined); }}><CloudUploadOutlined className="upload-icon" /><p>点击选择或拖入文件</p></Upload.Dragger><div className="field-note">仅保存原文件，上传后可提交入库。</div></Form.Item> : <><Form.Item name="url" label="来源 URL" extra="需在服务端配置允许下载的来源。" rules={[{ required: true, message: '请输入来源 URL' }, { type: 'url', message: '请输入完整 HTTP(S) 地址' }]}><Input placeholder="https://example.com/document.pdf" /></Form.Item><Form.Item name="filename" label="文件名（含扩展名）" rules={[{ required: true, whitespace: true, max: 255, message: '请输入文件名，最长 255 字符' }]}><Input placeholder="document.pdf" /></Form.Item></>}
        {dialog === 'url' && <Form.Item name="ingest" initialValue={true} valuePropName="checked"><Checkbox>保存后自动入库</Checkbox></Form.Item>}
        <Collapse ghost className="import-settings" activeKey={expanded} onChange={keys => setExpanded(Array.isArray(keys) ? keys : [keys])} items={[
          { key: 'settings', label: '高级设置', forceRender: true, children: <><Form.Item name="name" label="文档名称（可选）" rules={[{ max: 255 }]}><Input placeholder="默认使用文件名" /></Form.Item><IngestionFields showNote={false} /></> },
          { key: 'formats', label: '查看格式支持', children: <><div className="format-list">{formats.value?.map(f => <div key={f.extensions.join(',')}>{f.extensions.join(' / ')}：快速{formatLabel(f.fast)}；高保真{formatLabel(f.fidelity)}</div>)}</div><ErrorPanel error={formats.error} retry={formats.refresh} /></> },
        ]} />
      </Form>
    </Modal>
  </>;
}
function formatLabel(value: string): string {
  const labels: Record<string, string> = { LOCAL: '可用', LOCAL_ALIAS: '使用相同基础解析', LOCAL_TEXT_ONLY: '仅文本，不含 OCR', MINERU_CONFIGURED: 'MinerU 已配置，需确认服务可用', MINERU_DISABLED: '需配置 MinerU', UNAVAILABLE: '不支持', VLM_CONFIGURED: 'VLM 已配置，需确认服务可用', VLM_ALIAS_CONFIGURED: '使用相同 VLM', VLM_DISABLED: '需配置 VLM' };
  return labels[value] || '请核对服务端支持范围';
}
