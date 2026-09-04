<template>
  <div class="login-wrapper">
    <div class="login-card">
      <div class="login-header">
        <div class="logo-icon">
          <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M16 2L4 8v16l12 6 12-6V8L16 2z" stroke="currentColor" stroke-width="1.5" fill="none" />
            <path d="M4 8l12 6 12-6M16 14v16" stroke="currentColor" stroke-width="1.5" />
            <circle cx="16" cy="16" r="3" fill="currentColor" />
          </svg>
        </div>
        <h1>{{ t('login.title') }}</h1>
      </div>

      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        class="login-form"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            :placeholder="t('login.username')"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            :placeholder="t('login.password')"
            size="large"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <div class="form-options">
          <el-checkbox v-model="rememberMe">{{ t('login.rememberMe') }}</el-checkbox>
        </div>

        <el-button
          type="primary"
          class="login-btn"
          :loading="loading"
          native-type="submit"
        >
          {{ loading ? t('login.loggingIn') : t('login.loginBtn') }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { User, Lock } from '@element-plus/icons-vue'
import type { FormInstance } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { login } from '@/api/auth'
import { resetSessionExpired } from '@/api/request'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t } = useI18n()

const loginFormRef = ref<FormInstance>()
const loading = ref(false)
const rememberMe = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const loginRules = {
  username: [{ required: true, message: t('login.username'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.password'), trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return

  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const response = await login(loginForm)
        resetSessionExpired()
        userStore.setToken(response.token)
        userStore.setUserInfo({
          id: response.id,
          username: response.username,
          realName: response.realName,
          role: response.role,
          roleDescription: response.roleDescription
        })
        if (rememberMe.value) {
          localStorage.setItem('remember_me', 'true')
        }
        // 会话失效被踢出时带 redirect 参数，登录后回到原页面
        const redirect = route.query.redirect
        router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/dashboard')
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style lang="scss" scoped>
.login-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--jsp-bg-page);
}

.login-card {
  width: 100%;
  max-width: 380px;
  padding: 32px;
  background: var(--jsp-bg-card);
  border-bottom: 1px solid var(--jsp-border-color);
  border-radius: 8px;
}

.login-header {
  margin-bottom: 28px;
  text-align: center;

  .logo-icon {
    width: 56px;
    height: 56px;
    background: linear-gradient(135deg, $primary-color 0%, #8b5cf6 100%);
    border-radius: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0 auto 16px;

    svg {
      width: 34px;
      height: 34px;
      color: $text-inverse;
    }
  }

  h1 {
    margin: 0;
    color: var(--jsp-text-primary);
    font-size: 20px;
    line-height: 1.25;
    font-weight: 600;
    letter-spacing: 0;
  }
}

.login-form {
  :deep(.el-form-item) {
    margin-bottom: 18px;
  }

  :deep(.el-input__wrapper) {
    background: var(--jsp-bg-card);
    border-radius: 8px;
  }

  :deep(.el-input__prefix) {
    color: $text-placeholder;
  }

  .form-options {
    display: flex;
    align-items: center;
    margin: 2px 0 22px;

    :deep(.el-checkbox__label) {
      color: var(--jsp-text-secondary);
      font-size: 14px;
    }
  }

  .login-btn {
    width: 100%;
    height: 44px;
    border-radius: 8px;
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .login-wrapper {
    padding: 16px;
  }

  .login-card {
    padding: 28px 20px;
  }
}
</style>
