<template>
  <div class="workspace">
    <el-row :gutter="20">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>{{ t('layout.appName') }}</span>
            </div>
          </template>
          <div class="workspace-content">
            <el-row :gutter="20">
              <el-col :span="6">
                <div class="quick-access" @click="navigateTo('/workspace/projects')">
                  <el-icon class="icon"><Collection /></el-icon>
                  <span class="text">{{ t('nav.projects') }}</span>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="quick-access" @click="navigateTo('/workspace/inspection')">
                  <el-icon class="icon"><Monitor /></el-icon>
                  <span class="text">{{ t('nav.inspection') }}</span>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="quick-access" @click="navigateTo('/workspace/alert')">
                  <el-icon class="icon"><Bell /></el-icon>
                  <span class="text">{{ t('nav.alert') }}</span>
                </div>
              </el-col>
              <el-col :span="6" v-if="userInfo.role === 'ROLE_ADMIN'">
                <div class="quick-access" @click="navigateTo('/users')">
                  <el-icon class="icon"><Setting /></el-icon>
                  <span class="text">{{ t('nav.system') }}</span>
                </div>
              </el-col>
            </el-row>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>{{ t('workspace.recentActivity') }}</span>
          </template>
          <el-empty :description="t('workspace.noRecentActivity')" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>{{ t('workspace.systemStatus') }}</span>
          </template>
          <div class="status-list">
            <div class="status-item">
              <span class="label">{{ t('workspace.serviceStatus') }}</span>
              <el-tag type="success">{{ t('workspace.normal') }}</el-tag>
            </div>
            <div class="status-item">
              <span class="label">{{ t('workspace.databaseStatus') }}</span>
              <el-tag type="success">{{ t('workspace.connected') }}</el-tag>
            </div>
            <div class="status-item">
              <span class="label">{{ t('workspace.scanEngine') }}</span>
              <el-tag type="success">{{ t('workspace.ready') }}</el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Collection, Monitor, Bell, Setting } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()

const userInfo = computed(() => ({
  username: userStore.username,
  realName: userStore.realName,
  role: userStore.role,
  roleDescription: userStore.roleDescription
}))

const navigateTo = (path: string) => {
  router.push(path)
}
</script>

<style lang="scss" scoped>
.workspace {
  .card-header {
    font-weight: 600;
  }

  .workspace-content {
    padding: 20px 0;
  }

  .quick-access {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 30px;
    background: var(--jsp-bg-card);
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.3s;
    border-bottom: 1px solid var(--jsp-border-color);

    &:hover {
      box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
      transform: translateY(-2px);
    }

    .icon {
      font-size: 36px;
      color: $primary-color;
      margin-bottom: 12px;
    }

    .text {
      font-size: 16px;
      color: var(--jsp-text-primary);
    }
  }

  .status-list {
    .status-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 0;
      border-bottom: 1px solid var(--jsp-border-color);

      &:last-child {
        border-bottom: none;
      }

      .label {
        color: var(--jsp-text-secondary);
      }
    }
  }
}
</style>