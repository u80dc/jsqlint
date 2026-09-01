<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="logo-area">
      <div class="logo-icon">
        <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M16 2L4 8v16l12 6 12-6V8L16 2z" stroke="currentColor" stroke-width="1.5" fill="none" />
          <path d="M4 8l12 6 12-6M16 14v16" stroke="currentColor" stroke-width="1.5" />
          <circle cx="16" cy="16" r="3" fill="currentColor" />
        </svg>
      </div>
      <transition name="fade">
        <h1 v-if="!collapsed" class="logo-title">{{ platformName }}</h1>
      </transition>
    </div>

    <nav class="nav-menu">
      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="transparent"
        text-color="#94a3b8"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard" @click="navigate('/dashboard')">
          <el-icon><Odometer /></el-icon>
          <template #title>{{ t('nav.dashboard') }}</template>
        </el-menu-item>

        <div v-if="!collapsed" class="menu-group-title">
          <span>{{ t('nav.workspace') }}</span>
        </div>

        <el-menu-item index="/workspace/projects" @click="navigate('/workspace/projects')">
          <el-icon><Collection /></el-icon>
          <template #title>{{ t('nav.projects') }}</template>
        </el-menu-item>

        <el-menu-item index="/workspace/inspection" @click="navigate('/workspace/inspection')">
          <el-icon><Monitor /></el-icon>
          <template #title>{{ t('nav.inspection') }}</template>
        </el-menu-item>

        <el-menu-item index="/workspace/alert" @click="navigate('/workspace/alert')">
          <el-icon><Bell /></el-icon>
          <template #title>{{ t('nav.alert') }}</template>
        </el-menu-item>

        <el-menu-item index="/workspace/rules" @click="navigate('/workspace/rules')">
          <el-icon><DocumentChecked /></el-icon>
          <template #title>{{ t('nav.rules') }}</template>
        </el-menu-item>

        <el-menu-item index="/workspace/credentials" @click="navigate('/workspace/credentials')">
          <el-icon><Key /></el-icon>
          <template #title>{{ t('nav.credentials') }}</template>
        </el-menu-item>

        <template v-if="isAdmin">
          <el-menu-item index="/workspace/repositories" @click="navigate('/workspace/repositories')">
            <el-icon><Box /></el-icon>
            <template #title>{{ t('nav.repositories') }}</template>
          </el-menu-item>
        </template>

        <template v-if="isAdmin">
          <div v-if="!collapsed" class="menu-group-title">
            <span>{{ t('nav.system') }}</span>
          </div>

          <el-menu-item index="/users" @click="navigate('/users')">
            <el-icon><User /></el-icon>
            <template #title>{{ t('nav.users') }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </nav>

    <div class="sidebar-footer">
      <div class="footer-item" @click="navigate('/profile')">
        <el-icon><Avatar /></el-icon>
        <transition name="fade">
          <span v-if="!collapsed">{{ t('nav.profile') }}</span>
        </transition>
      </div>

      <div v-if="isAdmin" class="footer-item" @click="navigate('/settings')">
        <el-icon><Tools /></el-icon>
        <transition name="fade">
          <span v-if="!collapsed">{{ t('nav.settings') }}</span>
        </transition>
      </div>

      <div v-if="isAdmin" class="footer-item" @click="navigate('/logs')">
        <el-icon><Document /></el-icon>
        <transition name="fade">
          <span v-if="!collapsed">{{ t('nav.logs') }}</span>
        </transition>
      </div>

      <div class="collapse-btn" @click="$emit('toggle-collapse')">
        <el-icon class="collapse-icon" :class="{ rotated: collapsed }">
          <ArrowRight />
        </el-icon>
        <transition name="fade">
          <span v-if="!collapsed">{{ t('layout.collapse') }}</span>
        </transition>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { usePlatformName } from '@/composables/usePlatformName'
import {
  Avatar,
  Bell,
  Box,
  Collection,
  Document,
  DocumentChecked,
  Key,
  Monitor,
  Odometer,
  Tools,
  User,
  ArrowRight
} from '@element-plus/icons-vue'

defineProps<{
  activeMenu: string
  collapsed: boolean
  isAdmin: boolean
}>()

const emit = defineEmits<{
  navigate: [path: string]
  'toggle-collapse': []
}>()

const { t } = useI18n()

const { platformName } = usePlatformName()

const navigate = (path: string) => {
  emit('navigate', path)
}
</script>

<style lang="scss" scoped>
.sidebar {
  width: 240px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: $text-primary;
  transition: width 0.25s ease;
  flex-shrink: 0;
  position: relative;
  z-index: 100;

  &.collapsed {
    width: 72px;

    .logo-icon {
      margin-right: 0;
    }
  }
}

.logo-area {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;

  .logo-icon {
    width: 36px;
    height: 36px;
    background: linear-gradient(135deg, $primary-color 0%, #8b5cf6 100%);
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 12px;
    flex-shrink: 0;

    svg {
      width: 22px;
      height: 22px;
      color: $text-inverse;
    }
  }

  .logo-title {
    font-size: 18px;
    font-weight: 700;
    color: $bg-page-light;
    margin: 0;
    white-space: nowrap;
    letter-spacing: 0;
  }
}

.nav-menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 8px;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.1);
    border-radius: 2px;
  }
}

.menu-group-title {
  padding: 16px 12px 8px;
  font-size: 12px;
  font-weight: 600;
  color: $text-secondary-dark;
  text-transform: uppercase;
  letter-spacing: 0.8px;
}

.sidebar-menu {
  border: none !important;
  background: transparent !important;

  :deep(.el-menu-item) {
    height: 44px;
    line-height: 44px;
    color: $text-placeholder;
    background: transparent;
    margin: 2px 0;
    padding: 0 12px !important;
    border-radius: 8px;
    transition: all 0.15s ease;

    .el-icon {
      margin-right: 12px;
      font-size: 18px;
      flex-shrink: 0;
    }

    &:hover {
      color: $border-color;
      background: rgba(255, 255, 255, 0.06);
    }

    &.is-active {
      color: $text-inverse !important;
      background: linear-gradient(135deg, rgba(59, 130, 246, 0.2) 0%, rgba(139, 92, 246, 0.2) 100%) !important;

      .el-icon {
        color: #60a5fa;
      }

      &::before {
        content: '';
        position: absolute;
        left: 0;
        top: 50%;
        transform: translateY(-50%);
        width: 3px;
        height: 24px;
        background: linear-gradient(180deg, $primary-color 0%, #8b5cf6 100%);
        border-radius: 0 2px 2px 0;
      }
    }
  }

  &:not(.el-menu--collapse) {
    width: 100%;
  }
}

.sidebar-footer {
  flex-shrink: 0;
  padding: 12px 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);

  .footer-item,
  .collapse-btn {
    display: flex;
    align-items: center;
    height: 40px;
    padding: 0 12px;
    margin-bottom: 4px;
    border-radius: 8px;
    color: $text-placeholder;
    cursor: pointer;
    transition: all 0.15s ease;

    .el-icon {
      margin-right: 12px;
      font-size: 18px;
      flex-shrink: 0;
    }

    span {
      font-size: 14px;
      white-space: nowrap;
    }

    &:hover {
      color: $border-color;
      background: rgba(255, 255, 255, 0.06);
    }
  }

  .collapse-btn {
    margin-top: 8px;

    .collapse-icon {
      transition: transform 0.2s ease;
      margin-right: 12px;

      &.rotated {
        transform: rotate(180deg);
      }
    }
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
