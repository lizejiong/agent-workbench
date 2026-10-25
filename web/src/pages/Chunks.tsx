import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Form, Input, Modal, Pagination, Select, Space, Switch, Tabs, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { ErrorPanel, Nothing, RefreshButton, Source, useOperation } from '../components';
import type { Chunk, DocumentRecord, Page } from '../types';

export default function Chunks({ doc, changed }: { doc: DocumentRecord; changed: () => void }) {
  const api = useApi(); const op = useOperation(); const [current, setCurrent] = useState(1); const [enabled, setEnabled] = useState<boolean>();
  const [selection, setSelection] = useState<string[]>([]); const [editing, setEditing] = useState<Chunk | 'new' | null>(null); const [editVersion, setEditVersion] = useState(doc.version); const [form] = Form.useForm();
  const page = useResource(signal => api.get<Page<Chunk>>(`/api/documents/${doc.id}/chunks` + query({ current, size: 10, enabled }), signal), [api, doc.id, doc.activeGeneration, current, enabled]);
  useEffect(() => { setSelection([]); }, [doc.id, doc.activeGeneration, current, enabled]);
  const refresh = () => { setSelection([]); page.refresh(); changed(); };
  const open = (chunk: Chunk | 'new') => { setEditVersion(doc.version); form.setFieldsValue({ content: chunk === 'new' ? '' : chunk.content }); setEditing(chunk); };
  const save = async () => { const values = await form.validateFields(); const target = editing; if (!target) return; const result = await op.run(() => api.send(target === 'new' ? 'POST' : 'PATCH', `/api/documents/${doc.id}/chunks${target === 'new' ? '' : '/' + target.id}`, { version: editVersion, content: values.content }), '正文与向量已同步保存'); if (result !== undefined) { setEditing(null); refresh(); } };
  const toggle = (chunk: Chunk) => op.run(async () => { await api.send('PATCH', `/api/documents/${doc.id}/chunks/${chunk.id}/enabled`, { version: doc.version, enabled: !chunk.enabled }); refresh(); }, chunk.enabled ? 'Chunk 已禁用' : 'Chunk 已启用');
  const remove = async (chunk: Chunk) => { if (!await op.confirm('删除 Chunk', '将同时删除本块正文与向量索引，并更新文档块数。重新入库会从原文件重新生成自动块。', true)) return; await op.run(async () => { await api.send('DELETE', `/api/documents/${doc.id}/chunks/${chunk.id}?version=${doc.version}`); refresh(); }, 'Chunk 已删除'); };
  const batch = async (enable: boolean, applyAll = false) => {
    if (!applyAll && !selection.length) return;
    if (!await op.confirm(`${enable ? '启用' : '禁用'}${applyAll ? '整个文档' : '所选'} Chunk`, applyAll ? `本操作作用于文档内全部 Chunk，包含其他分页和筛选之外的块。确认${enable ? '启用' : '禁用'}全部？` : `仅操作显式选中的 ${selection.length} 个 Chunk。`, !enable)) return;
    await op.run(async () => { await api.send('POST', `/api/documents/${doc.id}/chunks/batch-enabled`, { version: doc.version, enabled: enable, ...(applyAll ? { applyAll: true } : { chunkIds: selection }) }); refresh(); }, 'Chunk 状态已更新');
  };
  return <><ErrorPanel error={op.error} /><ErrorPanel error={page.error} retry={page.refresh} />
    {!doc.enabled && <Alert showIcon type="info" title="文档已禁用，以下全部 Chunk 都不会参与检索。重新启用文档会保留每个 Chunk 的独立设置。" />}
    <div className="toolbar"><Space wrap><Select aria-label="Chunk 状态筛选" value={enabled === undefined ? undefined : String(enabled)} allowClear placeholder="全部 Chunk" style={{ width: 150 }} options={[{ value: 'true', label: '独立启用' }, { value: 'false', label: '独立禁用' }]} onChange={v => { setEnabled(v === undefined ? undefined : v === 'true'); setCurrent(1); }} /><RefreshButton onClick={refresh} /><span className="muted">已选 {selection.length} 项</span></Space><Button type="primary" icon={<PlusOutlined />} disabled={op.busy || !doc.enabled || doc.activeGeneration === null} onClick={() => open('new')}>新增 Chunk</Button></div>
    <Space wrap style={{ marginBottom: 20 }}><Button disabled={!selection.length || op.busy || !doc.enabled} onClick={() => void batch(true)}>启用所选</Button><Button disabled={!selection.length || op.busy} onClick={() => void batch(false)}>禁用所选</Button><Button type="text" disabled={op.busy || !doc.enabled || !doc.chunkCount} onClick={() => void batch(true, true)}>启用文档全部块…</Button><Button type="text" danger disabled={op.busy || !doc.chunkCount} onClick={() => void batch(false, true)}>禁用文档全部块…</Button></Space>
    {page.loading ? <p className="muted">正在加载 Chunk…</p> : !page.value?.records.length ? <Nothing text="暂无当前版本 Chunk，先提交文档入库" /> : page.value.records.map(chunk => <Card key={chunk.id} size="small" className="chunk-card" title={<Space wrap><Checkbox aria-label={`选择 Chunk ${chunk.chunkIndex}`} checked={selection.includes(chunk.id)} onChange={e => setSelection(ids => e.target.checked ? [...ids, chunk.id] : ids.filter(id => id !== chunk.id))} /><span>Chunk {chunk.chunkIndex}</span>{chunk.edited && <Tag color="gold">人工修改</Tag>}<Tag>{chunk.charCount} 字符</Tag><Tag>{chunk.tokenCount ?? '—'} Token{chunk.tokenCountEstimated ? '（估算）' : ''}</Tag></Space>} extra={<Space><Switch size="small" aria-label={`启用 Chunk ${chunk.chunkIndex}`} checked={chunk.enabled} disabled={op.busy || (!chunk.enabled && !doc.enabled)} onChange={() => void toggle(chunk)} /></Space>}>
      <Source metadata={chunk.metadata} /><Tabs size="small" className="chunk-tabs" items={[{ key: 'content', label: '展示正文', children: <pre className="text-body">{chunk.content}</pre> }, { key: 'embedding', label: '向量化文本', children: <pre className="text-body">{chunk.embeddingText}</pre> }]} />
      <div className="chunk-footer"><Typography.Text className="compact-code" type="secondary" copyable>{chunk.id}</Typography.Text><Space><Button size="small" disabled={op.busy || !doc.enabled} onClick={() => open(chunk)}>编辑正文</Button><Button size="small" danger disabled={op.busy} onClick={() => void remove(chunk)}>删除</Button></Space></div>
    </Card>)}
    <div className="pagination-row"><Pagination current={current} pageSize={10} total={page.value?.total || 0} showSizeChanger={false} showTotal={t => `共 ${t} 个 Chunk`} onChange={setCurrent} /></div>
    <Modal open={editing !== null} title={editing === 'new' ? '新增 Chunk' : '编辑 Chunk'} width={760} onCancel={() => !op.busy && setEditing(null)} onOk={() => void save().catch(() => {})} okText="保存并更新向量" cancelText="取消" confirmLoading={op.busy} forceRender>
      <Alert showIcon type="info" title="人工正文直接作为向量化文本，不继承旧段落的位置。模型或索引失败时不会发布不一致的新正文。" /><ErrorPanel error={op.error} retry={refresh} /><Form form={form} layout="vertical"><Form.Item name="content" label="正文" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={14} showCount /></Form.Item></Form>
      <p className="muted">按版本 {editVersion} 保存。若文档已变化，需刷新并重新审阅后再编辑；重新入库将要求显式确认覆盖。</p>
    </Modal>
  </>;
}
