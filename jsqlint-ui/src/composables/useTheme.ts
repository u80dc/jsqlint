import { ref } from 'vue'

type ThemePreference = 'light' | 'dark' | 'system'
type EffectiveTheme = 'light' | 'dark'

const STORAGE_KEY = 'jsqlint_theme'

const preference = ref<ThemePreference>(loadInitialPreference())
const currentTheme = ref<EffectiveTheme>(resolveEffective(preference.value))

function loadInitialPreference(): ThemePreference {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'dark' || stored === 'light' || stored === 'system') {
    return stored
  }
  return 'light'
}

function resolveEffective(pref: ThemePreference): EffectiveTheme {
  if (pref === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return pref
}

function applyTheme(theme: EffectiveTheme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.setAttribute('data-theme', theme)
}

export function useTheme() {
  const setTheme = (pref: ThemePreference) => {
    preference.value = pref
    localStorage.setItem(STORAGE_KEY, pref)
    currentTheme.value = resolveEffective(pref)
    applyTheme(currentTheme.value)
  }

  const toggleTheme = () => {
    setTheme(currentTheme.value === 'dark' ? 'light' : 'dark')
  }

  // 跟随系统时监听 OS 主题变化实时切换
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (preference.value === 'system') {
      currentTheme.value = resolveEffective('system')
      applyTheme(currentTheme.value)
    }
  })

  // 初始化时同步到 DOM（供 main.ts 首帧调用）
  applyTheme(currentTheme.value)

  return { theme: currentTheme, themePreference: preference, setTheme, toggleTheme }
}
