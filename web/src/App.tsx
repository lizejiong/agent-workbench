import { lazy, Suspense, useState } from 'react';
import { Navigate, Route, Routes, Link, useLocation } from 'react-router-dom';
import { Button, Card, Form, Input, Layout, Menu, Space, Typography } from 'antd';
import { BookOutlined, DatabaseOutlined, LogoutOutlined, SearchOutlined, UserOutlined } from '@ant-design/icons';
import { ApiClient, basicAuthorization } from './api';
import { useSession } from './state';
import { ErrorPanel, Loading, useOperation } from './components';

const KnowledgeBases = lazy(() => import('./pages/KnowledgeBases'));
const Documents = lazy(() => import('./pages/Documents'));
const DocumentDetail = lazy(() => import('./pages/DocumentDetail'));
const Search = lazy(() => import('./pages/Search'));

function Login() {
  const session = useSession(); const op = useOperation();
  const submit = (values: { username: string; password: string }) => op.run(async () => {
    const client = new ApiClient(basicAuthorization(values.username, values.password), () => session.expire(client));
    await client.get('/api/knowledge/management');
    session.login(values.username.trim(), client);
  });
  return <div className="login"><div className="login-brand"><DatabaseOutlined /><span>知识工作台</span></div><Card className="login-card">
    <div className="eyebrow">KNOWLEDGE WORKSPACE</div><Typography.Title level={2}>登录工作区</Typography.Title><p className="muted">使用服务端已配置的账号，管理资料与检索证据。</p>
    <ErrorPanel error={op.error} /><Form layout="vertical" onFinish={values => void submit(values)} requiredMark={false}>
      <Form.Item label="账号" name="username" rules={[{ required: true, whitespace: true }]}><Input prefix={<UserOutlined />} autoComplete="username" autoFocus /></Form.Item>
      <Form.Item label="密码" name="password" rules={[{ required: true }]}><Input.Password autoComplete="current-password" /></Form.Item>
      <Button type="primary" htmlType="submit" block size="large" loading={op.busy}>登录</Button>
    </Form><p className="login-note">凭据仅保存在本次页面内存中，刷新页面后需重新登录。</p>
  </Card><div className="login-footer">资料有序 · 来源可查</div></div>;
}
export default function App() {
  const session = useSession(); const location = useLocation(); const [collapsed, setCollapsed] = useState(false);
  if (!session.api) return <Login />;
  return <Layout className="workspace"><Layout.Sider width={224} breakpoint="lg" collapsedWidth={64} collapsed={collapsed} onCollapse={setCollapsed} className="sidebar">
    <Link to="/kbs" className="brand"><DatabaseOutlined />{!collapsed && <span>知识工作台<small>KNOWLEDGE</small></span>}</Link>
    <Menu theme="dark" mode="inline" selectedKeys={[location.pathname === '/search' ? 'search' : 'kbs']} items={[
      { key: 'kbs', icon: <BookOutlined />, label: <Link to="/kbs">知识库</Link> }, { key: 'search', icon: <SearchOutlined />, label: <Link to="/search">检索测试</Link> },
    ]} /><div className="sidebar-note">{collapsed ? 'KB' : '从原始资料到可追溯证据'}</div>
  </Layout.Sider><Layout><Layout.Header className="header"><span className="header-label">资料管理 / 私有工作区</span><Space><UserOutlined /><span>{session.username}</span><Button icon={<LogoutOutlined />} type="text" onClick={session.logout}>退出</Button></Space></Layout.Header>
    <Layout.Content className="content"><Suspense fallback={<Loading loading>{null}</Loading>}><Routes>
      <Route path="/kbs" element={<KnowledgeBases />} /><Route path="/kbs/:kbId" element={<Documents />} /><Route path="/documents/:documentId" element={<DocumentDetail />} /><Route path="/search" element={<Search />} /><Route path="*" element={<Navigate to="/kbs" replace />} />
    </Routes></Suspense></Layout.Content><Layout.Footer className="footer">知识工作台 · 检索结果保留真实来源</Layout.Footer>
  </Layout></Layout>;
}
