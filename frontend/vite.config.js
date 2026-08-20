import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/hfmp-ykt/',
  server: {
    port: 3000,
    // 只监听本机：dev server 没有任何鉴权，绑 0.0.0.0 等于把开发中的页面连同
    // 代理到后端的通道一起挂到局域网上。要联调真机时临时 `npx vite --host` 即可。
    host: '127.0.0.1',
    proxy: {
      '/hfmp-ykt/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
