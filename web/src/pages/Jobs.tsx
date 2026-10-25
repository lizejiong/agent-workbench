import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Select, Space, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { friendlyError, query } from '../api';
import { useApi, useResource } from '../state';
import { activeJob, date, ErrorPanel, RefreshButton, Status, submitJob, useOperation } from '../components';
import type { DocumentRecord, Job, Page, Stage } from '../types';

export function JobDrawer({ jobId, close, doc, changed }: { jobId?: string; close: () => void; doc: DocumentRecord; changed: () => void }) {
  const api = useApi(); const op = useOperation();
  const job = useResource(signal => jobId ? api.get<Job>(`/api/ingestion-jobs/${jobId}`, signal) : Promise.resolve(null), [api, jobId], j => !!j && activeJob(j.status));
  const completed = useRef('');
  useEffect(() => { if (!job.value || activeJob(job.value.status)) return; const key = job.value.id + job.value.status; if (completed.current !== key) { completed.current = key; changed(); } }, [job.value?.id, job.value?.status, changed]);
  const stages = useResource(signal => jobId ? api.get<Page<Stage>>(`/api/ingestion-jobs/${jobId}/stages?current=1&size=100`, signal) : Promise.resolve(null), [api, jobId, job.value?.status, job.value?.stage, job.value?.attempts], () => !!job.value && activeJob(job.value.status));
  const retry = () => op.run(async () => { const result = await submitJob(api, doc, op.confirm, jobId); if (!result) return null; changed(); close(); return result; }, '已提交新的重试任务');
  const cancel = async () => { if (!await op.confirm('取消任务', '任务将停止激活新版本。已有可用版本会保留。')) return; await op.run(async () => { await api.send('POST', `/api/ingestion-jobs/${jobId}/cancel`); job.refresh(); changed(); }, '已请求取消任务'); };
  return <Drawer title="入库任务详情" open={!!jobId} onClose={() => { close(); changed(); }} size={850} destroyOnHidden>
    <ErrorPanel error={job.error} retry={job.refresh} /><ErrorPanel error={op.error} />{job.value && <>
      <Space wrap style={{ marginBottom: 20 }}><Status value={job.value.status} /><RefreshButton onClick={() => { job.refresh(); stages.refresh(); changed(); }} />{activeJob(job.value.status) && <Button danger disabled={op.busy} onClick={() => void cancel()}>取消任务</Button>}{['FAILED', 'CANCELLED'].includes(job.value.status) && <Button type="primary" loading={op.busy} onClick={() => void retry()}>重新提交</Button>}</Space>
      <Descriptions size="small" column={2} items={[
        { key: 'id', label: '任务标识', children: <Typography.Text className="compact-code" copyable>{job.value.id}</Typography.Text>, span: 2 },
        { key: 'stage', label: '当前阶段', children: job.value.stage }, { key: 'attempts', label: '尝试次数', children: `${job.value.attempts} / ${job.value.maxAttempts}` },
        { key: 'generation', label: '目标索引版本', children: job.value.targetGeneration }, { key: 'config', label: '文档配置版本', children: job.value.documentConfigRevision },
        { key: 'start', label: '开始时间', children: date(job.value.startedAt) }, { key: 'end', label: '完成时间', children: date(job.value.completedAt) },
        { key: 'next', label: '下次尝试', children: date(job.value.nextRunAt) }, { key: 'lease', label: '租约有效至', children: date(job.value.leaseUntil) },
      ]} />
      {job.value.errorCode && <Alert style={{ marginTop: 20 }} showIcon type="error" title="任务未成功完成" description={<>{friendlyError(job.value.errorCode)}<div className="compact-code">{job.value.errorCode}</div></>} />}
    </>}<Typography.Title level={5}>阶段记录</Typography.Title><p className="muted">状态与耗时来自后端持久化记录。运行时每 2 秒查询，页面隐藏后降为每 30 秒。</p><ErrorPanel error={stages.error} retry={stages.refresh} />
    <Table<Stage> size="small" loading={stages.loading} dataSource={stages.value?.records || []} rowKey={r => `${r.attempt}-${r.stage}-${r.startedAt}`} pagination={false} scroll={{ x: 600 }} columns={[
      { title: '尝试', dataIndex: 'attempt', width: 60 }, { title: '阶段', dataIndex: 'stage' }, { title: '状态', render: (_, s) => <Status value={s.status} /> },
      { title: '耗时', render: (_, s) => s.durationMs === null || s.durationMs === undefined ? '—' : `${s.durationMs} ms` },
      { title: '输入 / 输出', render: (_, s) => `${s.inputCount ?? '—'} ${s.inputUnit || ''} / ${s.outputCount ?? '—'} ${s.outputUnit || ''}` }, { title: '错误码', dataIndex: 'errorCode' },
    ]} />
  </Drawer>;
}
export default function Jobs({ doc, changed, initialJob }: { doc: DocumentRecord; changed: () => void; initialJob?: string }) {
  const api = useApi(); const op = useOperation(); const [jobId, setJobId] = useState<string | undefined>(initialJob);
  useEffect(() => { setJobId(initialJob); }, [initialJob]);
  const [current, setCurrent] = useState(1); const [status, setStatus] = useState<string>();
  const page = useResource(signal => api.get<Page<Job>>(`/api/documents/${doc.id}/ingestion-jobs` + query({ current, size: 10, status }), signal), [api, doc.id, current, status], p => p.records.some(j => activeJob(j.status)));
  const submit = () => op.run(async () => { const job = await submitJob(api, doc, op.confirm); if (job) { setJobId(job.id); page.refresh(); changed(); } });
  const columns: ColumnsType<Job> = [{ title: '任务', render: (_, j) => <Button type="link" className="compact-code" onClick={() => setJobId(j.id)}>{j.id.slice(0, 8)}</Button> }, { title: '状态', render: (_, j) => <Status value={j.status} /> }, { title: '阶段', dataIndex: 'stage' }, { title: '尝试', render: (_, j) => `${j.attempts}/${j.maxAttempts}` }, { title: '目标版本', dataIndex: 'targetGeneration' }, { title: '提交时间', render: (_, j) => date(j.createdAt) }, { title: '操作', render: (_, j) => <Button size="small" onClick={() => setJobId(j.id)}>详情</Button> }];
  return <><ErrorPanel error={op.error} /><ErrorPanel error={page.error} retry={page.refresh} /><div className="toolbar"><Space><Select value={status} allowClear placeholder="全部状态" style={{ width: 160 }} onChange={s => { setStatus(s); setCurrent(1); }} options={['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].map(s => ({ value: s, label: <Status value={s} /> }))} /><RefreshButton onClick={() => { page.refresh(); changed(); }} /></Space><Button type="primary" loading={op.busy} disabled={!doc.enabled} onClick={() => void submit()}>提交入库 / 重新入库</Button></div>
    <Table rowKey="id" columns={columns} loading={page.loading} dataSource={page.value?.records || []} scroll={{ x: 700 }} pagination={{ current, pageSize: 10, total: page.value?.total, onChange: setCurrent }} />
    <JobDrawer jobId={jobId} close={() => setJobId(undefined)} doc={doc} changed={() => { page.refresh(); changed(); }} />
  </>;
}
