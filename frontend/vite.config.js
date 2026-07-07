import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/hfmp-ykt/',
  server: {
    port: 3000,
    host: '0.0.0.0',
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
