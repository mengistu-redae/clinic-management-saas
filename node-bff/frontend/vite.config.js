import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api and /auth to node-bff on :3000, so the session
// cookie (host-only, no domain attribute - see node-bff/src/auth/session.js)
// is shared transparently across ports. Login itself still has to happen on
// :3000 directly (Keycloak's registered redirect URI is hardcoded to
// http://localhost:3000/auth/callback).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:3000', changeOrigin: true },
      '/auth': { target: 'http://localhost:3000', changeOrigin: true },
    },
  },
});
