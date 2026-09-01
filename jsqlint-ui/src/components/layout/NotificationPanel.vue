<template>
  <div class="notification-panel">
    <div class="panel-header">
      <span class="panel-title">{{ t('notifications.title') }}</span>
      <el-button text @click="$emit('close')">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>
    <div class="panel-content">
      <div v-loading="loading" class="notification-list">
        <template v-if="notifications.length > 0">
          <div
            v-for="item in notifications"
            :key="item.id"
            class="notification-item"
            :class="{ unread: !item.read }"
            @click="handleNotificationItemClick(item)"
          >
            <div class="item-header">
              <el-tag :type="getAlertType(item.level)" size="small">{{ getAlertLabel(item.level) || item.typeDescription }}</el-tag>
              <span class="item-time">{{ formatTime(item.createTime) }}</span>
            </div>
            <div class="item-content">{{ item.content }}</div>
            <div v-if="item.projectName" class="item-project">
              {{ t('notifications.projectPrefix') }}: {{ item.projectName }}
            </div>
          </div>
        </template>
        <el-empty v-else :description="t('notifications.empty')" />
      </div>
    </div>
    <div v-if="notifications.length > 0" class="panel-footer">
      <span class="text-action" @click="handleMarkAllRead">{{ t('notifications.markAllRead') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { listNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notification'

interface NotificationItem {
  id: number
  type: string
  typeDescription?: string
  level?: string
  content: string
  read: boolean
  projectName?: string
  createTime: string
}

const emit = defineEmits<{
  close: []
  'unread-change': [count: number]
}>()

const { t, locale } = useI18n()
const loading = ref(false)
const notifications = ref<NotificationItem[]>([])

const updateUnreadCount = () => {
  emit('unread-change', notifications.value.filter(n => !n.read).length)
}

const loadNotifications = async () => {
  loading.value = true
  try {
    notifications.value = await listNotifications()
    updateUnreadCount()
  } catch (error) {
    console.error(t('notifications.loadError'), error)
  } finally {
    loading.value = false
  }
}

const handleNotificationItemClick = async (item: NotificationItem) => {
  if (!item.read) {
    try {
      await markNotificationRead(item.id)
      item.read = true
      updateUnreadCount()
    } catch (error) {
      console.error(t('notifications.markReadError'), error)
    }
  }
}

const getAlertType = (level?: string) => {
  const types: Record<string, string> = {
    CRITICAL: 'danger',
    HIGH: 'warning',
    MEDIUM: 'info',
    LOW: 'info'
  }
  return types[level || ''] || 'info'
}

const getAlertLabel = (level?: string) => {
  if (!level) {
    return ''
  }
  const key = `severity.${level.toLowerCase()}`
  const translated = t(key)
  return translated === key ? level : translated
}

const formatTime = (time: string) => {
  if (!time) return ''
  const date = new Date(time)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return t('time.justNow')
  if (minutes < 60) return t('time.minutesAgo', { count: minutes })
  if (hours < 24) return t('time.hoursAgo', { count: hours })
  if (days < 7) return t('time.daysAgo', { count: days })
  return date.toLocaleDateString(locale.value)
}

const handleMarkAllRead = async () => {
  try {
    await markAllNotificationsRead()
    notifications.value.forEach(n => {
      n.read = true
    })
    updateUnreadCount()
    ElMessage.success(t('notifications.markAllReadSuccess'))
  } catch {
    ElMessage.error(t('common.error'))
  }
}

onMounted(() => {
  loadNotifications()
})
</script>

<style lang="scss" scoped>
.notification-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 400px;
  height: 100vh;
  background: var(--jsp-bg-card);
  box-shadow: -4px 0 24px rgba(0, 0, 0, 0.12);
  z-index: 1000;
  display: flex;
  flex-direction: column;

  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px;
    border-bottom: 1px solid $border-light;

    .panel-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--jsp-text-primary);
    }
  }

  .panel-content {
    flex: 1;
    overflow: hidden;
    display: flex;
    flex-direction: column;

    .notification-list {
      flex: 1;
      overflow-y: auto;
      padding: 16px;

      .notification-item {
        padding: 16px;
        margin-bottom: 12px;
        border-radius: 12px;
        background: var(--jsp-bg-card);
        cursor: pointer;
        transition: all 0.15s ease;

        &:hover {
          background: $border-light;
        }

        &.unread {
          background: linear-gradient(135deg, rgba(239, 68, 68, 0.06) 0%, rgba(239, 68, 68, 0.03) 100%);
          border-left: 3px solid $danger-color;

          &:hover {
            background: linear-gradient(135deg, rgba(239, 68, 68, 0.08) 0%, rgba(239, 68, 68, 0.05) 100%);
          }
        }

        .item-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 10px;

          .item-time {
            font-size: 12px;
            color: $text-placeholder;
          }
        }

        .item-content {
          font-size: 14px;
          color: var(--jsp-text-primary);
          line-height: 1.6;
          margin-bottom: 8px;
        }

        .item-project {
          font-size: 12px;
          color: var(--jsp-text-secondary);
        }
      }
    }
  }

  .panel-footer {
    padding: 16px 24px;
    border-top: 1px solid $border-light;
    text-align: center;

    .text-action {
      color: $primary-color;
      font-size: 14px;
      cursor: pointer;

      &:hover {
        color: $primary-hover;
      }
    }
  }
}
</style>
