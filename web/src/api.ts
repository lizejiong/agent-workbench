import type { Page } from './types';

export class ApiError extends Error {
  constructor(public status: number, public code: string, public requestId?: string) {
    super(friendlyError(code, status));
  }
}
export function friendlyError(code: string, status = 0): string {
  const messages: Record<string, string> = {
    KB_NAME_EXISTS: '名称已存在，请使用其他名称。', KB_VERSION_CONFLICT: '内容已变化，请刷新后重新操作。',
    KB_NOT_EMPTY: '请先删除文档并等待资源清理完成，再删除知识库。', KB_JOB_ACTIVE: '该文档已有入库任务，请查看任务状态。',
    KB_REFRESH_ACTIVE: '该文档正在刷新，请等待任务结束。', KB_DOCUMENT_DISABLED: '请先启用文档。',
    KB_MANUAL_OVERWRITE_REQUIRED: '该文档有人工作业，需要明确确认覆盖。', KB_KEYWORD_DISABLED: '关键词服务尚未启用。',
    KB_RERANK_DISABLED: '精排服务尚未启用。', KB_FEATURE_DISABLED: '该能力尚未配置，请联系维护者。',
    KB_REMOTE_DISABLED: '远程导入尚未启用，请联系维护者配置来源白名单。',
    KB_UPLOAD_TOO_LARGE: '文件超过服务端大小上限。', KB_EMPTY_FILE: '不能上传空文件。',
    KB_UNSUPPORTED_TYPE: '文件类型或解析档位不受支持。', KB_UNSUPPORTED_FORMAT: '文件类型或解析档位不受支持。',
    KB_MODEL_UNAVAILABLE: '模型服务暂不可用，请检查服务配置。', KB_MODEL_TIMEOUT: '模型调用超时，请稍后重试。',
    KB_MODEL_INVALID_RESPONSE: '模型返回的数据不符合约定，请检查模型配置。',
    KB_MODEL_CONFIG_CHANGED: '嵌入模型配置发生变化，请由维护者检查模型绑定。',
    KB_SEARCH_MODEL_MISMATCH: '所选知识库属于不同嵌入空间，请分开检索。',
    KB_REMOTE_HOST_DENIED: '来源地址未获服务端批准。', KB_REFRESH_RETRY_REQUIRED: '请从刷新设置重新发起刷新。',
    KB_INVALID_INPUT: '参数不符合接口要求，请检查填写内容。', KB_SEARCH_ALL_CHANNELS_FAILED: '检索通道暂不可用，请稍后重试。',
    KB_DOCUMENT_NAME_EXISTS: '该知识库内已存在同名文档，请修改名称。', KB_DOCUMENT_NOT_INDEXED: '请先成功完成首次入库，再维护 Chunk。',
    KB_IDEMPOTENCY_CONFLICT: '相同请求标识已用于其他内容，请重新打开操作并确认。',
    KB_MODEL_RATE_LIMITED: '模型服务限流，后台将按重试预算退避执行。', KB_MODEL_REQUEST_REJECTED: '模型服务拒绝请求，请维护者核对模型配置与输入预算。',
    KB_PARSE_FAILED: '文件解析失败，请检查原文件及所选解析档位。', KB_PARSE_TIMEOUT: '基础解析超时，请调整文件或解析预算。',
    KB_EMPTY_CONTENT: '没有可入库的文本；扫描件请使用已配置 OCR 的解析档位。', KB_OCR_REQUIRED: '文件需要 OCR，请配置高保真解析服务。',
    KB_STRUCTURE_TOO_LARGE: '单个结构超出硬长度上限，请调整分块配置或源文件。', KB_PARSE_LIMIT: '文档超出解析行数、页数或结果大小预算。',
    KB_UNSUPPORTED_FILE: '文件实际类型、扩展名或所选解析档位不受支持。', KB_PROFILE_NOT_ALLOWED: '该格式不支持所选解析档位。',
    KB_MINERU_UNAVAILABLE: '高保真解析服务未配置或不可用。', KB_VLM_UNAVAILABLE: '图片识别服务未配置或不可用。',
    KB_FORMULA_EVALUATION_FAILED: '表格公式求值失败，请检查公式或原文件。',
    KB_STORAGE_UNAVAILABLE: '对象存储暂不可用，请由维护者检查配置。', KB_DATABASE_UNAVAILABLE: '数据库暂不可用，请稍后重试。',
    KB_INVALID_SCHEDULE: 'Cron 或时区无效，请使用含秒的 6 字段 Cron 和有效 IANA 时区。',
    KB_REMOTE_URL_REJECTED: '来源 URL 不符合服务端白名单或网络规则。', KB_REMOTE_ADDRESS_REJECTED: '来源解析地址未获服务端批准。',
    KB_REMOTE_DOWNLOAD_FAILED: '远程文件下载失败，原有可检索版本仍保留。', KB_REMOTE_TIMEOUT: '远程文件下载超时。',
    KB_REMOTE_HTTP_ERROR: '远程服务器返回错误状态，请检查来源。', KB_REFRESH_CLAIM_LOST: '刷新执行权或配置已变化，本次结果不会激活。',
    KB_JOB_EXPIRED: '任务执行权已失效，本次结果不会激活。', KB_JOB_CANCELLED: '任务已取消。', KB_INGESTION_TIMEOUT: '入库执行超时，请检查失败阶段和资源预算。',
    KB_SEARCH_DOCUMENT_SCOPE: '文档不在所选知识库范围内，或当前账号无权访问。', KB_SEARCH_UNAVAILABLE: '检索通道暂不可用，请稍后重试。',
  };
  return messages[code] || (status === 401 ? '账号或密码无效，或登录已失效。' : status === 403 ? '当前账号没有操作权限。'
    : status === 404 ? '资源不存在或当前账号无权访问。' : status === 409 ? '当前状态与操作冲突，请刷新后重试。'
      : status === 413 ? '文件超过服务端大小上限。' : status === 415 ? '文件格式不受支持。'
        : status === 400 || status === 422 ? '参数或文件不符合要求，请检查后重试。' : '服务暂不可用，请稍后重试。');
}
export function basicAuthorization(username: string, password: string): string {
  if (!username.trim() || !password || username.includes(':')) throw new Error('请填写有效账号和密码。');
  return 'Basic ' + btoa(Array.from(new TextEncoder().encode(`${username.trim()}:${password}`), b => String.fromCharCode(b)).join(''));
}
export function query(values: Record<string, string | number | boolean | undefined>): string {
  const result = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') result.set(key, String(value));
  return result.size ? '?' + result.toString() : '';
}
export function downloadName(disposition: string | null, fallback: string): string {
  let name = fallback;
  const utf8 = disposition?.match(/filename\*=UTF-8''([^;]+)/i);
  const plain = disposition?.match(/filename="([^"]+)"/i);
  try { if (utf8) name = decodeURIComponent(utf8[1]); else if (plain) name = plain[1]; } catch { /* Keep the safe display-name fallback. */ }
  name = name.replace(/[\\/\x00-\x1f\x7f]/g, '_').slice(0, 255);
  return !name || name === '.' || name === '..' ? 'document' : name;
}
export class ApiClient {
  constructor(private authorization: string, private expired: () => void = () => {}) {}
  private async read<T>(path: string, options: RequestInit, consume: (response: Response) => Promise<T>): Promise<T> {
    // The server origin is configured on the proxy. Never send credentials to a response-supplied URL.
    if (!/^\/(api|openapi)\//.test(path) || /\\|%2e|%2f|%5c|\/\.\.?\//i.test(path.split('?')[0])) throw new Error('无效的接口路径。');
    const headers = new Headers(options.headers);
    headers.set('Authorization', this.authorization);
    headers.set('X-Request-Id', 'web-' + crypto.randomUUID());
    const timeout = new AbortController();
    const abort = () => timeout.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) timeout.abort();
    const timer = setTimeout(abort, 180_000);
    try {
      const response = await fetch(path, { ...options, headers, signal: timeout.signal, redirect: 'error', credentials: 'omit', cache: 'no-store' });
      if (!response.ok) {
        if (response.status === 401) this.expired();
        let payload: { code?: string; requestId?: string } = {};
        try { payload = await response.json(); } catch { /* Reverse proxies may return non-JSON. */ }
        throw new ApiError(response.status, payload.code || `HTTP_${response.status}`, payload.requestId || response.headers.get('X-Request-Id') || undefined);
      }
      return await consume(response);
    } catch (failure) {
      if (timeout.signal.aborted && !options.signal?.aborted) throw new Error('请求超时，请先查询任务或资源状态，再决定是否重试。');
      if (failure instanceof TypeError) throw new Error('无法连接服务，请检查网络与后端状态。');
      throw failure;
    } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
  }
  async get<T>(path: string, signal?: AbortSignal): Promise<T> { return this.read(path, { signal }, response => this.unwrap<T>(response)); }
  async send<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>, signal?: AbortSignal): Promise<T> {
    return this.read(path, { method, signal, headers: { 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) }, response => this.unwrap<T>(response));
  }
  async upload<T>(path: string, data: FormData, key: string): Promise<T> {
    return this.read(path, { method: 'POST', body: data, headers: { 'Idempotency-Key': key } }, response => this.unwrap<T>(response));
  }
  private async unwrap<T>(response: Response): Promise<T> {
    let envelope: { code: string; data: T; requestId?: string };
    try { envelope = await response.json(); } catch { throw new Error('服务响应格式无效，请联系维护者。'); }
    if (envelope.code !== '0') throw new ApiError(response.status, envelope.code, envelope.requestId);
    return envelope.data;
  }
  async download(path: string, filename: string): Promise<void> {
    const result = await this.read(path, {}, async response => ({ blob: await response.blob(), filename: downloadName(response.headers.get('Content-Disposition'), filename) }));
    const blob = result.blob;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = result.filename;
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
/** Fetch every bounded page for scope selectors instead of silently hiding resources after the first page. */
export async function allPages<T>(api: ApiClient, path: string, signal?: AbortSignal): Promise<T[]> {
  const rows: T[] = [];
  for (let current = 1; current <= 100; current++) {
    const page = await api.get<Page<T>>(path + query({ current, size: 100 }), signal);
    rows.push(...page.records);
    if (rows.length >= page.total) return rows;
  }
  throw new Error('可选范围过大，请使用知识库详情中的检索入口缩小范围。');
}
