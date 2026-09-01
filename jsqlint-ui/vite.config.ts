import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 设计令牌全局注入，组件 <style lang="scss"> 内直接使用 $primary-color 等变量
        additionalData: `@use "@/assets/tokens" as *;\n`
      }
    }
  },
  server: {
    port: 3000,
    host: true
  }
})