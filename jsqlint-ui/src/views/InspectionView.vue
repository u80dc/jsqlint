<template>
  <div class="inspection">
    <el-card>
      <div class="filter-toolbar">
        <div class="toolbar-actions">
          <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增配置</el-button>
        </div>
        <div class="filter-toolbar__meta summary-inline">
          <span>已配置 {{ configs.length }}</span>
          <span>已启用 {{ enabledCount }}</span>
          <span>运行中 {{ runningCount }}</span>
          <span>最近失败 {{ failedCount }}</span>
        </div>
      </div>

      <el-table :data="pagedConfigs" v-loading="loading" stripe>
        <el-table-column prop="projectName" label="项目" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectName || projectName(row.projectId) }}
          </template>
        </el-table-column>
        <el-table-column prop="cronExpr" label="执行规则" min-width="150" />
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingProjectId === row.projectId"
              @change="(value: boolean) => toggleConfig(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="下次执行" width="170" align="center">
          <template #default="{ row }">
            {{ formatTimestamp(row.nextExecTime) }}
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="170" align="center">
          <template #default="{ row }">
            {{ formatTimestamp(row.lastExecTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastMessage" label="执行信息" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.lastMessage || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="编辑" placement="top">
              <el-icon class="action-icon" @click="editConfig(row)"><Edit /></el-icon>
            </el-tooltip>
            <el-popconfirm title="删除该项目的巡检配置？" @confirm="removeConfig(row)">
              <template #reference>
                <el-tooltip content="删除" placement="top">
                  <el-icon class="action-icon action-icon--danger"><Delete /></el-icon>
                </el-tooltip>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无巡检配置" />
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
    <el-dialog v-model="editDialogVisible" title="编辑巡检配置" width="500px" @close="resetEditForm">
      <el-form ref="editFormRef" :model="editForm" :rules="rules" label-width="80px">
        <el-form-item label="项目">
          <el-input :model-value="editProjectName" disabled />
        </el-form-item>
        <el-form-item label="执行规则" prop="cronExpr">
          <el-select
            v-model="editForm.cronExpr"
            filterable
            allow-create
            default-first-option
            placeholder="Spring cron 表达式"
          >
            <el-option label="每天 02:00" value="0 0 2 * * *" />
            <el-option label="工作日 02:00" value="0 0 2 * * MON-FRI" />
            <el-option label="每周一 02:00" value="0 0 2 * * MON" />
            <el-option label="每 6 小时" value="0 0 */6 * * *" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEditConfig">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增配置弹窗 -->
    <el-dialog v-model="createDialogVisible" title="新增巡检配置" width="500px" @close="resetCreateForm">
      <el-form ref="createFormRef" :model="createForm" :rules="rules" label-width="80px">
        <el-form-item label="项目" prop="projectId">
          <el-select
            v-model="createForm.projectId"
            filterable
            placeholder="选择项目"
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
        <el-form-item label="执行规则" prop="cronExpr">
          <el-select
            v-model="createForm.cronExpr"
            filterable
            allow-create
            default-first-option
            placeholder="Spring cron 表达式"
          >
            <el-option label="每天 02:00" value="0 0 2 * * *" />
            <el-option label="工作日 02:00" value="0 0 2 * * MON-FRI" />
            <el-option label="每周一 02:00" value="0 0 2 * * MON" />
            <el-option label="每 6 小时" value="0 0 */6 * * *" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="createForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" @click="submitCreateConfig">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import { getProjects, type ProjectVO } from '@/api/workspace'
import {
  deleteInspectionConfig,
  listInspectionConfigs,
  saveInspectionConfig,
  setInspectionEnabled,
  type InspectionConfigVO
} from '@/api/inspection'

const loading = ref(false)
const projectsLoading = ref(false)
const togglingProjectId = ref<number | null>(null)
const projects = ref<ProjectVO[]>([])
const configs = ref<InspectionConfigVO[]>([])
const currentPage = ref(1)
const pageSize = ref(10)

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
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
  cronExpr: [
    { required: true, message: '请输入执行调度规则', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const parts = String(value || '').trim().split(/\s+/)
        if (parts.length !== 6) {
          callback(new Error('请输入 6 段 Spring cron 表达式'))
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
    ElMessage.success('巡检配置已保存')
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
    ElMessage.success('巡检配置已保存')
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
    ElMessage.success(enabled ? '巡检已启用' : '巡检已禁用')
    await loadData()
  } finally {
    togglingProjectId.value = null
  }
}

const removeConfig = async (row: InspectionConfigVO) => {
  await deleteInspectionConfig(row.projectId)
  ElMessage.success('巡检配置已删除')
  if (createForm.projectId === row.projectId) {
    resetCreateForm()
  }
  await loadData()
}

const projectName = (projectId: number) => {
  return projects.value.find(project => project.id === projectId)?.name || `项目 ${projectId}`
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
.inspection {
  .summary-inline {
    gap: 12px;
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 0;
    border-top: 1px solid #f1f5f9;
  }

  .action-icon {
    margin: 0 6px;
    font-size: 16px;
    color: var(--el-color-primary);
    cursor: pointer;
    vertical-align: middle;

    &--danger {
      color: var(--el-color-danger);
    }

    &:hover {
      opacity: 0.75;
    }
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
