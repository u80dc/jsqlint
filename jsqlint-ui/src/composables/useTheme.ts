import { ref } from 'vue'

type Theme = 'light' | 'dark'

const STORAGE_KEY = 'jsqlint_theme'

const currentTheme = ref<Theme>(loadInitialTheme())

function loadInitialTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'dark' || stored === 'light') {
    return stored
  }
  return 'light'
}

function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.setAttribute('data-theme', theme)
}

export function useTheme() {
  const setTheme = (theme: Theme) => {
    currentTheme.value = theme
    localStorage.setItem(STORAGE_KEY, theme)
    applyTheme(theme)
  }

  const toggleTheme = () => {
    setTheme(currentTheme.value === 'dark' ? 'light' : 'dark')
  }

  // 初始化时同步到 DOM（供 main.ts 首帧调用）
  applyTheme(currentTheme.value)

  return { theme: currentTheme, setTheme, toggleTheme }
}
