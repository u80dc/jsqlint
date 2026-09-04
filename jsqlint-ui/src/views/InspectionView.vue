<template>
  <div class="inspection">
    <el-card class="list-card">
      <div class="filter-toolbar">
        <div class="toolbar-actions">
          <el-button type="primary" :icon="Plus" @click="openCreateDialog">{{ t('inspection.addConfig') }}</el-button>
        </div>
        <div class="filter-toolbar__meta summary-inline">
          <span>{{ t('inspection.configuredCount', { count: configs.length }) }}</span>
          <span>{{ t('inspection.enabledCount', { count: enabledCount }) }}</span>
          <span>{{ t('inspection.runningCount', { count: runningCount }) }}</span>
          <span>{{ t('inspection.failedCount', { count: failedCount }) }}</span>
        </div>
      </div>

      <el-table :data="pagedConfigs" v-loading="loading" stripe class="list-table" height="100%">
        <el-table-column prop="projectName" :label="t('inspection.project')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectName || projectName(row.projectId) }}
          </template>
        </el-table-column>
        <el-table-column prop="cronExpr" :label="t('inspection.cronExpr')" min-width="150" />
        <el-table-column :label="t('inspection.enabled')" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingProjectId === row.projectId"
              @change="(value: boolean) => toggleConfig(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.nextExecTime')" width="170" align="center">
          <template #default="{ row }">
            {{ formatTimestamp(row.nextExecTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.lastExecTime')" width="170" align="center">
          <template #default="{ row }">
            {{ formatTimestamp(row.lastExecTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastMessage" :label="t('inspection.lastMessage')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.lastMessage || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.actions')" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <div class="action-icons-row">
              <el-tooltip :content="t('common.edit')" placement="top">
                <el-icon class="action-icon" @click="editConfig(row)"><Edit /></el-icon>
              </el-tooltip>
              <el-tooltip :content="t('common.delete')" placement="top">
                <el-icon class="action-icon action-icon--danger" @click="confirmRemove(row)"><Delete /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('inspection.empty')" />
        </template>
      </el-table>

      <!-- 分页 -->
      <div v-if="!loading && configs.length > 0" class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="configs.length"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </el-card>

    <!-- 编辑配置弹窗 -->
    <el-dialog v-model="editDialogVisible" :title="t('inspection.editTitle')" width="500px" @close="resetEditForm">
      <el-form ref="editFormRef" :model="editForm" :rules="rules" label-width="100px">
        <el-form-item :label="t('inspection.project')">
          <el-input :model-value="editProjectName" disabled />
        </el-form-item>
        <el-form-item :label="t('inspection.cronExpr')" prop="cronExpr">
          <el-select
            v-model="editForm.cronExpr"
            filterable
            allow-create
            default-first-option
            :placeholder="t('inspection.cronPlaceholder')"
          >
            <el-option :label="t('inspection.cronDaily')" value="0 0 2 * * *" />
            <el-option :label="t('inspection.cronWeekday')" value="0 0 2 * * MON-FRI" />
            <el-option :label="t('inspection.cronWeekly')" value="0 0 2 * * MON" />
            <el-option :label="t('inspection.cronEvery6Hours')" value="0 0 */6 * * *" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inspection.enableLabel')">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEditConfig">{{ t('inspection.ok') }}</el-button>
      </template>
    </el-dialog>

    <!-- 新增配置弹窗 -->
    <el-dialog v-model="createDialogVisible" :title="t('inspection.createTitle')" width="500px" @close="resetCreateForm">
      <el-form ref="createFormRef" :model="createForm" :rules="rules" label-width="100px">
        <el-form-item :label="t('inspection.project')" prop="projectId">
          <el-select
            v-model="createForm.projectId"
            filterable
            :placeholder="t('inspection.projectPlaceholder')"
            :loading="projectsLoading"
          >
            <el-option
              v-for="project in configurableProjects"
              :key="project.id"
              :label="project.name"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inspection.cronExpr')" prop="cronExpr">
          <el-select
            v-model="createForm.cronExpr"
            filterable
            allow-create
            default-first-option
            :placeholder="t('inspection.cronPlaceholder')"
          >
            <el-option :label="t('inspection.cronDaily')" value="0 0 2 * * *" />
            <el-option :label="t('inspection.cronWeekday')" value="0 0 2 * * MON-FRI" />
            <el-option :label="t('inspection.cronWeekly')" value="0 0 2 * * MON" />
            <el-option :label="t('inspection.cronEvery6Hours')" value="0 0 */6 * * *" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inspection.enableLabel')">
          <el-switch v-model="createForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="createSaving" @click="submitCreateConfig">{{ t('inspection.ok') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import { getProjects, type ProjectVO } from '@/api/workspace'
import {
  deleteInspectionConfig,
  listInspectionConfigs,
  saveInspectionConfig,
  setInspectionEnabled,
  type InspectionConfigVO
} from '@/api/inspection'

const { t } = useI18n()

const loading = ref(false)
const projectsLoading = ref(false)
const togglingProjectId = ref<number | null>(null)
const projects = ref<ProjectVO[]>([])
const configs = ref<InspectionConfigVO[]>([])
const currentPage = ref(1)
const pageSize = ref(Number(localStorage.getItem('jsqlint_page_size')) || 10)

const editDialogVisible = ref(false)
const editSaving = ref(false)
const editingProjectId = ref<number | null>(null)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  cronExpr: '0 0 2 * * *',
  enabled: true
})

const createDialogVisible = ref(false)
const createSaving = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  projectId: undefined as number | undefined,
  cronExpr: '0 0 2 * * *',
  enabled: true
})

const rules: FormRules = {
  projectId: [{ required: true, message: t('inspection.projectRequired'), trigger: 'change' }],
  cronExpr: [
    { required: true, message: t('inspection.cronRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const parts = String(value || '').trim().split(/\s+/)
        if (parts.length !== 6) {
          callback(new Error(t('inspection.cronInvalid')))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

const configuredProjectIds = computed(() => new Set(configs.value.map(item => item.projectId)))
const editProjectName = computed(() => {
  if (!editingProjectId.value) {
    return ''
  }
  const config = configs.value.find(item => item.projectId === editingProjectId.value)
  return config?.projectName || projectName(editingProjectId.value)
})
const configurableProjects = computed(() => {
  return projects.value.filter(project => project.id === createForm.projectId || !configuredProjectIds.value.has(project.id))
})
const enabledCount = computed(() => configs.value.filter(item => item.enabled).length)
const runningCount = computed(() => configs.value.filter(item => item.lastStatus === 'RUNNING').length)
const failedCount = computed(() => configs.value.filter(item => item.lastStatus === 'FAILED').length)
const pagedConfigs = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return configs.value.slice(start, start + pageSize.value)
})

const loadData = async () => {
  loading.value = true
  projectsLoading.value = true
  try {
    const [projectData, configData] = await Promise.all([
      getProjects(),
      listInspectionConfigs()
    ])
    projects.value = projectData
    configs.value = configData
    // 数据量缩小后当前页可能越界，重置到第一页
    if ((currentPage.value - 1) * pageSize.value >= configData.length) {
      currentPage.value = 1
    }
  } finally {
    loading.value = false
    projectsLoading.value = false
  }
}

const openCreateDialog = () => {
  createDialogVisible.value = true
}

const resetCreateForm = () => {
  createForm.projectId = undefined
  createForm.cronExpr = '0 0 2 * * *'
  createForm.enabled = true
  createFormRef.value?.clearValidate()
}

const submitCreateConfig = async () => {
  await createFormRef.value?.validate()
  if (!createForm.projectId) {
    return
  }
  createSaving.value = true
  try {
    await saveInspectionConfig(createForm.projectId, {
      cronExpr: createForm.cronExpr.trim(),
      enabled: createForm.enabled
    })
    ElMessage.success(t('inspection.saveSuccess'))
    createDialogVisible.value = false
    await loadData()
  } finally {
    createSaving.value = false
  }
}

const editConfig = (row: InspectionConfigVO) => {
  editingProjectId.value = row.projectId
  editForm.cronExpr = row.cronExpr
  editForm.enabled = row.enabled
  editDialogVisible.value = true
}

const resetEditForm = () => {
  editingProjectId.value = null
  editForm.cronExpr = '0 0 2 * * *'
  editForm.enabled = true
  editFormRef.value?.clearValidate()
}

const submitEditConfig = async () => {
  await editFormRef.value?.validate()
  if (!editingProjectId.value) {
    return
  }
  editSaving.value = true
  try {
    await saveInspectionConfig(editingProjectId.value, {
      cronExpr: editForm.cronExpr.trim(),
      enabled: editForm.enabled
    })
    ElMessage.success(t('inspection.saveSuccess'))
    editDialogVisible.value = false
    await loadData()
  } finally {
    editSaving.value = false
  }
}

const toggleConfig = async (row: InspectionConfigVO, enabled: boolean) => {
  togglingProjectId.value = row.projectId
  try {
    await setInspectionEnabled(row.projectId, enabled)
    ElMessage.success(enabled ? t('inspection.enableSuccess') : t('inspection.disableSuccess'))
    await loadData()
  } finally {
    togglingProjectId.value = null
  }
}

const confirmRemove = (row: InspectionConfigVO) => {
  ElMessageBox.confirm(t('inspection.deleteConfirm', { name: projectName(row.projectId) }), t('inspection.deleteTitle'), {
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }).then(async () => {
    await deleteInspectionConfig(row.projectId)
    ElMessage.success(t('inspection.deleteSuccess'))
    if (createForm.projectId === row.projectId) {
      resetCreateForm()
    }
    await loadData()
  }).catch(() => {
    // 用户取消
  })
}

const projectName = (projectId: number) => {
  return projects.value.find(project => project.id === projectId)?.name || t('inspection.projectFallback', { id: projectId })
}

const formatTimestamp = (value?: number) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

onMounted(loadData)
</script>

<style lang="scss" scoped>
// 满高布局：根拉满内容区高度，卡片占满，表格弹性填充剩余空间（内部滚动）
.list-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
}

.list-table {
  flex: 1;
  min-height: 0;
}

.filter-toolbar {
  flex-shrink: 0;
}

.pagination {
  flex-shrink: 0;
}

.inspection {
  height: 100%;
  display: flex;
  flex-direction: column;

  // 本页工具栏只剩按钮与统计文字，去掉全局 filter-toolbar 的灰底与边框
  .filter-toolbar {
    padding: 0;
    background: transparent;
    border: none;
  }

  .summary-inline {
    gap: 12px;
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 0;
    border-top: 1px solid $border-light;
  }
}

@media (max-width: 768px) {
  .inspection {
    .summary-inline {
      flex-wrap: wrap;
    }
  }
}
</style>
