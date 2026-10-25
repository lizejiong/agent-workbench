import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Switch, Tag, Typography } from 'antd';
import { FileTextOutlined, SearchOutlined } from '@ant-design/icons';
import { allPages } from '../api';
import { useApi, useResource } from '../state';
import { ErrorPanel, Nothing, PageTitle, Source, useOperation } from '../components';
import type { Capabilities, DocumentRecord, KnowledgeBase, Retrieval } from '../types';

export default function Search() {
  const api = useApi(); const [params] = useSearchParams(); const [form] = Form.useForm(); const op = useOperation();
  const [result, setResult] = useState<{ query: string; data: Retrieval }>(); const [pending, setPending] = useState(false); const [error, setError] = useState<Error>(); const controller = useRef<AbortController | null>(null);
  const bases = useResource(signal => allPages<KnowledgeBase>(api, '/api/kbs', signal), [api]);
  const caps = useResource(signal => api.get<Capabilities>('/api/knowledge/search-capabilities', signal), [api]);
  const selected: string[] = Form.useWatch('kbIds', form) || []; const signature = selected.join(','); const strategy = Form.useWatch('strategy', form); const rerank = Form.useWatch('rerank', form);
  const documents = useResource(async signal => {
    const rows: DocumentRecord[] = [];
    // Bound selector HTTP fan-out; the API independently enforces every selected resource's ownership.
    for (let offset = 0; offset < selected.length; offset += 4) {
      const pages = await Promise.all(selected.slice(offset, offset + 4).map(id => allPages<DocumentRecord>(api, `/api/kbs/${id}/documents`, signal)));
      rows.push(...pages.flat());
    }
    return rows;
  }, [api, signature]);
  useEffect(() => { form.setFieldValue('documentIds', []); }, [form, signature]);
  useEffect(() => () => { controller.current?.abort(); }, [api]);
  const search = async () => {
    const values = await form.validateFields(); controller.current?.abort(); const request = new AbortController(); controller.current = request; setPending(true); setError(undefined); setResult(undefined);
    const payload = { ...values, query: values.query.trim(), ...(values.strategy !== 'VECTOR' ? { minScore: undefined } : {}), ...(!values.rerank ? { minRerankScore: undefined } : {}), documentIds: values.documentIds?.length ? values.documentIds : undefined };
    try { const data = await api.send<Retrieval>('POST', '/api/knowledge/retrieve', payload, undefined, request.signal); if (!request.signal.aborted) setResult({ query: payload.query, data }); }
    catch (failure) { if (!request.signal.aborted) setError(failure instanceof Error ? failure : new Error('检索失败')); }
    finally { if (!request.signal.aborted) setPending(false); }
  };
  const labels: Record<string, string> = { DISABLED: '精排未启用', SKIPPED_EMPTY: '无候选，跳过精排', APPLIED: '已精排', FALLBACK_FUSION: '精排失败，保留融合顺序', THRESHOLD_UNAVAILABLE: '精排分不可用，无法应用证据阈值' };
  return <><PageTitle eyebrow="RETRIEVAL / EVIDENCE" title="检索测试" description="检查资料能否被找到，核对命中正文、真实来源与评分依据。" />
    <ErrorPanel error={bases.error} retry={bases.refresh} /><ErrorPanel error={caps.error} retry={caps.refresh} /><ErrorPanel error={op.error} />
    <div className="search-layout"><Card className="search-scope" title={<Space><SearchOutlined />检索范围</Space>}>
      <div className="caps-line"><Tag color="cyan">向量检索</Tag><Tag color={caps.value?.keywordConfigured ? 'cyan' : undefined}>BM25 {caps.value?.keywordConfigured ? '已配置' : '未配置'}</Tag><Tag color={caps.value?.rerankConfigured ? 'cyan' : undefined}>精排 {caps.value?.rerankConfigured ? '已配置' : '未配置'}</Tag></div>
      <Form form={form} layout="vertical" initialValues={{ kbIds: params.get('kb') ? [params.get('kb')] : [], documentIds: [], strategy: 'AUTO', topK: 5, rerank: false }} onFinish={() => void search().catch(() => {})}>
        <Form.Item name="kbIds" label="知识库" rules={[{ required: true, type: 'array', min: 1, max: 16, message: '请选择 1–16 个知识库' }]}><Select mode="multiple" showSearch optionFilterProp="label" placeholder="选择知识库" maxTagCount="responsive" loading={bases.loading} options={bases.value?.map(b => ({ value: b.id, label: b.name }))} /></Form.Item>
        <Form.Item name="documentIds" label="文档（可选）" rules={[{ type: 'array', max: 100, message: '最多选择 100 份文档' }]}><Select mode="multiple" showSearch optionFilterProp="label" placeholder="默认所选库内全部文档" loading={documents.loading} maxTagCount="responsive" disabled={!selected.length} options={documents.value?.map(d => ({ value: d.id, label: d.name + (!d.enabled ? '（已禁用）' : '') }))} /></Form.Item><ErrorPanel error={documents.error} retry={documents.refresh} />
        <Form.Item name="query" label="检索问题" rules={[{ required: true, whitespace: true, max: 4000 }]}><Input.TextArea rows={4} maxLength={4000} placeholder="输入问题、术语或编号…" /></Form.Item>
        <div className="form-grid"><Form.Item name="strategy" label="策略"><Select options={[{ value: 'AUTO', label: '自动' }, { value: 'VECTOR', label: '向量' }, { value: 'HYBRID', label: '混合', disabled: !caps.value?.keywordConfigured }, { value: 'KEYWORD', label: '关键词', disabled: !caps.value?.keywordConfigured }]} /></Form.Item><Form.Item name="topK" label="返回数量" rules={[{ required: true }]}><InputNumber min={1} max={50} precision={0} /></Form.Item></div>
        <Form.Item name="rerank" label="使用精排" valuePropName="checked"><Switch disabled={!caps.value?.rerankConfigured} /></Form.Item>
        {strategy === 'VECTOR' && <Form.Item name="minScore" label="最低余弦相似度（可选）"><InputNumber min={-1} max={1} step={0.05} style={{ width: '100%' }} /></Form.Item>}
        {rerank && <Form.Item name="minRerankScore" label="最低精排分（可选）" tooltip="精排失败时无法验证阈值，后端将返回空证据并说明原因。"><InputNumber step={0.05} style={{ width: '100%' }} /></Form.Item>}
        <Button type="primary" htmlType="submit" block loading={pending} icon={<SearchOutlined />} disabled={!bases.value?.length}>检索证据</Button>
      </Form><p className="muted">仅返回检索证据，不生成答案。不同分数具有不同含义，不直接比较。不同嵌入空间请分开检索。</p>
    </Card><div><ErrorPanel error={error} />{!result ? <div className="search-empty"><Nothing text={pending ? '正在检索真实索引…' : '选择范围并输入问题，检查资料的召回效果'} /></div> : <>
      <div className="result-head"><div><Typography.Title level={4} style={{ margin: 0 }}>{result.data.evidence.length} 条检索证据</Typography.Title><span className="muted">问题：{result.query}</span></div><Tag color="cyan">实际策略 {result.data.strategy}</Tag></div>
      <div className="caps-line"><Tag>{labels[result.data.rerankStatus] || result.data.rerankStatus}</Tag>{result.data.channels.map(c => <Tag key={c.channel} color={c.status === 'SUCCEEDED' ? 'success' : 'warning'}>{c.channel} · {c.status === 'SUCCEEDED' ? `${c.candidates} 候选` : c.status === 'TIMEOUT' ? '通道超时' : '通道失败'}{c.errorCode ? ` / ${c.errorCode}` : ''}</Tag>)}</div>
      {result.data.rerankStatus === 'FALLBACK_FUSION' && <Alert showIcon type="warning" title="精排不可用，结果按融合顺序返回。" />}{result.data.refillLimitReached && <Alert type="info" title="已达到补召回上限，返回数量可能少于目标数量。" />}
      {!result.data.evidence.length && <Card><Nothing text={result.data.rerankStatus === 'THRESHOLD_UNAVAILABLE' ? '精排分不可用，无法满足所设证据阈值' : '没有符合当前范围与阈值的证据'} /></Card>}
      <div className="evidence-grid">{result.data.evidence.map((hit, index) => <Card key={hit.chunkId} size="small" title={<Space><Tag color="cyan">{index + 1}</Tag><Link className="evidence-title" to={`/documents/${hit.documentId}?tab=chunks`}>{hit.documentName}</Link></Space>}>
        <Source metadata={hit.sourceMetadata} /><pre className="text-body evidence-content">{hit.content}</pre><div className="evidence-score"><Space wrap><span>{hit.scoreType}：{hit.score.toFixed(6)}</span>{hit.fusionScore !== null && hit.fusionScore !== undefined && <span>融合分：{hit.fusionScore.toFixed(6)}</span>}{hit.rerankScore !== null && hit.rerankScore !== undefined && <span>精排分：{hit.rerankScore.toFixed(6)}</span>}</Space></div>
        <div className="caps-line" style={{ marginTop: 10 }}>{hit.channelScores.map(c => <Tag key={c.channel}>{c.channel} #{c.rank} · {c.scoreType} {c.score.toFixed(4)}</Tag>)}</div><div className="chunk-footer"><Typography.Text type="secondary" className="compact-code" copyable>{hit.chunkId}</Typography.Text><Button size="small" icon={<FileTextOutlined />} onClick={() => void op.run(() => api.download(`/api/documents/${hit.documentId}/file`, hit.documentName))}>原文件</Button></div><p className="muted">Chunk {hit.chunkIndex} · 索引版本 {hit.generation}</p>
      </Card>)}</div>
    </>}</div></div>
  </>;
}
