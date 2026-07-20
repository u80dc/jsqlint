<template>
  <div class="alert">
    <el-card>
      <el-tabs v-model="activeTab" class="alert-tabs">
        <el-tab-pane label="告警中心" name="instances">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item label="项目">
                <el-select v-model="instanceFilters.projectId" placeholder="全部项目" clearable style="width: 180px">
                  <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="级别">
                <el-select v-model="instanceFilters.severity" placeholder="全部级别" clearable style="width: 120px">
                  <el-option label="致命" value="critical" />
                  <el-option label="高危" value="high" />
                  <el-option label="警告" value="warning" />
                  <el-option label="信息" value="info" />
                </el-select>
              </el-form-item>
              <el-form-item label="状态">
                <el-select v-model="instanceFilters.status" placeholder="全部状态" clearable style="width: 120px">
                  <el-option label="未处理" value="OPEN" />
                  <el-option label="已确认" value="ACKED" />
                  <el-option label="已解决" value="RESOLVED" />
                  <el-option label="已忽略" value="IGNORED" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadInstances">查询</el-button>
                <el-button @click="resetInstanceFilters">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button @click="loadInstances">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </div>
          </div>

          <el-table :data="instances" v-loading="instanceLoading" stripe>
            <el-table-column prop="title" label="告警标题" min-width="220" show-overflow-tooltip />
            <el-table-column prop="projectName" label="项目" width="140" show-overflow-tooltip />
            <el-table-column prop="severityDescription" label="级别" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getSeverityType(row.severity)" size="small">{{ row.severityDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="statusDescription" label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)" size="small">{{ row.statusDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="triggerCount" label="次数" width="70" align="center" />
            <el-table-column prop="lastTriggeredAt" label="最近触发" width="170" />
            <el-table-column label="操作" width="180" align="center" fixed="right">
              <template #default="{ row }">
                <span class="action-btn action-btn--info" @click="showInstance(row)">详情</span>
                <span v-if="row.status === 'OPEN'" class="action-btn action-btn--warning" @click="changeStatus(row, 'ACKED')">确认</span>
                <span v-if="row.status !== 'RESOLVED'" class="action-btn action-btn--success" @click="changeStatus(row, 'RESOLVED')">解决</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="告警规则" name="rules">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item label="项目">
                <el-select v-model="ruleFilters.projectId" placeholder="全部项目" clearable style="width: 180px">
                  <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="类型">
                <el-select v-model="ruleFilters.ruleType" placeholder="全部类型" clearable style="width: 170px">
                  <el-option label="审核问题数" value="AUDIT_ISSUE_COUNT" />
                  <el-option label="高危审核问题数" value="HIGH_RISK_AUDIT_COUNT" />
                  <el-option label="任务失败" value="TASK_FAILED" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadRules">查询</el-button>
                <el-button @click="resetRuleFilters">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button type="primary" @click="handleAdd">
                <el-icon><Plus /></el-icon>
                新增规则
              </el-button>
            </div>
          </div>

          <el-table :data="rules" v-loading="ruleLoading" stripe>
            <el-table-column prop="name" label="规则名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="ruleTypeDescription" label="类型" width="130" />
            <el-table-column prop="projectName" label="项目" width="140" show-overflow-tooltip />
            <el-table-column prop="severityDescription" label="告警级别" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getSeverityType(row.severity)" size="small">{{ row.severityDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="minSeverityDescription" label="最低问题级别" width="110" align="center" />
            <el-table-column prop="thresholdCount" label="阈值" width="70" align="center" />
            <el-table-column prop="enabled" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabledFlag" @change="handleToggle(row)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" fixed="right">
              <template #default="{ row }">
                <span class="action-btn" @click="handleEdit(row)">编辑</span>
                <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="ruleDialogVisible" :title="ruleDialogTitle" width="560px" align-center>
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="formRules" label-width="110px">
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="ruleForm.name" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="规则类型" prop="ruleType">
          <el-select v-model="ruleForm.ruleType" :disabled="isEdit" style="width: 100%">
            <el-option label="审核问题数" value="AUDIT_ISSUE_COUNT" />
            <el-option label="高危审核问题数" value="HIGH_RISK_AUDIT_COUNT" />
            <el-option label="任务失败" value="TASK_FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用项目">
          <el-select v-model="ruleForm.projectId" placeholder="全部项目" clearable style="width: 100%">
            <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="告警级别" prop="severity">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option label="致命" value="critical" />
            <el-option label="高危" value="high" />
            <el-option label="警告" value="warning" />
            <el-option label="信息" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType !== 'TASK_FAILED'" label="最低问题级别" prop="minSeverity">
          <el-select v-model="ruleForm.minSeverity" style="width: 100%">
            <el-option label="致命" value="critical" />
            <el-option label="高危" value="high" />
            <el-option label="警告" value="warning" />
            <el-option label="信息" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType !== 'TASK_FAILED'" label="触发阈值" prop="thresholdCount">
          <el-input-number v-model="ruleForm.thresholdCount" :min="1" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="通知对象">
          <el-checkbox v-model="ruleForm.notifyCreator">项目创建者</el-checkbox>
          <el-checkbox v-model="ruleForm.notifyProjectMembers">项目成员</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitRule">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="instanceDialogVisible" title="告警详情" width="640px" align-center>
      <el-descriptions v-if="currentInstance" :column="1" border>
        <el-descriptions-item label="标题">{{ currentInstance.title }}</el-descriptions-item>
        <el-descriptions-item label="项目">{{ currentInstance.projectName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="级别">{{ currentInstance.severityDescription }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentInstance.statusDescription }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ currentInstance.versionId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="任务">{{ currentInstance.taskId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="内容">{{ currentInstance.content }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { getProjects, ProjectVO } from '@/api/workspace'
import {
  AlertInstanceVO,
  AlertRuleDTO,
  AlertRuleVO,
  createAlertRule,
  deleteAlertRule,
  listAlertInstances,
  listAlertRules,
  toggleAlertRule,
  updateAlertRule,
  updateAlertStatus
} from '@/api/alert'

const activeTab = ref('instances')
const projects = ref<ProjectVO[]>([])
const instances = ref<AlertInstanceVO[]>([])
const rules = ref<AlertRuleVO[]>([])
const instanceLoading = ref(false)
const ruleLoading = ref(false)
const submitLoading = ref(false)
const ruleDialogVisible = ref(false)
const instanceDialogVisible = ref(false)
const isEdit = ref(false)
const currentRuleId = ref(0)
const currentInstance = ref<AlertInstanceVO>()
const ruleFormRef = ref<FormInstance>()

const instanceFilters = reactive({
  projectId: undefined as number | undefined,
  severity: '',
  status: ''
})

const ruleFilters = reactive({
  projectId: undefined as number | undefined,
  ruleType: ''
})

const ruleForm = reactive<AlertRuleDTO>({
  name: '',
  ruleType: 'HIGH_RISK_AUDIT_COUNT',
  projectId: null,
  severity: 'high',
  minSeverity: 'high',
  thresholdCount: 1,
  notifyProjectMembers: true,
  notifyCreator: true
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }],
  severity: [{ required: true, message: '请选择告警级别', trigger: 'change' }],
  minSeverity: [{ required: true, message: '请选择最低问题级别', trigger: 'change' }],
  thresholdCount: [{ required: true, message: '请输入触发阈值', trigger: 'change' }]
}

const ruleDialogTitle = computed(() => isEdit.value ? '编辑告警规则' : '新增告警规则')

const getSeverityType = (severity: string) => {
  const types: Record<string, string> = {
    critical: 'danger',
    high: 'warning',
    warning: 'warning',
    info: 'info'
  }
  return types[severity] || 'info'
}

const getStatusType = (status: string) => {
  const types: Record<string, string> = {
    OPEN: 'danger',
    ACKED: 'warning',
    RESOLVED: 'success',
    IGNORED: 'info'
  }
  return types[status] || 'info'
}

const loadProjects = async () => {
  projects.value = await getProjects()
}

const loadInstances = async () => {
  instanceLoading.value = true
  try {
    instances.value = await listAlertInstances({
      projectId: instanceFilters.projectId,
      severity: instanceFilters.severity || undefined,
      status: instanceFilters.status || undefined
    })
  } finally {
    instanceLoading.value = false
  }
}

const loadRules = async () => {
  ruleLoading.value = true
  try {
    rules.value = await listAlertRules({
      projectId: ruleFilters.projectId,
      ruleType: ruleFilters.ruleType || undefined
    })
  } finally {
    ruleLoading.value = false
  }
}

const resetInstanceFilters = () => {
  instanceFilters.projectId = undefined
  instanceFilters.severity = ''
  instanceFilters.status = ''
  loadInstances()
}

const resetRuleFilters = () => {
  ruleFilters.projectId = undefined
  ruleFilters.ruleType = ''
  loadRules()
}

const resetRuleForm = () => {
  ruleForm.name = ''
  ruleForm.ruleType = 'HIGH_RISK_AUDIT_COUNT'
  ruleForm.projectId = null
  ruleForm.severity = 'high'
  ruleForm.minSeverity = 'high'
  ruleForm.thresholdCount = 1
  ruleForm.notifyProjectMembers = true
  ruleForm.notifyCreator = true
  ruleFormRef.value?.clearValidate()
}

const handleAdd = () => {
  isEdit.value = false
  currentRuleId.value = 0
  resetRuleForm()
  ruleDialogVisible.value = true
}

const handleEdit = (row: AlertRuleVO) => {
  isEdit.value = true
  currentRuleId.value = row.id
  ruleForm.name = row.name
  ruleForm.ruleType = row.ruleType
  ruleForm.projectId = row.projectId || null
  ruleForm.severity = row.severity
  ruleForm.minSeverity = row.minSeverity
  ruleForm.thresholdCount = row.thresholdCount
  ruleForm.notifyProjectMembers = row.notifyProjectMembers
  ruleForm.notifyCreator = row.notifyCreator
  ruleDialogVisible.value = true
}

const submitRule = async () => {
  await ruleFormRef.value?.validate()
  submitLoading.value = true
  try {
    if (ruleForm.ruleType === 'TASK_FAILED') {
      ruleForm.thresholdCount = 1
    }
    if (isEdit.value) {
      await updateAlertRule(currentRuleId.value, ruleForm)
      ElMessage.success('更新成功')
    } else {
      await createAlertRule(ruleForm)
      ElMessage.success('创建成功')
    }
    ruleDialogVisible.value = false
    loadRules()
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: AlertRuleVO) => {
  const enabled = row.enabledFlag
  try {
    await toggleAlertRule(row.id, enabled)
    ElMessage.success(`${row.name} 已${enabled ? '启用' : '禁用'}`)
  } catch {
    row.enabledFlag = !enabled
  }
}

const handleDelete = async (row: AlertRuleVO) => {
  await ElMessageBox.confirm(`确定删除规则「${row.name}」吗？`, '确认删除', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await deleteAlertRule(row.id)
  ElMessage.success('删除成功')
  loadRules()
}

const changeStatus = async (row: AlertInstanceVO, status: string) => {
  await updateAlertStatus(row.id, status)
  ElMessage.success('状态已更新')
  loadInstances()
}

const showInstance = (row: AlertInstanceVO) => {
  currentInstance.value = row
  instanceDialogVisible.value = true
}

onMounted(async () => {
  await loadProjects()
  await Promise.all([loadInstances(), loadRules()])
})
</script>

<style lang="scss" scoped>
.alert {
  .alert-tabs {
    :deep(.el-tabs__content) {
      padding-top: 20px;
    }
  }

}
</style>
