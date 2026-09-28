import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Separate from vite.config.js (dev/build only) so the dev-server proxy
// config there never needs a test-environment branch. jsdom, not the
// default node environment, since every test here renders real React
// components (DataTable, StatusPill, RequireRole) - a plain node
// environment has no DOM at all.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    globals: true,
  },
});
