import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

// web3-wallet-picker.js 在运行时由 web3-vaadin 的 jar 提供，测试时直接指向其源码
export default defineConfig({
  resolve: {
    // 被别名引入的选择器文件也从本模块解析 lit，避免依赖 web3-vaadin 的 node_modules 或加载两份 Lit
    dedupe: ['lit'],
    alias: [{
      find: /^\.\/web3-wallet-picker\.js$/,
      replacement: fileURLToPath(new URL('../web3-vaadin/src/main/resources/META-INF/frontend/web3-wallet-picker.js', import.meta.url))
    }]
  },
  test: {
    environment: 'jsdom',
    include: ['src/test/frontend/**/*.test.js']
  }
});
