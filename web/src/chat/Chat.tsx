import { useEffect, useRef, useState } from 'react';
import { App, ConfigProvider, Dropdown, Grid, Select, Tooltip } from 'antd';
import { ArrowUpOutlined, BookOutlined, CloseOutlined, EditOutlined, EllipsisOutlined, LogoutOutlined, MenuOutlined, SearchOutlined, SettingOutlined, StopOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { allPages } from '../api';
import { ErrorPanel } from '../components';
import { useApi, useResource, useSession } from '../state';
import type { KnowledgeBase } from '../types';
import Answer from './Answer';
import { useChat } from './state';
import './chat.css';

const suggestions = ['总结知识库中的重要内容', '帮我查找具体的操作步骤', '对比两种方案的适用条件'];

export default function Chat() {
  const api = useApi(); const session = useSession(); const chat = useChat(); const { message } = App.useApp();
  const screens = Grid.useBreakpoint(); const mobile = screens.md === false;
  const [collapsed, setCollapsed] = useState(false); const [mobileOpen, setMobileOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false); const [search, setSearch] = useState('');
  const input = useRef<HTMLTextAreaElement>(null); const latestTurn = useRef<HTMLDivElement>(null);
  const sidebar = useRef<HTMLElement>(null); const toggle = useRef<HTMLButtonElement>(null);
  const active = chat.conversations.find(item => item.id === chat.activeId)!;
  const busy = active.turns.some(turn => turn.status === 'pending'); const empty = !active.turns.length;
  const knowledge = useResource(signal => allPages<KnowledgeBase>(api, '/api/kbs', signal), [api]);
  useEffect(() => {
    const field = input.current;
    if (field) { field.style.height = 'auto'; field.style.height = Math.min(field.scrollHeight + 2, 180) + 'px'; field.style.overflowY = field.scrollHeight > 180 ? 'auto' : 'hidden'; }
  }, [active.draft, active.id, mobile]);
  useEffect(() => {
    if (mobile && mobileOpen) sidebar.current?.querySelector<HTMLButtonElement>('button')?.focus();
  }, [mobile, mobileOpen]);
  useEffect(() => {
    latestTurn.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
  }, [active.id, active.turns.length]);
  const closeSidebar = () => { setMobileOpen(false); requestAnimationFrame(() => toggle.current?.focus()); };
  const newChat = () => { chat.create(); setMobileOpen(false); requestAnimationFrame(() => input.current?.focus()); };
  const submit = () => { if (!busy) { void chat.send(active.id); input.current?.focus(); } };
  const filtered = [...chat.conversations].sort((a, b) => b.updatedAt - a.updatedAt).filter(item => item.turns.length && item.title.toLowerCase().includes(search.toLowerCase()));
  const copyQuestion = async (text: string) => {
    try { await navigator.clipboard.writeText(text); void message.success('已复制问题'); }
    catch { void message.info('浏览器未允许复制，请手动选择文字。'); }
  };
  return <ConfigProvider theme={{ token: { colorPrimary: '#171717', colorInfo: '#171717', colorText: '#202020', borderRadius: 12, colorBorder: '#e5e5e5' } }}>
    <div className={`chat-shell${collapsed ? ' chat-sidebar-collapsed' : ''}${mobileOpen ? ' chat-mobile-open' : ''}`}>
      {mobileOpen && <button className="chat-sidebar-backdrop" aria-label="关闭侧栏" tabIndex={-1} onClick={closeSidebar} />}
      <aside ref={sidebar} className="chat-sidebar" aria-label="对话侧栏" role={mobile ? 'dialog' : undefined} aria-modal={mobile && mobileOpen ? true : undefined} aria-hidden={mobile && !mobileOpen} inert={mobile && !mobileOpen} onKeyDown={event => {
        if (!mobile || !mobileOpen) return;
        if (event.key === 'Escape') { event.preventDefault(); closeSidebar(); }
        if (event.key === 'Tab') {
          const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('a[href], button:not(:disabled), input')].filter(node => node.getClientRects().length);
          const first = controls[0]; const last = controls[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
      }}>
        <div className="chat-sidebar-top"><Link to="/chat" className="chat-wordmark" aria-label="Agent Workbench 聊天首页"><span className="chat-brand-symbol" aria-hidden="true">✳</span><span>Workbench</span></Link>
          <Tooltip title={mobile ? '关闭侧栏' : collapsed ? '展开侧栏' : '收起侧栏'}><button className="chat-icon-button chat-sidebar-toggle" aria-label={mobile ? '关闭侧栏' : collapsed ? '展开侧栏' : '收起侧栏'} onClick={() => mobile ? closeSidebar() : setCollapsed(!collapsed)}>{mobile ? <CloseOutlined /> : <MenuOutlined />}</button></Tooltip>
        </div>
        <nav className="chat-sidebar-actions" aria-label="聊天操作">
          <button type="button" className="chat-nav-button" onClick={newChat} title="新对话"><EditOutlined /><span>新对话</span></button>
          <button type="button" className="chat-nav-button" onClick={() => { setSearchOpen(!searchOpen); if (collapsed) setCollapsed(false); }} title="搜索本次对话"><SearchOutlined /><span>搜索对话</span></button>
          <Link className="chat-nav-button" to="/kbs" title="后台管理"><BookOutlined /><span>知识库管理</span></Link>
        </nav>
        <div className="chat-recents">
          {searchOpen && <label className="chat-history-search"><SearchOutlined /><input autoFocus aria-label="搜索本次登录的对话" placeholder="搜索对话" value={search} onChange={event => setSearch(event.target.value)} /></label>}
          <div className="chat-recents-heading">本次登录的对话</div>
          <div className="chat-conversation-list">{filtered.length ? filtered.map(item => <button key={item.id} type="button" className={`chat-conversation${item.id === active.id ? ' is-selected' : ''}`} title={item.title} onClick={() => { chat.select(item.id); setMobileOpen(false); }}><span>{item.title}</span>{item.turns.some(turn => turn.status === 'pending') && <i className="chat-running-dot" aria-label="正在回复" />}</button>) : <p className="chat-history-empty">{search ? '没有找到相关对话' : '在这里继续你的对话'}</p>}</div>
        </div>
        <div className="chat-sidebar-footer"><Dropdown trigger={['click']} menu={{ items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }], onClick: session.logout }}>
          <button type="button" className="chat-profile" aria-label={`${session.username}，账号菜单`}><span className="chat-avatar">{session.username.slice(0, 1).toUpperCase()}</span><span className="chat-profile-copy"><strong>{session.username}</strong><small>个人工作空间</small></span><EllipsisOutlined /></button>
        </Dropdown></div>
      </aside>

      <main className="chat-main" inert={mobile && mobileOpen} aria-hidden={mobile && mobileOpen}>
        <header className="chat-header"><div className="chat-header-left"><button ref={toggle} type="button" className="chat-icon-button chat-main-toggle" aria-label="展开侧栏" onClick={() => mobile ? setMobileOpen(true) : setCollapsed(false)}><MenuOutlined /></button><span className="chat-assistant-name">知识助手</span></div>
          <Link to="/kbs" className="chat-admin-link"><SettingOutlined /><span>后台管理</span></Link>
        </header>
        <div className={`chat-stage${empty ? ' is-empty' : ''}`}>
          {empty ? <section className="chat-welcome"><h1>今天想了解什么？</h1><p>查找资料、梳理问题，从这里开始。</p></section>
            : <div className="chat-messages" role="region" aria-label="聊天消息" tabIndex={0}><div className="chat-message-column">
              {active.turns.map((turn, index) => <div key={turn.id} ref={index === active.turns.length - 1 ? latestTurn : undefined} className="chat-turn">
                <article className="chat-user-message" aria-label="你的问题"><div>{turn.question}</div><button type="button" className="chat-question-copy" aria-label="复制问题" onClick={() => void copyQuestion(turn.question)}>复制</button></article>
                <article className="chat-assistant-message" aria-label="助手回复">
                  {turn.status === 'pending' ? <div className="chat-thinking" role="status"><span className="chat-thinking-dot" /><span>正在处理你的问题</span></div>
                    : turn.status === 'cancelled' ? <p className="chat-stopped" role="status">已停止等待回复</p>
                      : turn.status === 'failed' ? <div className="chat-send-error" role="alert"><ErrorPanel error={turn.error} /><button type="button" onClick={() => { chat.draft(active.id, turn.question); input.current?.focus(); }}>重新编辑问题</button></div>
                        : turn.reply ? <Answer reply={turn.reply} /> : null}
                </article>
              </div>)}
            </div></div>}
          <div className="chat-composer-wrap"><form className={`chat-composer${busy ? ' is-busy' : ''}`} onSubmit={event => { event.preventDefault(); submit(); }}>
            <textarea ref={input} rows={1} aria-label="发送消息" placeholder="向知识助手提问" maxLength={4000} value={active.draft} onChange={event => chat.draft(active.id, event.target.value)} onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) { event.preventDefault(); submit(); }
            }} />
            <div className="chat-composer-tools"><div className="chat-scope" title={empty ? '选择本次对话使用的知识库' : '当前对话的知识范围已固定，新对话可调整'}><BookOutlined /><Select mode="multiple" variant="borderless" aria-label="知识库范围" placeholder="全部知识库" maxTagCount="responsive" allowClear value={active.knowledgeIds || []} disabled={!empty || knowledge.loading || !!knowledge.error} loading={knowledge.loading} options={knowledge.value?.map(kb => ({ value: kb.id, label: kb.name }))} onChange={ids => chat.scope(active.id, ids.length ? ids : undefined)} popupMatchSelectWidth={260} notFoundContent="暂无可选知识库" /></div>
              <div className="chat-composer-submit">{active.draft.length > 3600 && <span className="chat-character-count">{active.draft.length}/4000</span>}
                {busy ? <button type="button" className="chat-send-button" aria-label="停止回复" onClick={() => chat.stop(active.id)}><StopOutlined /></button>
                  : <button type="submit" className="chat-send-button" aria-label="发送问题" disabled={!active.draft.trim()}><ArrowUpOutlined /></button>}
              </div>
            </div>
          </form>
            {knowledge.error && <div className="chat-knowledge-error" role="status">知识库列表暂不可用，检索范围仍由服务端验证。<button type="button" onClick={knowledge.refresh}>重新加载</button></div>}
            {!empty && <p className="chat-composer-caption">回答可能存在偏差，重要信息请核对来源。</p>}
          </div>
          {empty && <div className="chat-suggestions" aria-label="提问示例">{suggestions.map(text => <button type="button" key={text} onClick={() => { chat.draft(active.id, text); input.current?.focus(); }}>{text}</button>)}</div>}
        </div>
        {empty && <footer className="chat-bottom-note">回答可能存在偏差，重要信息请核对来源。</footer>}
      </main>
    </div>
  </ConfigProvider>;
}
