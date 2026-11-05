import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { query } from '../api';
import { useApi } from '../state';
import type { Page } from '../types';
import type { AgentReply, ChatTurn, Conversation, StoredMessage, ToolEvent } from './types';

const fresh = (): Conversation => ({ id: crypto.randomUUID(), title: '新对话', turns: [], draft: '', updatedAt: Date.now(), loaded: true });
type RemoteConversation = { id: string; title: string; kbIds: string[]; activeAt: string };
type MessagePage = { records: StoredMessage[]; hasMore: boolean; nextBeforeSequence: number };
interface ChatStore {
  conversations: Conversation[]; activeId: string; error?: Error; listMore: boolean; listLoading: boolean;
  select: (id: string) => void; create: () => void; search: (text: string) => void; more: () => void;
  rename: (id: string, title: string) => Promise<void>; remove: (id: string) => Promise<void>;
  load: (id: string, older?: boolean) => Promise<void>;
  draft: (id: string, text: string) => void; scope: (id: string, ids?: string[]) => void;
  send: (id: string) => Promise<void>; stop: (id: string) => void;
}
const ChatContext = createContext<ChatStore | null>(null);

function turnsFromMessages(messages: StoredMessage[], conversationId: string): ChatTurn[] {
  const turns = new Map<string, ChatTurn>();
  for (const row of [...messages].sort((a, b) => a.sequence - b.sequence)) {
    let turn = turns.get(row.requestId);
    if (!turn) { turn = { id: row.requestId, requestId: row.requestId, runId: row.runId, question: '（加载更早消息可查看问题）', status: 'pending' }; turns.set(row.requestId, turn); }
    if (row.role === 'user') { turn.question = row.content; turn.messageId = row.id; }
    else {
      turn.status = row.status === 'RUNNING' ? 'pending' : row.status === 'COMPLETED' ? 'completed' : row.status === 'CANCELLED' ? 'cancelled' : 'failed';
      turn.runStatus = row.status; turn.tools = row.tools; turn.sourcesRestricted = row.sourcesRestricted; turn.persisted = true;
      turn.reply = { sessionId: conversationId, runId: row.runId, traceId: row.tools.find(t => t.traceId)?.traceId || '', status: row.status,
        answer: row.content, citations: row.citations, retrievals: row.tools.filter(t => t.status !== 'RUNNING') as AgentReply['retrievals'],
        model: row.model || '', promptVersion: row.promptVersion || '', promptHash: row.promptHash || '' };
    }
  }
  return [...turns.values()];
}

/** 浏览器只保留交互缓存；Java 是跨刷新、跨设备的权威历史。 */
export function ChatProvider({ children }: PropsWithChildren) {
  const api = useApi();
  const [conversations, setConversations] = useState<Conversation[]>(() => [fresh()]);
  const [activeId, setActiveId] = useState(conversations[0].id);
  const [error, setError] = useState<Error>(); const [listMore, setListMore] = useState(false); const [listLoading, setListLoading] = useState(false);
  const current = useRef(conversations); const closed = useRef(false);
  const selectedId = useRef(activeId); selectedId.current = activeId;
  const requests = useRef(new Map<string, AbortController>()); const stored = useRef(new Map<string, StoredMessage[]>());
  const pages = useRef(1); const filter = useRef(''); const listVersion = useRef(0);
  const update = useCallback((change: (previous: Conversation[]) => Conversation[]) => {
    if (closed.current) return; const next = change(current.current); current.current = next; setConversations(next);
  }, []);
  const change = useCallback((id: string, edit: (item: Conversation) => Conversation) => update(rows => rows.map(item => item.id === id ? edit(item) : item)), [update]);
  const loadList = useCallback(async (page = 1, search = filter.current) => {
    const version = ++listVersion.current; setListLoading(true);
    try {
      const data = await api.get<Page<RemoteConversation>>('/api/conversations' + query({ current: page, size: 30, search }));
      if (closed.current || version !== listVersion.current) return;
      pages.current = page; setListMore(page * data.size < data.total); setError(undefined);
      update(previous => {
        const remote = data.records.map(row => {
          const existing = previous.find(c => c.sessionId === row.id);
          return { ...existing, id: existing?.id || row.id, sessionId: row.id, title: row.title, knowledgeIds: row.kbIds, turns: existing?.turns || [], draft: existing?.draft || '', updatedAt: Date.parse(row.activeAt), loaded: existing?.loaded || false };
        });
        const seen = new Set(remote.map(c => c.id));
        return [...previous.filter(c => !seen.has(c.id) && (!c.sessionId || page > 1 || c.id === selectedId.current)), ...remote];
      });
    } catch (e) { if (!closed.current) setError(e instanceof Error ? e : new Error('会话列表加载失败')); }
    finally { if (!closed.current && version === listVersion.current) setListLoading(false); }
  }, [api, update]);
  const load = useCallback(async (id: string, older = false) => {
    if (closed.current) return;
    const item = current.current.find(c => c.id === id); if (!item?.sessionId || item.loading) return;
    change(id, c => ({ ...c, loading: true }));
    try {
      const data = await api.get<MessagePage>(`/api/conversations/${item.sessionId}/messages` + query({ size: 30, beforeSequence: older ? item.beforeSequence : undefined }));
      const old = stored.current.get(id) || [];
      const byId = new Map((older ? [...data.records, ...old] : [...old, ...data.records]).map(m => [m.id, m]));
      const messages = [...byId.values()]; stored.current.set(id, messages);
      change(id, c => ({ ...c, loaded: true, loading: false, turns: turnsFromMessages(messages, item.sessionId!),
        hasMore: older ? data.hasMore : c.hasMore || data.hasMore, beforeSequence: older || !c.beforeSequence ? data.nextBeforeSequence : c.beforeSequence }));
      setError(undefined);
    } catch (e) { change(id, c => ({ ...c, loading: false })); if (!closed.current) setError(e instanceof Error ? e : new Error('历史消息加载失败')); }
  }, [api, change]);
  useEffect(() => {
    closed.current = false; void loadList(); const pending = requests.current;
    return () => { closed.current = true; pending.forEach(c => c.abort()); pending.clear(); stored.current.clear(); };
  }, [loadList]);
  useEffect(() => {
    const item = current.current.find(c => c.id === activeId);
    if (item && !item.loaded) void load(item.id);
    const timer = setInterval(() => {
      const selected = current.current.find(c => c.id === activeId);
      if (selected?.turns.some(t => t.status === 'pending') && !requests.current.has(selected.id)) void load(selected.id);
    }, 1500);
    return () => clearInterval(timer);
  }, [activeId, load]);
  const create = () => {
    const empty = current.current.find(item => !item.sessionId && !item.turns.length && !item.draft);
    if (empty) { setActiveId(empty.id); return; }
    const next = fresh(); update(rows => [next, ...rows]); setActiveId(next.id);
  };
  const stop = (id: string) => {
    const item = current.current.find(c => c.id === id); const turn = item?.turns.find(t => t.status === 'pending');
    if (item?.sessionId && turn?.runId) void api.send('POST', `/api/conversations/${item.sessionId}/runs/${turn.runId}/cancel`).then(() => {
      if (!requests.current.has(id)) void load(id);
      setTimeout(() => { if (!closed.current) void load(id); }, 3500);
    }).catch(e => setError(e));
    requests.current.get(id)?.abort();
  };
  const send = async (id: string) => {
    const conversation = current.current.find(item => item.id === id); const question = conversation?.draft.trim();
    if (!conversation || !question || question.length > 4000 || requests.current.has(id) || conversation.turns.some(t => t.status === 'pending')) return;
    const controller = new AbortController(); requests.current.set(id, controller);
    const requestId = crypto.randomUUID(); const messageId = crypto.randomUUID();
    const turn: ChatTurn = { id: requestId, requestId, messageId, question, status: 'pending', tools: [] };
    change(id, c => ({ ...c, draft: '', title: c.turns.length ? c.title : Array.from(question).slice(0, 28).join(''), updatedAt: Date.now(), turns: [...c.turns, turn] }));
    let conversationId = conversation.sessionId;
    const editTurn = (edit: (turn: ChatTurn) => ChatTurn) => change(id, c => ({ ...c, turns: c.turns.map(t => t.id === requestId ? edit(t) : t) }));
    try {
      if (!conversationId) {
        const created = await api.send<RemoteConversation>('POST', '/api/conversations', { requestId: conversation.id, ...(conversation.knowledgeIds?.length ? { kbIds: conversation.knowledgeIds } : {}) }, undefined, controller.signal);
        conversationId = created.id; change(id, c => ({ ...c, sessionId: created.id, knowledgeIds: created.kbIds }));
      }
      await api.stream('/api/agent/chat/stream', { conversationId, question, requestId, messageId }, (name, data) => {
        if (name === 'meta') editTurn(t => ({ ...t, runId: String(data.runId) }));
        else if (name === 'delta') editTurn(t => ({ ...t, reply: { sessionId: conversationId!, runId: t.runId || '', traceId: '', status: 'RUNNING', answer: (t.reply?.answer || '') + String(data.text || ''), citations: [], retrievals: [], model: '', promptVersion: '', promptHash: '' } }));
        else if (name === 'tool') editTurn(t => ({ ...t, tools: [...(t.tools || []).filter(e => e.toolCallId !== data.toolCallId), data as unknown as ToolEvent] }));
        else if (name === 'error') editTurn(t => ({ ...t, persisted: data.persisted !== false, error: new Error('本轮未完成，请刷新会话确认运行及保存状态。') }));
        else if (name === 'done') editTurn(t => ({ ...t, persisted: data.persisted !== false, runStatus: data.persisted === false ? 'UNKNOWN' : String(data.status), status: data.persisted === false ? 'pending' : data.status === 'COMPLETED' ? 'completed' : data.status === 'CANCELLED' ? 'cancelled' : data.status === 'RUNNING' ? 'pending' : 'failed', reply: typeof data.answer === 'string' ? data as unknown as AgentReply : t.reply }));
      }, controller.signal);
    } catch (failure) {
      if (!closed.current) editTurn(t => ({ ...t, status: t.runId ? 'pending' : 'failed', persisted: false, runStatus: controller.signal.aborted ? 'CANCELLING' : 'UNKNOWN', error: controller.signal.aborted ? undefined : failure instanceof Error ? failure : new Error('连接中断，请查询历史状态。') }));
    } finally {
      if (requests.current.get(id) === controller) requests.current.delete(id);
      if (conversationId && !closed.current) {
        // 断连后给 Python 的短预算结算留出时间；这里只读历史，不重连或重放生成。
        if (controller.signal.aborted) await new Promise(resolve => setTimeout(resolve, 700));
        await load(id); void loadList();
        if (controller.signal.aborted) setTimeout(() => { if (!closed.current) void load(id); }, 3000);
      }
    }
  };
  const rename = async (id: string, title: string) => {
    const item = current.current.find(c => c.id === id); if (!item?.sessionId) return;
    await api.send('PATCH', `/api/conversations/${item.sessionId}`, { title }); change(id, c => ({ ...c, title }));
  };
  const remove = async (id: string) => {
    const item = current.current.find(c => c.id === id); if (!item?.sessionId) return;
    await api.send('DELETE', `/api/conversations/${item.sessionId}`); requests.current.get(id)?.abort(); stored.current.delete(id);
    const remaining = current.current.filter(c => c.id !== id); if (!remaining.length) remaining.push(fresh());
    update(() => remaining); if (activeId === id) setActiveId(remaining[0].id);
  };
  return <ChatContext.Provider value={{ conversations, activeId, error, listMore, listLoading, select: setActiveId, create,
    load, rename, remove, search: text => { filter.current = text; void loadList(1, text); }, more: () => void loadList(pages.current + 1),
    draft: (id, draft) => change(id, item => ({ ...item, draft })), scope: (id, knowledgeIds) => change(id, item => item.sessionId || item.turns.length ? item : ({ ...item, knowledgeIds })), send, stop }}>{children}</ChatContext.Provider>;
}
export function useChat() { const store = useContext(ChatContext); if (!store) throw new Error('Chat provider missing'); return store; }
