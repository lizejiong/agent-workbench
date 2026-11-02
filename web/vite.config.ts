import { defineConfig, loadEnv } from 'vite';
import type { ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'KB_');
  const target = env.KB_BACKEND_ORIGIN || 'http://127.0.0.1:8080';
  const agent = env.KB_AGENT_ORIGIN || 'http://127.0.0.1:8090';
  for (const [name, value] of [['KB_BACKEND_ORIGIN', target], ['KB_AGENT_ORIGIN', agent]]) {
    const origin = new URL(value);
    if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) {
      throw new Error(`${name} must be an HTTP(S) origin without credentials or a path`);
    }
  }
  const proxy: Record<string, ProxyOptions> = {
    '/api/agent': { target: agent, changeOrigin: false, configure(server) {
      server.on('proxyReq', (upstream, _request, response) => {
        // 请求体发送完毕后的浏览器取消表现为 response.close，也须关闭 Python 上游。
        const disconnect = () => { if (!response.writableEnded) upstream.destroy(); };
        response.once('close', disconnect);
        upstream.once('close', () => response.removeListener('close', disconnect));
      });
    } },
    ...Object.fromEntries(['/api', '/openapi'].map(path => [path, { target, changeOrigin: false }])),
  };
  return {
    define: { __LOGIN_DEFAULTS__: JSON.stringify({ username: env.KB_LOGIN_DEFAULT_USERNAME || '', password: env.KB_LOGIN_DEFAULT_PASSWORD || '' }) },
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    build: {
      sourcemap: false,
      // AntD modules have ordered initialization; preserve it when splitting vendor chunks.
      rolldownOptions: { output: { strictExecutionOrder: true, codeSplitting: { groups: [
        { name: 'react', test: /node_modules[\\/](react|react-dom|scheduler|react-router|react-router-dom)[\\/]/, priority: 20 },
        { name: 'ui', test: /node_modules[\\/](antd|@ant-design|@rc-component)[\\/]/, priority: 10, maxSize: 450_000 },
      ] } } },
    },
  };
});
