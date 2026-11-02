import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Navigate, Route, Routes, Link, useLocation, useNavigate } from 'react-router-dom';
import { Button, Card, Drawer, Dropdown, Form, Grid, Input, Layout, Menu, Typography } from 'antd';
import type { InputRef } from 'antd';
import { CommentOutlined, DatabaseOutlined, DoubleLeftOutlined, DoubleRightOutlined, DownOutlined, GithubOutlined, MenuOutlined, SearchOutlined } from '@ant-design/icons';
import { ApiClient, basicAuthorization, query } from './api';
import { useSession } from './state';
import { ErrorPanel, Loading, useOperation } from './components';
import { ChatProvider } from './chat/state';

declare const __LOGIN_DEFAULTS__: { username: string; password: string };

const KnowledgeBases = lazy(() => import('./pages/KnowledgeBases'));
const Documents = lazy(() => import('./pages/Documents'));
const DocumentDetail = lazy(() => import('./pages/DocumentDetail'));
const Search = lazy(() => import('./pages/Search'));
const Chat = lazy(() => import('./chat/Chat'));
const repository = 'https://github.com/lizejiong/agent-workbench';

function Brand({ compact = false }: { compact?: boolean }) {
  return <><img className="brand-mark" src="/favicon.svg" alt="" />{!compact && <span className="brand-name">Agent Workbench</span>}</>;
}

function Login() {
  const session = useSession(); const op = useOperation();
  const navigate = useNavigate();
  const submit = (values: { username: string; password: string }) => op.run(async () => {
    const client = new ApiClient(basicAuthorization(values.username, values.password), () => session.expire(client));
    await client.get('/api/knowledge/management');
    session.login(values.username.trim(), client);
    navigate('/chat', { replace: true });
  });
  return <div className="login">
    <section className="login-story">
      <div className="login-brand"><Brand /></div>
      <div className="login-story-content"><div className="eyebrow">YOUR AGENT, GROUNDED IN KNOWLEDGE</div>
        <h1>让每一份资料，<br />成为可靠的知识。</h1><p>连接模型、整理资料、验证检索。<br />在一个工作空间里，为智能体建立知识基础。</p>
      </div><div className="login-story-footer">Agent Workbench <span>模型 · 知识 · 智能体</span></div>
    </section>
    <section className="login-form-area"><Card className="login-card" variant="borderless">
      <div className="login-mobile-brand"><Brand /></div><span className="section-kicker">开始工作</span><Typography.Title level={2}>欢迎回到工作台</Typography.Title><p className="muted">使用服务端已配置的账号登录。</p>
      <ErrorPanel error={op.error} /><Form layout="vertical" initialValues={__LOGIN_DEFAULTS__} onFinish={values => void submit(values)} requiredMark={false} size="large">
        <Form.Item label="账号" name="username" rules={[{ required: true, whitespace: true, message: '请输入账号' }]}><Input placeholder="请输入账号" autoComplete="username" autoFocus /></Form.Item>
        <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}><Input.Password placeholder="请输入密码" autoComplete="current-password" /></Form.Item>
        <Button type="primary" htmlType="submit" block size="large" loading={op.busy}>登录工作台</Button>
      </Form><p className="login-note">凭据仅用于本次会话，刷新页面后需重新登录。</p>
    </Card><div className="login-footer">AGENT WORKBENCH · 从资料到证据</div></section>
  </div>;
}

function Workspace() {
  const session = useSession(); const location = useLocation(); const navigate = useNavigate(); const screens = Grid.useBreakpoint();
  const mobile = screens.md === false;
  const [collapsed, setCollapsed] = useState(false); const [navOpen, setNavOpen] = useState(false);
  const searchRef = useRef<InputRef>(null); const [search, setSearch] = useState('');
  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); searchRef.current?.focus(); } };
    window.addEventListener('keydown', focusSearch); return () => window.removeEventListener('keydown', focusSearch);
  }, []);
  useEffect(() => { setNavOpen(false); }, [location.pathname]);
  const compact = collapsed && !mobile;
  const navigation = <div className="navigation-inner">
    <Link to="/kbs" className="brand" aria-label="Agent Workbench 首页" onClick={() => setNavOpen(false)}><Brand compact={compact} /></Link>
    <Menu theme="dark" mode="inline" selectedKeys={[location.pathname === '/search' ? 'search' : 'kbs']} inlineCollapsed={compact} onClick={() => setNavOpen(false)} items={[
      { key: 'chat', icon: <CommentOutlined />, label: <Link to="/chat">返回聊天</Link> },
      { key: 'kbs', icon: <DatabaseOutlined />, label: <Link to="/kbs">知识库管理</Link> },
      { key: 'search', icon: <SearchOutlined />, label: <Link to="/search">检索测试</Link> },
    ]} />
    <div className="sidebar-bottom"><Button type="text" className="sidebar-project" icon={<GithubOutlined />} href={repository} target="_blank" rel="noopener noreferrer">{!compact && '项目源码'}</Button>
      {!mobile && <Button type="text" className="sidebar-collapse" aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'} icon={collapsed ? <DoubleRightOutlined /> : <DoubleLeftOutlined />} onClick={() => setCollapsed(value => !value)}>{!compact && '收起侧边栏'}</Button>}
    </div>
  </div>;
  return <Layout className="workspace">
    {mobile ? <Drawer title="工作空间导航" placement="left" size={232} open={navOpen} onClose={() => setNavOpen(false)} className="mobile-navigation">{navigation}</Drawer> : <Layout.Sider width={208} collapsedWidth={64} collapsed={collapsed} trigger={null} className="sidebar">{navigation}</Layout.Sider>}
    <Layout className="workspace-main"><Layout.Header className="header">
      <div className="header-start"><Button type="text" className="header-menu-button" icon={<MenuOutlined />} aria-label={mobile ? '打开导航' : collapsed ? '展开导航' : '收起导航'} onClick={() => mobile ? setNavOpen(true) : setCollapsed(value => !value)} />
        <Input ref={searchRef} className="header-search" suffix={<kbd>Ctrl K</kbd>} placeholder="搜索知识库…" aria-label="全局搜索知识库" value={search} onChange={event => setSearch(event.target.value)} onPressEnter={() => { navigate('/kbs' + query({ name: search.trim() })); searchRef.current?.blur(); }} />
      </div>
      <div className="header-actions">
        <Dropdown menu={{ items: [{ key: 'logout', label: '退出登录' }], onClick: session.logout }} trigger={['click']}>
          <Button type="text" className="account-button" aria-label={`${session.username}，账号菜单`}><span className="account-name">{session.username}</span><DownOutlined className="account-chevron" /></Button>
        </Dropdown>
      </div>
    </Layout.Header>
      <Layout.Content className="content"><Suspense fallback={<Loading loading>{null}</Loading>}><Routes>
        <Route path="/kbs" element={<KnowledgeBases />} /><Route path="/kbs/:kbId" element={<Documents />} /><Route path="/documents/:documentId" element={<DocumentDetail />} /><Route path="/search" element={<Search />} /><Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes></Suspense></Layout.Content>
    </Layout>
  </Layout>;
}

export default function App() {
  return useSession().api ? <ChatProvider><Suspense fallback={<Loading loading>{null}</Loading>}><Routes>
    <Route path="/chat" element={<Chat />} />
    <Route path="/" element={<Navigate to="/chat" replace />} />
    <Route path="/*" element={<Workspace />} />
  </Routes></Suspense></ChatProvider> : <Login />;
}
