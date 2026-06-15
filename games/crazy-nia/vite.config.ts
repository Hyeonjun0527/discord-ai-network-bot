import { defineConfig } from 'vite';

// Base is relative so the production bundle works when served from any subpath
// (e.g. a future Discord Activity embed or static host).
export default defineConfig({
  base: './',
  server: {
    port: 5173,
    host: '127.0.0.1',
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
  },
});
