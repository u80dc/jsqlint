<template>
  <div class="logs-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="section-title">{{ t('projectDetail.logs.title') }}</span>
          <el-tooltip :content="t('common.refresh')" placement="top">
            <el-button text @click="loadProjectLogs" class="refresh-btn">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </template>

      <el-table :data="projectLogs" v-loading="logsLoading" stripe>
        <el-table-column prop="operationTime" :label="t('projectDetail.logs.operationTime')" width="180">
          <template #default="{ row }">
            {{ formatDate(row.operationTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="operationType" :label="t('projectDetail.logs.operationType')" width="150" />
        <el-table-column prop="operationUserName" :label="t('projectDetail.logs.operationUser')" width="120" />
        <el-table-column prop="operationMessage" :label="t('projectDetail.logs.details')" min-width="300" show-overflow-tooltip />
        <el-table-column prop="ipAddress" :label="t('projectDetail.logs.ipAddress')" width="140" />
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getProjectLogsByProjectId, type ProjectLog } from '@/api/projectLog'

const props = defineProps<{
  active: boolean
  projectId: number
}>()

const { t, locale } = useI18n()

const logsLoading = ref(false)
const projectLogs = ref<ProjectLog[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

const loadProjectLogs = async () => {
  logsLoading.value = true
  try {
    const result = await getProjectLogsByProjectId(
      props.projectId,
      currentPage.value,
      pageSize.value
    )
    projectLogs.value = result.records
    total.value = result.total
  } catch {
    ElMessage.error(t('projectDetail.logs.loadError'))
  } finally {
    logsLoading.value = false
  }
}

const handleSizeChange = (size: number) => {
  pageSize.value = size
  loadProjectLogs()
}

const handleCurrentChange = (page: number) => {
  currentPage.value = page
  loadProjectLogs()
}

const formatDate = (date?: string) => {
  if (!date) return '-'
  return new Date(date).toLocaleString(locale.value)
}

watch(() => props.active, (active) => {
  if (active) {
    loadProjectLogs()
  }
}, { immediate: true })
</script>

<style lang="scss" scoped>
.logs-page {
  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;

    .section-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--jsp-text-primary);
    }

    .refresh-btn {
      padding: 4px;
      color: var(--jsp-text-secondary);

      &:hover {
        color: $primary-color;
      }

      .el-icon {
        font-size: 16px;
      }
    }
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
    padding: 16px 0;
  }

  :deep(.el-card__body) {
    padding: 0;
  }

  :deep(.el-table) {
    .el-table__header-wrapper {
      background: var(--jsp-bg-card);
    }
  }
}
</style>
