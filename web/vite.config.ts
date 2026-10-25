import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'KB_');
  const target = env.KB_BACKEND_ORIGIN || 'http://127.0.0.1:8080';
  const origin = new URL(target);
  if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) {
    throw new Error('KB_BACKEND_ORIGIN must be an HTTP(S) origin without credentials or a path');
  }
  const proxy = Object.fromEntries(['/api', '/openapi'].map(path => [path, { target, changeOrigin: false }]));
  return {
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
