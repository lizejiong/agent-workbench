import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Descriptions, Drawer, Form, Grid, Input, Modal, Pagination, Segmented, Select, Space, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Dropdown } from 'antd';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { ErrorPanel, Nothing, RefreshButton, Source, useOperation } from '../components';
import type { Chunk, DocumentRecord, Page } from '../types';

export default function Chunks({ doc, changed }: { doc: DocumentRecord; changed: () => void }) {
  const api = useApi(); const op = useOperation(); const [current, setCurrent] = useState(1); const [enabled, setEnabled] = useState<boolean>();
  const [selection, setSelection] = useState<string[]>([]); const [editing, setEditing] = useState<Chunk | 'new' | null>(null); const [editVersion, setEditVersion] = useState(doc.version); const [form] = Form.useForm();
  const screens = Grid.useBreakpoint(); const [chosenView, setView] = useState<string>(); const view = chosenView || (screens.md === false ? 'cards' : 'table'); const [preview, setPreview] = useState<Chunk | null>(null);
  const locked = op.busy || doc.processStatus === 'PROCESSING';
  const page = useResource(signal => api.get<Page<Chunk>>(`/api/documents/${doc.id}/chunks` + query({ current, size: 10, enabled }), signal), [api, doc.id, doc.activeGeneration, current, enabled]);
  useEffect(() => { setSelection([]); }, [doc.id, doc.activeGeneration, current, enabled]);
  useEffect(() => { setPreview(null); setEditing(null); }, [doc.id, doc.activeGeneration]);
  const refresh = () => { setSelection([]); setPreview(null); page.refresh(); changed(); };
  const open = (chunk: Chunk | 'new') => { setPreview(null); setEditVersion(doc.version); form.setFieldsValue({ content: chunk === 'new' ? '' : chunk.content }); setEditing(chunk); };
  const save = async () => { const values = await form.validateFields(); const target = editing; if (!target) return; const result = await op.run(() => api.send(target === 'new' ? 'POST' : 'PATCH', `/api/documents/${doc.id}/chunks${target === 'new' ? '' : '/' + target.id}`, { version: editVersion, content: values.content }), '正文与向量已同步保存'); if (result !== undefined) { setEditing(null); refresh(); } };
  const toggle = (chunk: Chunk) => op.run(async () => { await api.send('PATCH', `/api/documents/${doc.id}/chunks/${chunk.id}/enabled`, { version: doc.version, enabled: !chunk.enabled }); refresh(); }, chunk.enabled ? 'Chunk 已禁用' : 'Chunk 已启用');
  const remove = async (chunk: Chunk) => { if (!await op.confirm('删除 Chunk', '将同时删除本块正文与向量索引，并更新文档块数。重新入库会从原文件重新生成自动块。', true)) return; await op.run(async () => { await api.send('DELETE', `/api/documents/${doc.id}/chunks/${chunk.id}?version=${doc.version}`); refresh(); }, 'Chunk 已删除'); };
  const batch = async (enable: boolean, applyAll = false) => {
    if (!applyAll && !selection.length) return;
    if (!await op.confirm(`${enable ? '启用' : '禁用'}${applyAll ? '整个文档' : '所选'} Chunk`, applyAll ? `本操作作用于文档内全部 Chunk，包含其他分页和筛选之外的块。确认${enable ? '启用' : '禁用'}全部？` : `仅操作显式选中的 ${selection.length} 个 Chunk。`, !enable)) return;
    await op.run(async () => { await api.send('POST', `/api/documents/${doc.id}/chunks/batch-enabled`, { version: doc.version, enabled: enable, ...(applyAll ? { applyAll: true } : { chunkIds: selection }) }); refresh(); }, 'Chunk 状态已更新');
  };
  const chunkState = (chunk: Chunk) => <Space size={4} wrap><Tag color={chunk.enabled ? 'success' : 'default'}>{chunk.enabled ? '已启用' : '已禁用'}</Tag>{chunk.edited && <Tag color="gold">人工维护</Tag>}</Space>;
  const actions = (chunk: Chunk) => <Space size={4}><Button type="text" size="small" disabled={locked || !doc.enabled} onClick={() => open(chunk)}>编辑</Button><Button type="text" size="small" disabled={locked || (!chunk.enabled && !doc.enabled)} onClick={() => void toggle(chunk)}>{chunk.enabled ? '禁用' : '启用'}</Button><Tooltip title="删除分块"><Button size="small" type="text" className="row-delete" aria-label={`删除 Chunk ${chunk.chunkIndex}`} icon={<DeleteOutlined />} disabled={locked} onClick={() => void remove(chunk)} /></Tooltip></Space>;
  const columns: ColumnsType<Chunk> = [
    { title: '序号', width: 60, render: (_, chunk) => <span className="chunk-number">{String(chunk.chunkIndex).padStart(2, '0')}</span> },
    { title: '分块内容', render: (_, chunk) => <div className="chunk-cell"><button className="chunk-preview-button" onClick={() => setPreview(chunk)} aria-label={`查看 Chunk ${chunk.chunkIndex}`}><span className="chunk-excerpt">{chunk.content}</span></button><Source metadata={chunk.metadata} /></div> },
    { title: '状态', width: 126, render: (_, chunk) => chunkState(chunk) },
    { title: '字符', width: 72, render: (_, chunk) => <span className="numeric">{chunk.charCount.toLocaleString()}</span>, responsive: ['md'] },
    { title: 'Token', width: 80, render: (_, chunk) => <Tooltip title={chunk.tokenCountEstimated ? '估算 Token 数' : 'Token 数'}><span className="numeric">{chunk.tokenCount?.toLocaleString() ?? '—'}{chunk.tokenCountEstimated && <small> ≈</small>}</span></Tooltip>, responsive: ['lg'] },
    { title: '操作', width: 172, render: (_, chunk) => actions(chunk) },
  ];
  const pagination = { current, pageSize: 10, total: page.value?.total || 0, showSizeChanger: false, showTotal: (total: number) => `共 ${total} 个分块`, onChange: setCurrent };
  return <><ErrorPanel error={op.error} /><ErrorPanel error={page.error} retry={page.refresh} />
    {!doc.enabled && <Alert showIcon type="info" title="文档已禁用，以下分块均不参与检索。重新启用文档会保留每个分块的独立设置。" />}
    {doc.processStatus === 'PROCESSING' && <Alert showIcon type="info" title="文档正在入库。处理完成后可维护分块，当前版本仍可查看。" />}
    <Card className="table-card chunks-panel">
      <div className="list-toolbar chunks-toolbar"><Select aria-label="Chunk 状态筛选" value={enabled === undefined ? undefined : String(enabled)} allowClear placeholder="全部状态" style={{ width: 120 }} options={[{ value: 'true', label: '独立启用' }, { value: 'false', label: '独立禁用' }]} onChange={value => { setEnabled(value === undefined ? undefined : value === 'true'); setCurrent(1); }} /><RefreshButton onClick={refresh} /><Segmented aria-label="分块显示方式" value={view} onChange={setView} options={[{ value: 'table', label: '表格' }, { value: 'cards', label: '卡片' }]} /><div className="toolbar-end"><Dropdown trigger={['click']} menu={{ items: [{ key: 'enable-all', label: '启用文档全部块…', disabled: locked || !doc.enabled || !doc.chunkCount }, { key: 'disable-all', label: '禁用文档全部块…', danger: true, disabled: locked || !doc.chunkCount }], onClick: ({ key }) => void batch(key === 'enable-all', true) }}><Button>更多操作</Button></Dropdown><Button icon={<PlusOutlined />} disabled={locked || !doc.enabled || doc.activeGeneration === null} onClick={() => open('new')}>新增 Chunk</Button></div></div>
      {selection.length > 0 && <div className="selection-bar has-selection"><span>已选 <strong>{selection.length}</strong> 项</span><Button size="small" type="link" disabled={locked || !doc.enabled} onClick={() => void batch(true)}>启用所选</Button><Button size="small" type="link" disabled={locked} onClick={() => void batch(false)}>禁用所选</Button><Button size="small" type="text" onClick={() => setSelection([])}>取消选择</Button></div>}
      {view === 'table' ? <Table<Chunk> rowKey="id" loading={page.loading} columns={columns} dataSource={page.value?.records || []} scroll={{ x: 800 }} rowSelection={{ selectedRowKeys: selection, onChange: keys => setSelection(keys.map(String)), columnWidth: 48, getCheckboxProps: chunk => ({ 'aria-label': `选择 Chunk ${chunk.chunkIndex}` }) }} pagination={pagination} locale={{ emptyText: <Nothing text={enabled === undefined ? '暂无当前版本分块，先提交文档入库' : '没有符合状态的分块'} /> }} /> : <div className="chunk-cards">
        {page.loading ? <p className="muted">正在加载分块…</p> : !page.value?.records.length ? <Nothing text="没有符合条件的分块" /> : page.value.records.map(chunk => <Card key={chunk.id} size="small" className="chunk-card" title={<Space><Checkbox aria-label={`选择 Chunk ${chunk.chunkIndex}`} checked={selection.includes(chunk.id)} onChange={event => setSelection(ids => event.target.checked ? [...ids, chunk.id] : ids.filter(id => id !== chunk.id))} /><span>Chunk {String(chunk.chunkIndex).padStart(2, '0')}</span></Space>} extra={chunkState(chunk)}><Source metadata={chunk.metadata} /><button className="chunk-preview-button" onClick={() => setPreview(chunk)} aria-label={`查看 Chunk ${chunk.chunkIndex}`}><pre className="text-body chunk-card-excerpt">{chunk.content}</pre></button><div className="chunk-footer"><span className="muted">{chunk.charCount.toLocaleString()} 字符 · {chunk.tokenCount ?? '—'} Token{chunk.tokenCountEstimated ? ' ≈' : ''}</span>{actions(chunk)}</div></Card>)}
        <div className="card-pagination"><Pagination {...pagination} /></div>
      </div>}
    </Card>
    <Drawer title={preview ? `Chunk ${String(preview.chunkIndex).padStart(2, '0')} · 分块详情` : '分块详情'} open={!!preview} onClose={() => setPreview(null)} size={720} destroyOnHidden>
      {preview && <><div className="preview-meta">{chunkState(preview)}<span className="muted">{preview.charCount.toLocaleString()} 字符 · {preview.tokenCount ?? '—'} Token{preview.tokenCountEstimated ? '（估算）' : ''}</span></div><Source metadata={preview.metadata} />
        <Tabs className="chunk-tabs" items={[{ key: 'content', label: '展示正文', children: <pre className="text-body">{preview.content}</pre> }, { key: 'embedding', label: '向量化文本', children: <pre className="text-body">{preview.embeddingText}</pre> }]} />
        <Descriptions className="chunk-identifiers" column={1} size="small" items={[{ key: 'id', label: 'Chunk 标识', children: <Typography.Text className="compact-code" copyable>{preview.id}</Typography.Text> }, { key: 'generation', label: '索引版本', children: preview.generation }]} /><Button disabled={locked || !doc.enabled} onClick={() => open(preview)}>编辑正文</Button>
      </>}
    </Drawer>
    <Modal className="compact-dialog" open={editing !== null} title={editing === 'new' ? '新增 Chunk' : '编辑 Chunk'} width={640} zIndex={1200} onCancel={() => !op.busy && setEditing(null)} onOk={() => void save().catch(() => {})} okText="保存并更新向量" cancelText="取消" confirmLoading={op.busy} forceRender>
      <ErrorPanel error={op.error} retry={refresh} /><Form form={form} layout="vertical"><Form.Item name="content" label="正文" extra="保存时同步更新向量；人工正文不继承原段落位置。" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={8} showCount /></Form.Item></Form>
      <p className="muted">按版本 {editVersion} 保存。版本冲突时需重新载入；重新入库会要求确认覆盖人工修改。</p>
    </Modal>
  </>;
}
