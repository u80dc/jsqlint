import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

interface UserInfo {
  id: number
  username: string
  realName: string
  role: string
  roleDescription: string
}

const STORAGE_KEY = 'jsqlint_user_info'

function loadUserInfo(): UserInfo {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      return JSON.parse(stored)
    }
  } catch {
    clearUserInfo()
  }
  return {
    id: 0,
    username: '',
    realName: '',
    role: '',
    roleDescription: ''
  }
}

function saveUserInfo(info: UserInfo) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(info))
  } catch {
  }
}

function clearUserInfo() {
  localStorage.removeItem(STORAGE_KEY)
}

export const useUserStore = defineStore('user', () => {
  const storedUserInfo = loadUserInfo()

  const userId = ref(storedUserInfo.id)
  const token = ref<string>(localStorage.getItem('token') || '')
  const username = ref(storedUserInfo.username)
  const realName = ref(storedUserInfo.realName)
  const role = ref(storedUserInfo.role)
  const roleDescription = ref(storedUserInfo.roleDescription)

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function setUserInfo(data: UserInfo) {
    userId.value = data.id
    username.value = data.username
    realName.value = data.realName
    role.value = data.role
    roleDescription.value = data.roleDescription
    saveUserInfo(data)
  }

  function logout() {
    userId.value = 0
    token.value = ''
    username.value = ''
    realName.value = ''
    role.value = ''
    roleDescription.value = ''
    localStorage.removeItem('token')
    clearUserInfo()
  }

  const isAdmin = computed(() => role.value === 'ROLE_ADMIN')
  const displayName = computed(() => realName.value || username.value)

  return {
    userId,
    token,
    username,
    realName,
    role,
    roleDescription,
    isAdmin,
    displayName,
    setToken,
    setUserInfo,
    logout
  }
})
