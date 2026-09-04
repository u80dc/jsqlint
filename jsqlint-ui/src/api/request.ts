import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import { API_BASE_URL } from '@/config/app'

const service = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000
})

service.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 会话失效标记：并发请求同时 401 时只提示并跳转一次。
// 不能在此模块顶层访问 router（如 router.beforeEach）：request.ts 被 router 的
// 路由组件（LoginView）经 api/* 引入，顶层调用会形成循环依赖的 TDZ 错误。
// router 仅在拦截器回调内惰性访问，此时代码已全部初始化完成。
let sessionExpired = false

export function resetSessionExpired() {
  sessionExpired = false
}

function handleSessionExpired() {
  sessionExpired = true
  // 清空 Pinia store，否则路由守卫会因内存中 token 仍有值把 /login 重定向回工作台
  useUserStore().logout()
  ElMessage.error('登录已过期，请重新登录')
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    router.push({
      path: '/login',
      query: current.fullPath && current.fullPath !== '/' ? { redirect: current.fullPath } : undefined
    })
  }
}

service.interceptors.response.use(
  response => {
    return response.data
  },
  error => {
    const status = error.response?.status
    const requestUrl: string = error.config?.url || ''
    // 登录接口自身的 401 是凭证错误，不属于会话失效
    const isLoginRequest = requestUrl.includes('/api/auth/login')
    if (status === 401 && !isLoginRequest) {
      if (!sessionExpired) {
        handleSessionExpired()
      }
    } else if (status === 403) {
      ElMessage.error('没有权限访问该资源')
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.get<T, T>(url, config)
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post<T, T>(url, data, config)
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.put<T, T>(url, data, config)
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.patch<T, T>(url, data, config)
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete<T, T>(url, config)
  }
}

export default request
