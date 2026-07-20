import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

const messages = {
  'zh-CN': zhCN,
  'en-US': enUS
}

// 从本地存储获取语言设置，默认中文
const savedLanguage = localStorage.getItem('language') || 'zh-CN'

const i18n = createI18n({
  legacy: false, // 使用 Composition API 模式
  locale: savedLanguage,
  fallbackLocale: 'zh-CN',
  messages
})

// 根据当前语言同步浏览器标签页标题，保持与界面展示的系统名一致
const titleMessages: Record<string, string> = {
  'zh-CN': 'SQL质量管理平台',
  'en-US': 'JSQLint System'
}
document.title = titleMessages[savedLanguage] || titleMessages['zh-CN']

export default i18n
