import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { App, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { ArrowRightOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { date, ErrorPanel, ModelInfo, PageTitle, RefreshButton, SummaryCard, useOperation } from '../components';
import type { KnowledgeBase, ModelProfile, Page } from '../types';

export default function KnowledgeBases() {
  const api = useApi(); const navigate = useNavigate(); const { message } = App.useApp();
  const [current, setCurrent] = useState(1); const [size, setSize] = useState(10); const [name, setName] = useState('');
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
    { title: '知识库', dataIndex: 'name', render: (_, b) => <div><Link className="row-title" to={`/kbs/${b.id}`}>{b.name}</Link><div className="muted ellipsis">{b.description || '暂无描述'}</div></div> },
    { title: '嵌入模型', render: (_, b) => <ModelInfo model={b.embeddingModelId || '未绑定'} provider={b.embeddingProvider || '—'} dimension={b.embeddingDimension || 0} />, responsive: ['sm'] },
    { title: '文档', dataIndex: 'documentCount', width: 90 },
    { title: '创建时间', render: (_, b) => date(b.createdAt), responsive: ['lg'] },
    { title: '操作', width: 210, render: (_, b) => <Space><Button type="link" size="small" onClick={() => navigate(`/kbs/${b.id}`)}>管理</Button><Button type="link" size="small" onClick={() => show(b)}>重命名</Button><Button type="link" danger size="small" disabled={op.busy} onClick={() => void remove(b)}>删除</Button></Space> },
  ];
  return <>
    <PageTitle eyebrow="WORKSPACE / KNOWLEDGE" title="知识库" description="集中管理原始资料，让每一次检索都有据可查。" actions={<><RefreshButton onClick={page.refresh} /><Button type="primary" icon={<PlusOutlined />} onClick={() => show('new')}>创建知识库</Button></>} />
    <div className="summary-grid"><SummaryCard title="知识库" value={page.value?.total ?? '—'} detail={name ? '当前名称筛选结果' : '当前账号可访问的知识库'} /><SummaryCard title="本页文档" value={page.value?.records.reduce((sum, b) => sum + b.documentCount, 0) ?? '—'} detail="包含已禁用文档" /><Card size="small" className="quick-start"><Tag color="cyan">工作流</Tag><p>上传资料 → 提交入库 → 查看证据</p><Button type="link" icon={<ArrowRightOutlined />} onClick={() => navigate('/search')}>打开检索测试</Button></Card></div>
    <ErrorPanel error={op.error} /><ErrorPanel error={models.error} retry={models.refresh} /><ErrorPanel error={page.error} retry={page.refresh} />
    <Card className="table-card" title={<Space><SearchOutlined /><Input.Search placeholder="搜索知识库名称" allowClear aria-label="搜索知识库名称" onSearch={value => { setName(value); setCurrent(1); }} /></Space>} extra={<Typography.Text type="secondary">私有工作区</Typography.Text>}>
      <Table rowKey="id" columns={columns} dataSource={page.value?.records || []} loading={page.loading} locale={{ emptyText: name ? '没有匹配的知识库' : '创建第一个知识库，开始整理资料' }}
        pagination={{ current, pageSize: size, total: page.value?.total, showSizeChanger: true, showTotal: total => `共 ${total} 个`, onChange: (p, s) => { setCurrent(p); setSize(s); } }} />
    </Card>
    <Modal title={editing === 'new' ? '创建知识库' : '编辑知识库'} open={editing !== null} onCancel={() => setEditing(null)} onOk={() => void save().catch(() => {})} okText="保存" cancelText="取消" confirmLoading={op.busy} forceRender>
      <Form form={form} layout="vertical" preserve={false}><Form.Item label="名称" name="name" rules={[{ required: true, whitespace: true, max: 128 }]}><Input maxLength={128} autoFocus /></Form.Item>
        <Form.Item label="描述" name="description"><Input.TextArea rows={3} maxLength={2000} showCount /></Form.Item>
        {editing === 'new' && <Form.Item label="嵌入模型" name="embeddingProfileId" rules={[{ required: true }]} extra="模型来自服务端配置。创建后模型绑定固定，迁移需建立新知识库。"><Select loading={models.loading} options={models.value?.map(m => ({ value: m.id, label: `${m.modelName} · ${m.provider} · ${m.dimension} 维` }))} /></Form.Item>}
        <ErrorPanel error={op.error} />
      </Form>
    </Modal>
  </>;
}
