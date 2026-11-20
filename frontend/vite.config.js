import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// sockjs-client expects a Node-style `global` — map it to window in the browser.
export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window',
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // Dev convenience: accept tunneled/preview hosts (e.g. cloud IDE proxies).
    // Restrict to specific hosts if you expose the dev server on a shared network.
    allowedHosts: true,
    // Dev proxy: the browser only ever talks to the Vite origin (no CORS headaches).
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
