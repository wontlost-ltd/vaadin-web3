import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  resolve: {
    alias: {
      '@walletconnect/ethereum-provider': fileURLToPath(new URL('./src/test/frontend/walletconnect-provider-stub.js', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom'
  }
});
