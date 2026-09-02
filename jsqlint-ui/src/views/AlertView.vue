<template>
  <div class="alert">
    <el-card>
      <el-tabs v-model="activeTab" class="alert-tabs">
        <el-tab-pane :label="t('alert.centerTab')" name="instances">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item :label="t('alert.project')">
                <el-select v-model="instanceFilters.projectId" :placeholder="t('alert.allProjects')" clearable style="width: 180px">
                  <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('alert.level')">
                <el-select v-model="instanceFilters.severity" :placeholder="t('alert.allLevels')" clearable style="width: 120px">
                  <el-option :label="t('severity.critical')" value="critical" />
                  <el-option :label="t('severity.high')" value="high" />
                  <el-option :label="t('severity.warning')" value="warning" />
                  <el-option :label="t('severity.info')" value="info" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('alert.status')">
                <el-select v-model="instanceFilters.status" :placeholder="t('alert.allStatus')" clearable style="width: 120px">
                  <el-option :label="t('alert.instanceStatus.open')" value="OPEN" />
                  <el-option :label="t('alert.instanceStatus.acked')" value="ACKED" />
                  <el-option :label="t('alert.instanceStatus.resolved')" value="RESOLVED" />
                  <el-option :label="t('alert.instanceStatus.ignored')" value="IGNORED" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadInstances">{{ t('alert.query') }}</el-button>
                <el-button @click="resetInstanceFilters">{{ t('common.reset') }}</el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button @click="loadInstances">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </div>
          </div>

          <el-table :data="instances" v-loading="instanceLoading" stripe>
            <el-table-column prop="title" :label="t('alert.alertTitle')" min-width="220" show-overflow-tooltip />
            <el-table-column prop="projectName" :label="t('alert.project')" width="140" show-overflow-tooltip />
            <el-table-column prop="severityDescription" :label="t('alert.level')" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getSeverityType(row.severity)" size="small">{{ row.severityDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="statusDescription" :label="t('alert.status')" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)" size="small">{{ row.statusDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="triggerCount" :label="t('alert.triggerCount')" width="70" align="center" />
            <el-table-column prop="lastTriggeredAt" :label="t('alert.lastTriggeredAt')" width="170" />
            <el-table-column :label="t('alert.operation')" width="130" align="center" fixed="right">
              <template #default="{ row }">
                <div class="action-icons-row">
                  <el-tooltip :content="t('alert.detail')" placement="top">
                    <el-icon class="action-icon" @click="showInstance(row)"><View /></el-icon>
                  </el-tooltip>
                  <el-tooltip v-if="row.status === 'OPEN'" :content="t('alert.ack')" placement="top">
                    <el-icon class="action-icon action-icon--warning" @click="changeStatus(row, 'ACKED')"><Warning /></el-icon>
                  </el-tooltip>
                  <el-tooltip v-if="row.status !== 'RESOLVED'" :content="t('alert.resolve')" placement="top">
                    <el-icon class="action-icon action-icon--success" @click="changeStatus(row, 'RESOLVED')"><CircleCheck /></el-icon>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="t('alert.noInstances')" />
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="t('alert.ruleTab')" name="rules">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item :label="t('alert.project')">
                <el-select v-model="ruleFilters.projectId" :placeholder="t('alert.allProjects')" clearable style="width: 180px">
                  <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('alert.type')">
                <el-select v-model="ruleFilters.ruleType" :placeholder="t('alert.allTypes')" clearable style="width: 170px">
                  <el-option :label="t('alert.ruleTypeOptions.auditIssueCount')" value="AUDIT_ISSUE_COUNT" />
                  <el-option :label="t('alert.ruleTypeOptions.highRiskAuditCount')" value="HIGH_RISK_AUDIT_COUNT" />
                  <el-option :label="t('alert.ruleTypeOptions.taskFailed')" value="TASK_FAILED" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadRules">{{ t('alert.query') }}</el-button>
                <el-button @click="resetRuleFilters">{{ t('common.reset') }}</el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button type="primary" @click="handleAdd">
                <el-icon><Plus /></el-icon>
                {{ t('alert.addRule') }}
              </el-button>
            </div>
          </div>

          <el-table :data="rules" v-loading="ruleLoading" stripe>
            <el-table-column prop="name" :label="t('alert.ruleName')" min-width="160" show-overflow-tooltip />
            <el-table-column prop="ruleTypeDescription" :label="t('alert.type')" width="130" />
            <el-table-column prop="projectName" :label="t('alert.project')" width="140" show-overflow-tooltip />
            <el-table-column prop="severityDescription" :label="t('alert.alertLevel')" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getSeverityType(row.severity)" size="small">{{ row.severityDescription }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="minSeverityDescription" :label="t('alert.minIssueLevel')" width="110" align="center" />
            <el-table-column prop="thresholdCount" :label="t('alert.threshold')" width="70" align="center" />
            <el-table-column prop="enabled" :label="t('alert.status')" width="80" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabledFlag" @change="handleToggle(row)" />
              </template>
            </el-table-column>
            <el-table-column :label="t('alert.operation')" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <div class="action-icons-row">
                  <el-tooltip :content="t('common.edit')" placement="top">
                    <el-icon class="action-icon" @click="handleEdit(row)"><Edit /></el-icon>
                  </el-tooltip>
                  <el-tooltip :content="t('common.delete')" placement="top">
                    <el-icon class="action-icon action-icon--danger" @click="handleDelete(row)"><Delete /></el-icon>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="t('alert.noRules')" />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="ruleDialogVisible" :title="ruleDialogTitle" width="560px" align-center>
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="formRules" label-width="100px">
        <el-form-item :label="t('alert.ruleName')" prop="name">
          <el-input v-model="ruleForm.name" :placeholder="t('alert.ruleNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('alert.ruleType')" prop="ruleType">
          <el-select v-model="ruleForm.ruleType" :disabled="isEdit" style="width: 100%">
            <el-option :label="t('alert.ruleTypeOptions.auditIssueCount')" value="AUDIT_ISSUE_COUNT" />
            <el-option :label="t('alert.ruleTypeOptions.highRiskAuditCount')" value="HIGH_RISK_AUDIT_COUNT" />
            <el-option :label="t('alert.ruleTypeOptions.taskFailed')" value="TASK_FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('alert.applyProject')">
          <el-select v-model="ruleForm.projectId" :placeholder="t('alert.allProjects')" clearable style="width: 100%">
            <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('alert.alertLevel')" prop="severity">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option :label="t('severity.critical')" value="critical" />
            <el-option :label="t('severity.high')" value="high" />
            <el-option :label="t('severity.warning')" value="warning" />
            <el-option :label="t('severity.info')" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType !== 'TASK_FAILED'" :label="t('alert.minIssueLevel')" prop="minSeverity">
          <el-select v-model="ruleForm.minSeverity" style="width: 100%">
            <el-option :label="t('severity.critical')" value="critical" />
            <el-option :label="t('severity.high')" value="high" />
            <el-option :label="t('severity.warning')" value="warning" />
            <el-option :label="t('severity.info')" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType !== 'TASK_FAILED'" :label="t('alert.triggerThreshold')" prop="thresholdCount">
          <el-input-number v-model="ruleForm.thresholdCount" :min="1" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('alert.notifyTarget')">
          <el-checkbox v-model="ruleForm.notifyCreator">{{ t('alert.notifyCreator') }}</el-checkbox>
          <el-checkbox v-model="ruleForm.notifyProjectMembers">{{ t('alert.notifyMembers') }}</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitRule">{{ t('alert.ok') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="instanceDialogVisible" :title="t('alert.instanceDetailTitle')" width="640px" align-center>
      <el-descriptions v-if="currentInstance" :column="1" border>
        <el-descriptions-item :label="t('alert.detailLabels.title')">{{ currentInstance.title }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.project')">{{ currentInstance.projectName || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.level')">{{ currentInstance.severityDescription }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.status')">{{ currentInstance.statusDescription }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.detailLabels.version')">{{ currentInstance.versionId || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.detailLabels.task')">{{ currentInstance.taskId || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('alert.detailLabels.content')">{{ currentInstance.content }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, FormInstance, FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Plus, Refresh, Edit, Delete, View, Warning, CircleCheck } from '@element-plus/icons-vue'
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

const { t } = useI18n()

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

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('alert.validation.nameRequired'), trigger: 'blur' }],
  ruleType: [{ required: true, message: t('alert.validation.ruleTypeRequired'), trigger: 'change' }],
  severity: [{ required: true, message: t('alert.validation.severityRequired'), trigger: 'change' }],
  minSeverity: [{ required: true, message: t('alert.validation.minSeverityRequired'), trigger: 'change' }],
  thresholdCount: [{ required: true, message: t('alert.validation.thresholdRequired'), trigger: 'change' }]
}))

const ruleDialogTitle = computed(() => isEdit.value ? t('alert.editRuleTitle') : t('alert.createRuleTitle'))

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
      ElMessage.success(t('alert.messages.updateSuccess'))
    } else {
      await createAlertRule(ruleForm)
      ElMessage.success(t('alert.messages.createSuccess'))
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
    ElMessage.success(enabled
      ? t('alert.messages.toggleEnabled', { name: row.name })
      : t('alert.messages.toggleDisabled', { name: row.name }))
  } catch {
    row.enabledFlag = !enabled
  }
}

const handleDelete = async (row: AlertRuleVO) => {
  await ElMessageBox.confirm(t('alert.messages.deleteConfirm', { name: row.name }), t('alert.messages.deleteTitle'), {
    type: 'warning',
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel')
  })
  await deleteAlertRule(row.id)
  ElMessage.success(t('alert.messages.deleteSuccess'))
  loadRules()
}

const changeStatus = async (row: AlertInstanceVO, status: string) => {
  await updateAlertStatus(row.id, status)
  ElMessage.success(t('alert.messages.statusUpdated'))
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
