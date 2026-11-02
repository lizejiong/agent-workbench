import { createContext, useContext, useEffect, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { useApi } from '../state';
import type { AgentReply, Conversation } from './types';

const fresh = (): Conversation => ({ id: crypto.randomUUID(), title: '新对话', turns: [], draft: '', updatedAt: Date.now() });
interface ChatStore {
  conversations: Conversation[]; activeId: string;
  select: (id: string) => void; create: () => void;
  draft: (id: string, text: string) => void; scope: (id: string, ids?: string[]) => void;
  send: (id: string) => Promise<void>; stop: (id: string) => void;
}
const ChatContext = createContext<ChatStore | null>(null);

/** 当前登录内的 UI 会话；不把凭据、对话或证据写入浏览器持久存储。 */
export function ChatProvider({ children }: PropsWithChildren) {
  const api = useApi();
  const [conversations, setConversations] = useState<Conversation[]>(() => [fresh()]);
  const [activeId, setActiveId] = useState(conversations[0].id);
  const current = useRef(conversations);
  const requests = useRef(new Map<string, AbortController>());
  const closed = useRef(false);
  const update = (change: (previous: Conversation[]) => Conversation[]) => {
    if (closed.current) return;
    const next = change(current.current); current.current = next; setConversations(next);
  };
  const change = (id: string, edit: (item: Conversation) => Conversation) => update(rows => rows.map(item => item.id === id ? edit(item) : item));
  useEffect(() => {
    closed.current = false;
    const pending = requests.current;
    return () => { closed.current = true; pending.forEach(controller => controller.abort()); pending.clear(); };
  }, []);
  const create = () => {
    const empty = current.current.find(item => !item.turns.length && !item.draft);
    if (empty) { change(empty.id, item => ({ ...item, knowledgeIds: undefined })); setActiveId(empty.id); return; }
    const next = fresh(); update(rows => [next, ...rows]); setActiveId(next.id);
  };
  const stop = (id: string) => {
    requests.current.get(id)?.abort();
    change(id, item => ({ ...item, turns: item.turns.map(turn => turn.status === 'pending' ? { ...turn, status: 'cancelled' } : turn) }));
  };
  const send = async (id: string) => {
    const conversation = current.current.find(item => item.id === id);
    const question = conversation?.draft.trim();
    if (!conversation || !question || question.length > 4000 || requests.current.has(id)) return;
    const controller = new AbortController(); requests.current.set(id, controller);
    const turnId = crypto.randomUUID();
    change(id, item => ({ ...item, draft: '', title: item.turns.length ? item.title : Array.from(question).slice(0, 28).join(''), updatedAt: Date.now(),
      turns: [...item.turns, { id: turnId, question, status: 'pending' }] }));
    try {
      // 后续只传服务端会话 ID；知识范围由首次委托绑定，不由模型或旧响应扩大。
      const body = conversation.sessionId
        ? { question, sessionId: conversation.sessionId }
        : { question, ...(conversation.knowledgeIds?.length ? { kbIds: conversation.knowledgeIds } : {}) };
      const reply = await api.send<AgentReply>('POST', '/api/agent/chat', body, undefined, controller.signal);
      if (controller.signal.aborted || closed.current) return;
      if (reply?.status !== 'COMPLETED' || typeof reply.answer !== 'string' || !reply.sessionId
        || !Array.isArray(reply.citations) || !Array.isArray(reply.retrievals)
        || conversation.sessionId && conversation.sessionId !== reply.sessionId) throw new Error('助手响应格式无效，请稍后重试。');
      change(id, item => ({ ...item, sessionId: reply.sessionId, updatedAt: Date.now(),
        turns: item.turns.map(turn => turn.id === turnId ? { ...turn, status: 'completed', reply } : turn) }));
    } catch (failure) {
      if (!controller.signal.aborted && !closed.current) change(id, item => ({ ...item,
        turns: item.turns.map(turn => turn.id === turnId ? { ...turn, status: 'failed', error: failure instanceof Error ? failure : new Error('暂时无法获得回复。') } : turn) }));
    } finally { if (requests.current.get(id) === controller) requests.current.delete(id); }
  };
  return <ChatContext.Provider value={{ conversations, activeId, select: setActiveId, create,
    draft: (id, draft) => change(id, item => ({ ...item, draft })),
    scope: (id, knowledgeIds) => change(id, item => item.turns.length ? item : ({ ...item, knowledgeIds })), send, stop }}>{children}</ChatContext.Provider>;
}
export function useChat() { const store = useContext(ChatContext); if (!store) throw new Error('Chat provider missing'); return store; }
