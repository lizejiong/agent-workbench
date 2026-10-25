import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, Space, Switch, Table, Typography } from 'antd';
import { query } from '../api';
import { useApi, useResource } from '../state';
import { activeRefresh, date, ErrorPanel, RefreshButton, Status, useOperation } from '../components';
import { JobDrawer } from './Jobs';
import type { DocumentRecord, Page, RefreshRun, Schedule } from '../types';

export default function Refresh({ doc, changed }: { doc: DocumentRecord; changed: () => void }) {
  const api = useApi(); const op = useOperation(); const [form] = Form.useForm(); const [current, setCurrent] = useState(1); const [jobId, setJobId] = useState<string>();
  const schedule = useResource(signal => api.get<Schedule>(`/api/documents/${doc.id}/refresh-schedule`, signal), [api, doc.id]);
  const runs = useResource(signal => api.get<Page<RefreshRun>>(`/api/documents/${doc.id}/refresh-runs` + query({ current, size: 10 }), signal), [api, doc.id, current], p => p.records.some(r => activeRefresh(r.status)));
  const completed = useRef('');
  useEffect(() => { const latest = runs.value?.records[0]; if (!latest || activeRefresh(latest.status)) return; const key = latest.id + latest.status; if (completed.current !== key) { completed.current = key; changed(); schedule.refresh(); } }, [runs.value, changed]);
  useEffect(() => { if (schedule.value) form.setFieldsValue({ enabled: schedule.value.enabled, cron: schedule.value.cron || '0 0 9 * * *', timezone: schedule.value.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai' }); }, [form, schedule.value]);
  const save = async () => { const values = await form.validateFields(); await op.run(async () => { await api.send('PUT', `/api/documents/${doc.id}/refresh-schedule`, { ...values, version: schedule.value?.version }); schedule.refresh(); }, '刷新设置已保存'); };
  const refresh = () => op.run(async () => {
    const fresh = await api.get<DocumentRecord>(`/api/documents/${doc.id}`); const request: { requestId: string; confirmOverwriteEdited?: boolean; expectedDocumentVersion?: number } = { requestId: crypto.randomUUID() };
    if (fresh.hasManualChanges) { if (!await op.confirm('刷新可能覆盖人工维护', '远程内容发生变化时会重新入库，覆盖当前人工正文、新增、删除和 Chunk 独立启停设置。是否继续？', true)) return null; request.confirmOverwriteEdited = true; request.expectedDocumentVersion = fresh.version; }
    await api.send('POST', `/api/documents/${doc.id}/refresh`, request); runs.refresh(); schedule.refresh(); changed();
  }, '刷新任务已提交');
  return <><ErrorPanel error={op.error} /><ErrorPanel error={schedule.error} retry={schedule.refresh} /><Alert type="info" showIcon title="未变化的来源会跳过解析和向量化。远程内容成功入库后才会更新文件与变化检测基线。" />
    <Card size="small" title="自动刷新" extra={<RefreshButton onClick={() => { schedule.refresh(); runs.refresh(); changed(); }} />} style={{ marginBottom: 24 }}>
      <Form form={form} layout="vertical"><Form.Item label="启用定时刷新" name="enabled" valuePropName="checked"><Switch disabled={!doc.enabled} /></Form.Item><div className="form-grid"><Form.Item label="Cron（6 字段，含秒）" name="cron" rules={[{ required: true, whitespace: true }]} extra="示例：0 0 9 * * * 表示每天 09:00"><Input placeholder="0 0 9 * * *" /></Form.Item><Form.Item label="时区" name="timezone" rules={[{ required: true, whitespace: true }]} extra="使用 IANA 时区，如 Asia/Shanghai"><Input placeholder="Asia/Shanghai" /></Form.Item></div><Space wrap><Button type="primary" disabled={!schedule.value} loading={op.busy} onClick={() => void save().catch(() => {})}>保存刷新设置</Button><Button disabled={op.busy || !doc.enabled} onClick={() => void refresh()}>立即刷新</Button></Space></Form>
      {schedule.value && <div className="status-detail"><Space wrap><Status value={schedule.value.lastStatus} /><span>下次执行：{date(schedule.value.nextRunAt)}</span><span>连续失败：{schedule.value.consecutiveFailures}</span></Space>{schedule.value.lastReason && <p className="compact-code">结果：{schedule.value.lastReason}</p>}</div>}
      <Typography.Text type="secondary">时间按当前浏览器时区显示；Cron 按上方显式时区执行。禁用文档后调度不会发布新版本。</Typography.Text>
    </Card><Typography.Title level={5}>刷新记录</Typography.Title><ErrorPanel error={runs.error} retry={runs.refresh} />
    <Table<RefreshRun> rowKey="id" loading={runs.loading} dataSource={runs.value?.records || []} scroll={{ x: 650 }} pagination={{ current, pageSize: 10, total: runs.value?.total, onChange: setCurrent }} columns={[
      { title: '状态', render: (_, r) => <Status value={r.status} /> }, { title: '触发方式', render: (_, r) => r.triggerType === 'MANUAL' ? '手动' : '定时' },
      { title: '尝试', render: (_, r) => `${r.attempts}/${r.maxAttempts}` }, { title: '开始时间', render: (_, r) => date(r.startedAt) }, { title: '耗时', render: (_, r) => r.durationMs === undefined || r.durationMs === null ? '—' : `${r.durationMs} ms` },
      { title: '结果', dataIndex: 'reason' }, { title: '入库任务', render: (_, r) => r.jobId ? <Button type="link" size="small" onClick={() => setJobId(r.jobId)}>查看任务</Button> : '—' },
    ]} /><JobDrawer jobId={jobId} close={() => setJobId(undefined)} doc={doc} changed={() => { runs.refresh(); schedule.refresh(); changed(); }} />
  </>;
}
