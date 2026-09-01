import { ref } from 'vue'
import { getSystemSettings } from '@/api/systemConfig'

const STORAGE_KEY = 'jsqlint_platform_name'
const DEFAULT_NAMES: Record<string, string> = {
  'zh-CN': 'SQL质量管理平台',
  'en-US': 'JSQLint System'
}

const platformName = ref(loadInitial())

function loadInitial(): string {
  return localStorage.getItem(STORAGE_KEY) || DEFAULT_NAMES[localStorage.getItem('language') || 'zh-CN'] || DEFAULT_NAMES['zh-CN']
}

export function usePlatformName() {
  const setPlatformName = (name: string) => {
    platformName.value = name || DEFAULT_NAMES['zh-CN']
    localStorage.setItem(STORAGE_KEY, platformName.value)
  }

  // 从后端刷新（失败保持本地缓存）
  const refreshPlatformName = async () => {
    try {
      const vo = await getSystemSettings()
      if (vo.basic?.platformName) {
        setPlatformName(vo.basic.platformName)
      }
    } catch {
      // 忽略：非管理员或网络问题时沿用缓存
    }
  }

  return { platformName, setPlatformName, refreshPlatformName }
}
