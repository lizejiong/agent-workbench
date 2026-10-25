import { afterEach, describe, expect, it, vi } from 'vitest';
import { allPages, ApiClient, ApiError, basicAuthorization, downloadName, query } from './api';

const ok = (data: unknown) => new Response(JSON.stringify({ code: '0', data }), { headers: { 'Content-Type': 'application/json' } });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
describe('authenticated same-origin API', () => {
  it('encodes UTF-8 credentials and rejects ambiguous account names', () => {
    expect(basicAuthorization(' 张三 ', '密码')).toBe('Basic ' + Buffer.from('张三:密码').toString('base64'));
    expect(() => basicAuthorization('a:b', 'secret')).toThrow(); expect(() => basicAuthorization('a', '')).toThrow();
  });
  it('preserves explicit false and zero in query filters', () => { expect(query({ enabled: false, count: 0, name: '', absent: undefined })).toBe('?enabled=false&count=0'); });
  it('preserves the original UTF-8 filename and extension from authorized download headers', () => { expect(downloadName("attachment; filename*=UTF-8''%E6%89%8B%E5%86%8C.md", 'display name')).toBe('手册.md'); });
  it('rejects filename traversal and handles malformed encodings without leaking paths', () => { expect(downloadName('attachment; filename="../../source.md"', 'fallback')).toBe('.._.._source.md'); expect(downloadName("attachment; filename*=UTF-8''%zz", 'fallback.md')).toBe('fallback.md'); });
  it('attaches credentials to a bounded same-origin request and unwraps the envelope', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ id: 'kb' })); vi.stubGlobal('fetch', fetcher);
    expect(await new ApiClient('Basic test').get('/api/kbs')).toEqual({ id: 'kb' });
    const options = fetcher.mock.calls[0][1]; expect(options.headers.get('Authorization')).toBe('Basic test'); expect(options.headers.get('X-Request-Id')).toMatch(/^web-/);
    expect(options.redirect).toBe('error'); expect(options.credentials).toBe('omit'); expect(options.cache).toBe('no-store');
  });
  it.each(['https://foreign.example/api/kbs', '//foreign.example/api/kbs', '/api/../secret', '/api/%2e%2e/secret', '/api/a%2fb', '/api/a\\b'])('never sends credentials to invalid path %s', async path => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher); await expect(new ApiClient('Basic test').get(path)).rejects.toThrow('无效'); expect(fetcher).not.toHaveBeenCalled();
  });
  it('allows escaped user values in a query without treating them as paths', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok([])); vi.stubGlobal('fetch', fetcher); await new ApiClient('Basic test').get('/api/kbs?name=a%2Fb'); expect(fetcher).toHaveBeenCalledOnce();
  });
  it('does not expose backend messages or internal storage locations on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'KB_VERSION_CONFLICT', message: 'secret https://internal-storage/key', requestId: 'req-1' }), { status: 409 })));
    const error = await new ApiClient('Basic test').send('PATCH', '/api/documents/1', { version: 1 }).catch(e => e);
    expect(error).toBeInstanceOf(ApiError); if (!(error instanceof ApiError)) throw new Error('Expected API failure'); expect(error.message).toContain('内容已变化'); expect(error.message).not.toContain('secret'); expect(error.requestId).toBe('req-1');
  });
  it('expires the session on unauthorized responses', async () => {
    const expire = vi.fn(); vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    await expect(new ApiClient('Basic test', expire).get('/api/kbs')).rejects.toMatchObject({ status: 401 }); expect(expire).toHaveBeenCalledOnce();
  });
  it('leaves multipart content type to the browser and preserves the idempotency key', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ id: 'doc' })); vi.stubGlobal('fetch', fetcher);
    const data = new FormData(); data.append('file', new Blob(['hello']), 'hello.txt'); await new ApiClient('Basic test').upload('/api/kbs/1/documents', data, 'same-upload-key');
    const options = fetcher.mock.calls[0][1]; expect(options.body).toBe(data); expect(options.headers.has('Content-Type')).toBe(false); expect(options.headers.get('Idempotency-Key')).toBe('same-upload-key');
  });
  it('keeps navigation cancellation active while consuming response bodies', async () => {
    let networkSignal: AbortSignal | undefined; const external = new AbortController();
    vi.stubGlobal('fetch', vi.fn((_path, options) => { networkSignal = options.signal; return Promise.resolve({ ok: true, status: 200, json: () => new Promise((_resolve, reject) => networkSignal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))) }); }));
    const request = new ApiClient('Basic test').get('/api/kbs', external.signal); await Promise.resolve(); external.abort(); await expect(request).rejects.toThrow(); expect(networkSignal?.aborted).toBe(true);
  });
  it('applies the deadline to stalled response bodies', async () => {
    vi.useFakeTimers(); vi.stubGlobal('fetch', vi.fn((_path, options) => Promise.resolve({ ok: true, status: 200, json: () => new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')))) })));
    const request = new ApiClient('Basic test').get('/api/kbs'); const assertion = expect(request).rejects.toThrow(); await vi.advanceTimersByTimeAsync(180_000); await assertion;
  });
  it('does not expose HTML reverse-proxy response bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>internal server details</html>'))); await expect(new ApiClient('Basic test').get('/api/kbs')).rejects.toThrow('服务响应格式无效');
  });
  it('reads all scope pages so resources past page one remain selectable', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(ok({ records: ['first'], total: 2 })).mockResolvedValueOnce(ok({ records: ['second'], total: 2 })); vi.stubGlobal('fetch', fetcher);
    expect(await allPages<string>(new ApiClient('Basic test'), '/api/kbs')).toEqual(['first', 'second']); expect(fetcher.mock.calls[1][0]).toContain('current=2');
  });
});
