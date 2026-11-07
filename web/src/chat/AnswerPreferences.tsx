import { useEffect, useState } from 'react';
import { Alert, App, Button, Form, Modal, Select, Space, Spin } from 'antd';
import { ApiError } from '../api';
import { ErrorPanel } from '../components';
import { useApi, useResource } from '../state';

interface Preferences {
  revision: number; epoch: number;
  language: 'AUTO' | 'ZH_CN' | 'EN';
  detail: 'BALANCED' | 'CONCISE' | 'DETAILED';
  format: 'AUTO' | 'PARAGRAPHS' | 'BULLETS';
  updatedAt: string | null;
}

/** 仅在打开时读 Java；不在浏览器另存一份用户偏好。 */
export default function AnswerPreferences({ onClose }: { onClose: () => void }) {
  const api = useApi(); const { message } = App.useApp();
  const resource = useResource(signal => api.get<Preferences>('/api/agent/preferences', signal), [api]);
  const [draft, setDraft] = useState<Preferences>(); const [saving, setSaving] = useState(false);
  const [error, setError] = useState<Error>(); const [conflict, setConflict] = useState(false);
  const value = draft || resource.value;
  useEffect(() => { setDraft(undefined); setConflict(false); setError(undefined); }, [resource.value]);
  const submit = async (reset: boolean) => {
    if (!value || saving || conflict) return;
    setSaving(true); setError(undefined);
    try {
      await api.send<Preferences>(reset ? 'POST' : 'PUT', '/api/agent/preferences' + (reset ? '/reset' : ''), reset
        ? { expectedRevision: value.revision }
        : { expectedRevision: value.revision, language: value.language, detail: value.detail, format: value.format });
      void message.success(reset ? '已恢复默认回答偏好' : '回答偏好已保存');
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error('保存失败，请重新加载设置确认状态。'));
      setConflict(failure instanceof ApiError && failure.status === 409);
    } finally { setSaving(false); }
  };
  return <Modal open title="回答偏好" onCancel={() => { if (!saving) onClose(); }} closable={!saving} width={480}
    footer={<Space wrap><Button disabled={!value || saving || conflict} onClick={() => void submit(true)}>恢复默认</Button>
      <Button disabled={saving} onClick={onClose}>取消</Button><Button type="primary" loading={saving} disabled={!value || conflict || resource.loading} onClick={() => void submit(false)}>保存</Button></Space>}>
    <Space orientation="vertical" size={20} style={{ width: '100%', paddingTop: 8 }}>
      <Alert type="info" showIcon style={{ background: '#f7f7f7', borderColor: '#e5e5e5' }} title="用于之后的新回答" description="仅保存你选择的表达方式，不自动记录聊天事实；具体提问中的明确要求优先。" />
      <ErrorPanel error={error || resource.error} retry={!saving ? resource.refresh : undefined} />
      {!value && !resource.error ? <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div> : value && <Form layout="vertical" disabled={saving || conflict}>
        <Form.Item label="回答语言" htmlFor="preference-language"><Select id="preference-language" aria-label="回答语言" value={value.language}
          options={[{ value: 'AUTO', label: '自动（默认中文）' }, { value: 'ZH_CN', label: '简体中文' }, { value: 'EN', label: '英语' }]}
          onChange={language => setDraft({ ...value, language })} /></Form.Item>
        <Form.Item label="回答详略" htmlFor="preference-detail"><Select id="preference-detail" aria-label="回答详略" value={value.detail}
          options={[{ value: 'BALANCED', label: '适中' }, { value: 'CONCISE', label: '简洁' }, { value: 'DETAILED', label: '详细' }]}
          onChange={detail => setDraft({ ...value, detail })} /></Form.Item>
        <Form.Item label="回答格式" htmlFor="preference-format"><Select id="preference-format" aria-label="回答格式" value={value.format}
          options={[{ value: 'AUTO', label: '按问题需要选择' }, { value: 'PARAGRAPHS', label: '连贯段落' }, { value: 'BULLETS', label: '项目符号列表' }]}
          onChange={format => setDraft({ ...value, format })} /></Form.Item>
      </Form>}
    </Space>
  </Modal>;
}
