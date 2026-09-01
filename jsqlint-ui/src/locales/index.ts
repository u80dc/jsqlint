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

// 浏览器标签页标题：优先使用管理员配置的平台名称，未配置时按语言取默认名
const titleMessages: Record<string, string> = {
  'zh-CN': 'SQL质量管理平台',
  'en-US': 'JSQLint System'
}
document.title = localStorage.getItem('jsqlint_platform_name') || titleMessages[savedLanguage] || titleMessages['zh-CN']

export default i18n
