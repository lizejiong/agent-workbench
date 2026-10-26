import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { App, Button, Card, Form, Input, Modal, Select, Space, Table } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { DateCell, ErrorPanel, ListHeading, ModelInfo, PageTitle, RefreshButton, useOperation } from '../components';
import type { KnowledgeBase, ModelProfile, Page } from '../types';

export default function KnowledgeBases() {
  const api = useApi(); const navigate = useNavigate(); const { message } = App.useApp(); const [params, setParams] = useSearchParams();
  const [current, setCurrent] = useState(1); const [size, setSize] = useState(10); const name = params.get('name') || '';
  const [search, setSearch] = useState(name);
  useEffect(() => { setSearch(name); setCurrent(1); }, [name]);
  const [editing, setEditing] = useState<KnowledgeBase | 'new' | null>(null);
  const [form] = Form.useForm(); const op = useOperation();
  const page = useResource(signal => api.get<Page<KnowledgeBase>>('/api/kbs' + query({ current, size, name }), signal), [api, current, size, name]);
  const models = useResource(signal => api.get<ModelProfile[]>('/api/kb-models', signal), [api]);
  const show = (base: KnowledgeBase | 'new') => { form.resetFields(); form.setFieldsValue(base === 'new' ? { embeddingProfileId: models.value?.[0]?.id } : base); setEditing(base); };
  const save = async () => {
    const values = await form.validateFields();
    const saved = await op.run(() => editing === 'new' ? api.send<KnowledgeBase>('POST', '/api/kbs', values) : api.send<KnowledgeBase>('PATCH', `/api/kbs/${editing!.id}`, { name: values.name, description: values.description || '', version: editing!.version }), '知识库已保存');
    if (saved) { setEditing(null); page.refresh(); }
  };
  const remove = async (base: KnowledgeBase) => {
    if (!await op.confirm('删除知识库', <>确认删除“{base.name}”？只能删除没有文档且资源清理完成的知识库。</>, true)) return;
    await op.run(async () => { await api.send('DELETE', `/api/kbs/${base.id}?version=${base.version}`); void message.success('知识库已删除'); page.refresh(); });
  };
  const columns: ColumnsType<KnowledgeBase> = [
    { title: '知识库名称', dataIndex: 'name', render: (_, b) => <div><Link className="row-title" to={`/kbs/${b.id}`}>{b.name}</Link>{b.description && <div className="muted ellipsis">{b.description}</div>}</div> },
    { title: '嵌入模型', render: (_, b) => <ModelInfo model={b.embeddingModelId || '未绑定'} provider={b.embeddingProvider || '—'} dimension={b.embeddingDimension || 0} />, responsive: ['sm'] },
    { title: '文档数', width: 100, render: (_, b) => <Link className="count-link" to={`/kbs/${b.id}`}>{b.documentCount}<span>份</span></Link> },
    { title: '创建时间', render: (_, b) => <DateCell value={b.createdAt} />, responsive: ['lg'] },
    { title: '操作', width: 190, render: (_, b) => <Space size={4}><Button type="text" size="small" onClick={() => show(b)}>编辑</Button><Button type="text" size="small" onClick={() => navigate(`/kbs/${b.id}`)}>管理</Button><Button type="text" className="row-delete" aria-label={`删除知识库 ${b.name}`} icon={<DeleteOutlined />} disabled={op.busy} onClick={() => void remove(b)} /></Space> },
  ];
  return <>
    <PageTitle eyebrow="KNOWLEDGE BASE" title="知识库管理" actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => show('new')}>创建知识库</Button>} />
    <ErrorPanel error={op.error} /><ErrorPanel error={models.error} retry={models.refresh} /><ErrorPanel error={page.error} retry={page.refresh} />
    <Card className="table-card" title={<ListHeading title="知识库" total={page.value?.total} />} extra={<div className="list-filters"><Input.Search placeholder="搜索知识库名称" allowClear aria-label="搜索知识库名称" value={search} onChange={event => setSearch(event.target.value)} onSearch={value => { setParams(value.trim() ? { name: value.trim() } : {}); setCurrent(1); }} /><RefreshButton onClick={page.refresh} /></div>}>
      <Table rowKey="id" columns={columns} dataSource={page.value?.records || []} loading={page.loading} scroll={{ x: 720 }} locale={{ emptyText: name ? '没有匹配的知识库' : '创建第一个知识库，开始整理资料' }}
        pagination={{ current, pageSize: size, total: page.value?.total, showSizeChanger: true, showTotal: total => `共 ${total} 个`, onChange: (p, s) => { setCurrent(p); setSize(s); } }} />
    </Card>
    <Modal className="compact-dialog" title={editing === 'new' ? '创建知识库' : '编辑知识库'} open={editing !== null} onCancel={() => setEditing(null)} onOk={() => void save().catch(() => {})} okText="保存" cancelText="取消" confirmLoading={op.busy} forceRender>
      <Form form={form} layout="vertical" preserve={false}><Form.Item label="名称" name="name" rules={[{ required: true, whitespace: true, max: 128 }]}><Input maxLength={128} autoFocus /></Form.Item>
        <Form.Item label="描述" name="description"><Input.TextArea rows={3} maxLength={2000} showCount /></Form.Item>
        {editing === 'new' && <Form.Item label="嵌入模型" name="embeddingProfileId" initialValue={models.value?.[0]?.id} rules={[{ required: true, message: '请选择嵌入模型' }]} extra="模型来自服务端配置。创建后模型绑定固定，迁移需建立新知识库。"><Select loading={models.loading} options={models.value?.map(m => ({ value: m.id, label: `${m.modelName} · ${m.provider} · ${m.dimension} 维` }))} /></Form.Item>}
        <ErrorPanel error={op.error} />
      </Form>
    </Modal>
  </>;
}
