import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import enUS from 'element-plus/es/locale/lang/en'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import router from './router'
import i18n from './locales'
import App from './App.vue'
import './assets/main.scss'
import { useTheme } from './composables/useTheme'

// 首帧同步深浅色主题，避免闪烁
useTheme()

const app = createApp(App)
const pinia = createPinia()

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 根据当前语言设置 Element Plus 的 locale
const currentLocale = localStorage.getItem('language') || 'zh-CN'
const elementLocale = currentLocale === 'en-US' ? enUS : zhCn

app.use(pinia)
app.use(router)
app.use(i18n)
app.use(ElementPlus, { locale: elementLocale })

app.mount('#app')