<template>
  <div class="inspection">
    <el-card>
      <div class="filter-toolbar">
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          :inline="true"
          class="filter-form"
          @submit.prevent
        >
          <el-form-item label="项目" prop="projectId">
            <el-select
              v-model="form.projectId"
              filterable
              placeholder="选择项目"
              style="width: 180px"
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
              v-model="form.cronExpr"
              filterable
              allow-create
              default-first-option
              placeholder="Spring cron 表达式"
              style="width: 180px"
            >
              <el-option label="每天 02:00" value="0 0 2 * * *" />
              <el-option label="工作日 02:00" value="0 0 2 * * MON-FRI" />
              <el-option label="每周一 02:00" value="0 0 2 * * MON" />
              <el-option label="每 6 小时" value="0 0 */6 * * *" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Check" :loading="saving" @click="submitConfig">
              保存配置
            </el-button>
            <el-button :icon="Refresh" @click="loadData" :loading="loading">刷新</el-button>
          </el-form-item>
        </el-form>
        <div class="filter-toolbar__meta summary-inline">
          <span>已配置 {{ configs.length }}</span>
          <span>已启用 {{ enabledCount }}</span>
          <span>运行中 {{ runningCount }}</span>
          <span>最近失败 {{ failedCount }}</span>
        </div>
      </div>

      <el-table :data="configs" v-loading="loading" stripe>
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
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <span class="action-btn" @click="editConfig(row)">编辑</span>
            <el-popconfirm title="删除该项目的巡检配置？" @confirm="removeConfig(row)">
              <template #reference>
                <span class="action-btn action-btn--danger">删除</span>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无巡检配置" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Check, Refresh } from '@element-plus/icons-vue'
import { getProjects, type ProjectVO } from '@/api/workspace'
import {
  deleteInspectionConfig,
  listInspectionConfigs,
  saveInspectionConfig,
  setInspectionEnabled,
  type InspectionConfigVO
} from '@/api/inspection'

const loading = ref(false)
const saving = ref(false)
const projectsLoading = ref(false)
const togglingProjectId = ref<number | null>(null)
const projects = ref<ProjectVO[]>([])
const configs = ref<InspectionConfigVO[]>([])
const formRef = ref<FormInstance>()

const form = reactive({
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
const configurableProjects = computed(() => {
  return projects.value.filter(project => project.id === form.projectId || !configuredProjectIds.value.has(project.id))
})
const enabledCount = computed(() => configs.value.filter(item => item.enabled).length)
const runningCount = computed(() => configs.value.filter(item => item.lastStatus === 'RUNNING').length)
const failedCount = computed(() => configs.value.filter(item => item.lastStatus === 'FAILED').length)

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
  } finally {
    loading.value = false
    projectsLoading.value = false
  }
}

const submitConfig = async () => {
  await formRef.value?.validate()
  if (!form.projectId) {
    return
  }
  saving.value = true
  try {
    await saveInspectionConfig(form.projectId, {
      cronExpr: form.cronExpr.trim(),
      enabled: form.enabled
    })
    ElMessage.success('巡检配置已保存')
    resetForm()
    await loadData()
  } finally {
    saving.value = false
  }
}

const editConfig = (row: InspectionConfigVO) => {
  form.projectId = row.projectId
  form.cronExpr = row.cronExpr
  form.enabled = row.enabled
  window.scrollTo({ top: 0, behavior: 'smooth' })
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
  if (form.projectId === row.projectId) {
    resetForm()
  }
  await loadData()
}

const resetForm = () => {
  form.projectId = undefined
  form.cronExpr = '0 0 2 * * *'
  form.enabled = true
  formRef.value?.clearValidate()
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
}

@media (max-width: 768px) {
  .inspection {
    .summary-inline {
      flex-wrap: wrap;
    }
  }
}
</style>
