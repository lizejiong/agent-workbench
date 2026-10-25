import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { ApiClient } from './api';

interface Session { api: ApiClient | null; username: string; login: (username: string, api: ApiClient) => void; logout: () => void; expire: (api: ApiClient) => void }
const SessionContext = createContext<Session | null>(null);
export function SessionProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<{ username: string; api: ApiClient } | null>(null);
  const logout = useCallback(() => setSession(null), []);
  const expire = useCallback((api: ApiClient) => setSession(current => current?.api === api ? null : current), []);
  return <SessionContext.Provider value={{ api: session?.api || null, username: session?.username || '', login: (username, api) => setSession({ username, api }), logout, expire }}>{children}</SessionContext.Provider>;
}
export function useSession() { const value = useContext(SessionContext); if (!value) throw new Error('Session provider missing'); return value; }
export function useApi() { const { api } = useSession(); if (!api) throw new Error('Authentication required'); return api; }

/** Poll only after the previous request settles; abort on navigation and reject stale responses. */
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, dependencies: unknown[], poll?: (value: T) => boolean) {
  const [value, setValue] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const loader = useRef(load); loader.current = load;
  const shouldPoll = useRef(poll); shouldPoll.current = poll;
  const identity = useRef<unknown[]>([]);
  useEffect(() => {
    let closed = false; let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController;
    const newIdentity = dependencies.length !== identity.current.length || dependencies.some((v, i) => !Object.is(v, identity.current[i]));
    identity.current = [...dependencies];
    setError(undefined);
    if (newIdentity) { setLoading(true); setValue(undefined); }
    const run = async () => {
      controller = new AbortController();
      try {
        const next = await loader.current(controller.signal);
        if (closed) return;
        setValue(next); setError(undefined); setLoading(false);
        if (shouldPoll.current?.(next)) timer = setTimeout(run, document.hidden ? 30_000 : 2000);
      } catch (failure) {
        if (!closed && !controller.signal.aborted) { setError(failure instanceof Error ? failure : new Error('加载失败')); setLoading(false); }
      }
    };
    void run();
    return () => { closed = true; clearTimeout(timer); controller?.abort(); };
    // Callers specify resource identity; mutable loader refs avoid polling resets during rendering.
  }, [...dependencies, revision]);
  return { value, error, loading, refresh: () => setRevision(r => r + 1) };
}
