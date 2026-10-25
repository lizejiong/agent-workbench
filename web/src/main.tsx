import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp, Button, Card, ConfigProvider, Typography } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { HashRouter } from 'react-router-dom';
import { SessionProvider } from './state';
import App from './App';
import './styles.css';

class PageBoundary extends React.Component<React.PropsWithChildren, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <div className="login"><Card className="login-card"><Typography.Title level={3}>页面需要重新加载</Typography.Title><p>页面已更新或加载失败，请刷新后重新登录。</p><Button type="primary" onClick={() => window.location.reload()}>刷新并重新登录</Button></Card></div>;
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#0f766e', colorInfo: '#0f766e', borderRadius: 8, fontFamily: 'Inter, "Segoe UI", "Microsoft YaHei", sans-serif', colorBgLayout: '#f4f6f8' } }}>
    <AntApp><PageBoundary><SessionProvider><HashRouter><App /></HashRouter></SessionProvider></PageBoundary></AntApp>
  </ConfigProvider></React.StrictMode>,
);
