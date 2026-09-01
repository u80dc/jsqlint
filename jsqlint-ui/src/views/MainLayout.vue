<template>
  <div class="layout-container">
    <SidebarMenu
      :active-menu="activeMenu"
      :collapsed="isCollapsed"
      :is-admin="userStore.isAdmin"
      @navigate="handleNavigate"
      @toggle-collapse="toggleCollapse"
    />

    <div class="main-wrapper">
      <TopHeader
        :collapsed="isCollapsed"
        :hide-title="hideHeaderTitle"
        :is-project-detail="route.name === 'ProjectDetail'"
        :is-sql-construction-detail="route.name === 'SqlConstructionDetail'"
        :notification-count="notificationCount"
        :page-title="pageTitle"
        :project-name="projectName"
        :sql-construction-location="sqlConstructionTitle"
        @back="handleBack"
        @logout="handleLogout"
        @toggle-notifications="notificationPanelVisible = !notificationPanelVisible"
      />

      <main class="main-content">
        <router-view />
      </main>
    </div>

    <transition name="slide">
      <NotificationPanel
        v-show="notificationPanelVisible"
        @close="notificationPanelVisible = false"
        @unread-change="notificationCount = $event"
      />
    </transition>

    <div v-if="notificationPanelVisible" class="notification-overlay" @click="notificationPanelVisible = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NotificationPanel from '@/components/layout/NotificationPanel.vue'
import SidebarMenu from '@/components/layout/SidebarMenu.vue'
import TopHeader from '@/components/layout/TopHeader.vue'
import { getProject } from '@/api/project'
import { getSqlConstructionDetail } from '@/api/sqlStatement'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()

const isCollapsed = ref(false)
const notificationCount = ref(0)
const notificationPanelVisible = ref(false)
const projectName = ref('')
const sqlConstructionTitle = ref('')
const sqlConstructionProjectId = ref<number | null>(null)

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => {
  if (route.name === 'SqlConstructionDetail') {
    return 'SQL构造详情'
  }
  const titleKeys: Record<string, string> = {
    '/dashboard': 'nav.dashboard',
    '/workspace/projects': 'nav.projects',
    '/workspace/inspection': 'nav.inspection',
    '/workspace/alert': 'nav.alert',
    '/workspace/rules': 'nav.rules',
    '/workspace/credentials': 'nav.credentials',
    '/workspace/repositories': 'nav.repositories',
    '/users': 'nav.users',
    '/profile': 'nav.profile',
    '/settings': 'nav.settings',
    '/logs': 'nav.logs'
  }
  return t(titleKeys[route.path] || 'layout.appName')
})

const hideHeaderTitle = computed(() => route.path === '/dashboard')

const handleBack = () => {
  if (route.name === 'SqlConstructionDetail') {
    const projectId = Number(route.query.projectId || sqlConstructionProjectId.value)
    if (projectId && !Number.isNaN(projectId)) {
      router.push({
        path: `/workspace/projects/${projectId}`,
        query: { tab: 'projectSql' }
      })
      return
    }
  }
  router.back()
}

const handleNavigate = (path: string) => {
  router.push(path)
}

const toggleCollapse = () => {
  isCollapsed.value = !isCollapsed.value
}

const handleLogout = () => {
  userStore.logout()
  ElMessage.success(t('layout.logoutSuccess'))
  router.push('/login')
}

watch(() => route.params.id, async (projectId) => {
  if (route.name === 'ProjectDetail' && projectId) {
    try {
      const project = await getProject(Number(projectId))
      projectName.value = project.name
    } catch {
      projectName.value = ''
    }
  } else {
    projectName.value = ''
  }
}, { immediate: true })

watch(() => [route.name, route.params.id, route.query.title], async () => {
  sqlConstructionTitle.value = ''
  sqlConstructionProjectId.value = null
  if (route.name !== 'SqlConstructionDetail') {
    return
  }

  const queryTitle = typeof route.query.title === 'string' ? route.query.title : ''
  if (queryTitle) {
    sqlConstructionTitle.value = queryTitle
  }

  const sqlId = Number(route.params.id)
  if (!sqlId || Number.isNaN(sqlId)) {
    return
  }

  try {
    const detail = await getSqlConstructionDetail(sqlId)
    sqlConstructionProjectId.value = detail.projectId
    const definition = detail.definition
    if (definition.mapperClass && definition.mapperMethod) {
      sqlConstructionTitle.value = `${definition.mapperClass}.${definition.mapperMethod}`
    } else {
      sqlConstructionTitle.value = definition.statementId || queryTitle
    }
  } catch {
    sqlConstructionTitle.value = queryTitle
  }
}, { immediate: true })
</script>

<style lang="scss" scoped>
.layout-container {
  height: 100vh;
  display: flex;
  background: var(--jsp-bg-page);
}

.main-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.main-content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  background: var(--jsp-bg-page);

  &::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }

  &::-webkit-scrollbar-thumb {
    background: $border-darker;
    border-radius: 4px;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: $text-placeholder;
  }
}

.notification-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.2);
  z-index: 999;
}

.slide-enter-active,
.slide-leave-active {
  transition: transform 0.25s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
</style>
