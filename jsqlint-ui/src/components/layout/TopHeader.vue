<template>
  <header class="top-header">
    <div class="header-left">
      <template v-if="isProjectDetail">
        <div class="project-nav">
          <div class="back-btn" @click="$emit('back')">
            <el-icon><ArrowLeft /></el-icon>
            <span>{{ t('common.back') }}</span>
          </div>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/workspace/projects' }">
              {{ t('layout.projectInstance') }}
            </el-breadcrumb-item>
            <el-breadcrumb-item>
              <template #separator>
                <el-icon class="breadcrumb-separator"><ArrowRight /></el-icon>
              </template>
              {{ projectName }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
      </template>
      <template v-else-if="isSqlConstructionDetail">
        <div class="detail-nav">
          <div class="back-btn" @click="$emit('back')">
            <el-icon><ArrowLeft /></el-icon>
            <span>{{ t('common.back') }}</span>
          </div>
          <span class="detail-name">{{ pageTitle }}</span>
          <span class="detail-location">{{ sqlConstructionLocation }}</span>
        </div>
      </template>
      <template v-else>
        <h2 v-if="!hideTitle" class="page-title">{{ pageTitle }}</h2>
      </template>
    </div>

    <div class="header-right">
      <div
        class="header-icon-btn"
        :title="theme === 'dark' ? t('common.theme.light') : t('common.theme.dark')"
        @click="toggleTheme"
      >
        <el-icon :size="20"><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>
      </div>

      <el-badge :value="notificationCount" :hidden="notificationCount === 0" :max="99" class="notification-badge">
        <div class="header-icon-btn" @click="$emit('toggle-notifications')">
          <el-icon :size="20"><Bell /></el-icon>
        </div>
      </el-badge>

      <el-dropdown @command="handleCommand" trigger="click">
        <div class="user-dropdown">
          <el-avatar :size="36" :style="{ background: 'linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%)' }">
            {{ userStore.displayName?.charAt(0) || 'U' }}
          </el-avatar>
          <div v-if="!collapsed" class="user-info">
            <span class="user-name">{{ userStore.displayName }}</span>
            <span class="user-role">{{ userStore.roleDescription }}</span>
          </div>
          <el-icon class="dropdown-arrow"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              <el-icon><User /></el-icon>
              <span>{{ userStore.username }}</span>
            </el-dropdown-item>
            <el-dropdown-item disabled>
              <el-icon><Postcard /></el-icon>
              <span>{{ userStore.roleDescription }}</span>
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided style="color: #ef4444;">
              <el-icon><SwitchButton /></el-icon>
              <span>{{ t('common.logout') }}</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ArrowDown, ArrowLeft, ArrowRight, Bell, Moon, Postcard, Sunny, SwitchButton, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useTheme } from '@/composables/useTheme'

defineProps<{
  collapsed: boolean
  hideTitle: boolean
  isProjectDetail: boolean
  isSqlConstructionDetail: boolean
  notificationCount: number
  pageTitle: string
  projectName: string
  sqlConstructionLocation: string
}>()

const emit = defineEmits<{
  back: []
  logout: []
  'toggle-notifications': []
}>()

const { t } = useI18n()

const { theme, toggleTheme } = useTheme()
const userStore = useUserStore()

const handleCommand = (command: string) => {
  if (command === 'logout') {
    emit('logout')
  }
}
</script>

<style lang="scss" scoped>
.top-header {
  height: 64px;
  flex-shrink: 0;
  background: var(--jsp-bg-card);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  z-index: 10;

  .header-left {
    flex: 1;
    min-width: 0;

    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--jsp-text-primary);
      margin: 0;
    }

    .project-nav,
    .detail-nav {
      display: flex;
      align-items: center;
      gap: 16px;
      min-width: 0;

      .back-btn {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        flex-shrink: 0;
        color: var(--jsp-text-primary);
        cursor: pointer;
        transition: color 0.2s ease;

        &:hover {
          color: $primary-color;
        }

        .el-icon {
          font-size: 16px;
        }

        span {
          font-size: 14px;
        }
      }
    }

    .detail-nav {
      .detail-name {
        flex-shrink: 0;
        color: var(--jsp-text-primary);
        font-size: 14px;
        font-weight: 400;
        line-height: 1.4;
      }

      .detail-location {
        min-width: 0;
        color: $text-placeholder;
        font-size: 12px;
        font-weight: 400;
        line-height: 1.4;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .project-nav {
      .el-breadcrumb {
        font-size: 14px;
        display: inline-flex;
        align-items: center;
        line-height: normal;

        :deep(.el-breadcrumb__inner) {
          color: var(--jsp-text-secondary);
          font-weight: normal;
          line-height: normal;
        }

        :deep(.breadcrumb-separator) {
          color: $text-placeholder;
          font-size: 12px;
          margin: 0 8px;
          line-height: normal;
        }

        :deep(.el-breadcrumb__inner.is-link:hover) {
          color: $primary-color;
        }
      }
    }
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 16px;
    flex-shrink: 0;
  }

  .notification-badge {
    :deep(.el-badge__content) {
      background: $danger-color;
      border: none;
    }
  }

  .header-icon-btn {
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 10px;
    color: var(--jsp-text-secondary);
    cursor: pointer;
    transition: all 0.15s ease;

    &:hover {
      background: $border-light;
      color: $primary-color;
    }
  }

  .user-dropdown {
    display: flex;
    align-items: center;
    cursor: pointer;
    padding: 6px 12px 6px 6px;
    border-radius: 10px;
    transition: all 0.15s ease;

    &:hover {
      background: $border-light;
    }

    .user-info {
      display: flex;
      flex-direction: column;
      margin-left: 12px;
      margin-right: 8px;

      .user-name {
        font-size: 14px;
        font-weight: 600;
        color: var(--jsp-text-primary);
        line-height: 1.3;
      }

      .user-role {
        font-size: 12px;
        color: $text-placeholder;
        line-height: 1.3;
      }
    }

    .dropdown-arrow {
      color: $text-placeholder;
      font-size: 12px;
    }
  }
}
</style>
