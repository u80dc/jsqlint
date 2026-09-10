<template>
  <div class="rules">
    <el-card class="rules-card">
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item :label="t('rules.name')">
            <el-input v-model="filters.name" :placeholder="t('rules.namePlaceholder')" clearable style="width: 220px" />
          </el-form-item>
          <el-form-item :label="t('rules.category')">
            <el-select v-model="filters.category" :placeholder="t('rules.selectPlaceholder')" clearable style="width: 150px">
              <el-option :label="t('rules.categoryOptions.quality')" value="SQL_QUALITY" />
              <el-option :label="t('rules.categoryOptions.security')" value="SECURITY" />
              <el-option :label="t('rules.categoryOptions.performance')" value="PERFORMANCE" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('rules.status')">
            <el-select v-model="filters.enabled" :placeholder="t('rules.selectPlaceholder')" clearable style="width: 120px">
              <el-option :label="t('common.enable')" :value="true" />
              <el-option :label="t('common.disable')" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">{{ t('rules.query') }}</el-button>
            <el-button @click="handleReset">{{ t('common.reset') }}</el-button>
          </el-form-item>
        </el-form>
        <div class="filter-toolbar__actions">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            {{ t('rules.addRule') }}
          </el-button>
        </div>
      </div>

      <el-table :data="ruleList" v-loading="loading" stripe class="rules-table" height="100%">
        <el-table-column prop="name" :label="t('rules.name')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <el-tag v-if="row.isBuiltin === 1" size="small" effect="plain" class="builtin-tag">
              {{ t('rules.builtin') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="code" :label="t('rules.code')" min-width="120" />
        <el-table-column prop="categoryDescription" :label="t('rules.category')" width="100" align="center" />
        <el-table-column :label="t('rules.dialect')" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.dialect" size="small" effect="plain">{{ dialectLabel(row.dialect) }}</el-tag>
            <span v-else class="dialect-none">{{ t('rules.dialectOptions.all') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="severityDescription" :label="t('rules.severityLevel')" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ row.severityDescription }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" :label="t('rules.description')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="suggestion" :label="t('rules.suggestion')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="enabled" :label="t('rules.status')" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabledFlag" @change="handleToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="createUserName" :label="t('rules.creator')" width="100" align="center" />
        <el-table-column prop="updateTime" :label="t('rules.updateTime')" width="160" align="center" />
        <el-table-column :label="t('rules.operation')" width="100" align="center" fixed="right">
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
          <el-empty :description="t('rules.empty')" />
        </template>
      </el-table>

      <div class="pagination" v-if="total > 0">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="loadRules"
        />
      </div>
    </el-card>

    <!-- 新增/编辑规则弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="700px" align-center @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('rules.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('rules.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('rules.code')" prop="code" v-if="!isEdit">
          <el-input v-model="form.code" :placeholder="t('rules.codePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('rules.category')" prop="category">
          <el-select v-model="form.category" :placeholder="t('rules.categoryPlaceholder')" style="width: 100%">
            <el-option :label="t('rules.categoryOptions.quality')" value="SQL_QUALITY" />
            <el-option :label="t('rules.categoryOptions.security')" value="SECURITY" />
            <el-option :label="t('rules.categoryOptions.performance')" value="PERFORMANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('rules.dialect')" prop="dialect">
          <el-select v-model="form.dialect" :placeholder="t('rules.dialectPlaceholder')" clearable style="width: 100%">
            <el-option :label="t('rules.dialectOptions.all')" value="" />
            <el-option label="MySQL" value="mysql" />
            <el-option label="PostgreSQL" value="postgresql" />
            <el-option label="Oracle" value="oracle" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('rules.severityLevel')" prop="severity">
          <el-select v-model="form.severity" :placeholder="t('rules.severityPlaceholder')" style="width: 100%">
            <el-option :label="t('severity.critical')" value="critical" />
            <el-option :label="t('severity.high')" value="high" />
            <el-option :label="t('severity.warning')" value="warning" />
            <el-option :label="t('severity.info')" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('rules.description')" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" :placeholder="t('rules.descriptionPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('rules.suggestion')" prop="suggestion">
          <el-input v-model="form.suggestion" type="textarea" :rows="2" :placeholder="t('rules.suggestionPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('rules.droolsContent')" prop="droolsContent">
          <el-input
            v-model="form.droolsContent"
            type="textarea"
            :rows="8"
            :placeholder="t('rules.droolsPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">{{ t('rules.ok') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import {
  getAuditRules,
  createAuditRule,
  updateAuditRule,
  deleteAuditRule,
  toggleAuditRule
} from '@/api/auditRule'
import type { AuditRuleVO, AuditRuleListParams } from '@/api/auditRule'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const { t } = useI18n()

const loading = ref(false)
const submitLoading = ref(false)
const ruleList = ref<AuditRuleVO[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number>(0)
const formRef = ref<FormInstance>()

const currentPage = ref(1)
const pageSize = ref(Number(localStorage.getItem('jsqlint_page_size')) || 10)
const total = ref(0)

const filters = reactive({
  name: '',
  category: '',
  enabled: null as boolean | null
})

const form = reactive({
  name: '',
  code: '',
  description: '',
  suggestion: '',
  severity: 'warning',
  category: 'SQL_QUALITY',
  dialect: '',
  droolsContent: ''
})

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('rules.validation.nameRequired'), trigger: 'blur' }],
  code: [
    { required: true, message: t('rules.validation.codeRequired'), trigger: 'blur' },
    { pattern: /^[A-Z_][A-Z0-9_]*$/, message: t('rules.validation.codePattern'), trigger: 'blur' }
  ],
  severity: [{ required: true, message: t('rules.validation.severityRequired'), trigger: 'change' }],
  category: [{ required: true, message: t('rules.validation.categoryRequired'), trigger: 'change' }],
  droolsContent: [{ required: true, message: t('rules.validation.droolsRequired'), trigger: 'blur' }]
}))

const dialogTitle = computed(() => isEdit.value ? t('rules.editTitle') : t('rules.createTitle'))

const getSeverityType = (severity: string) => {
  const types: Record<string, string> = {
    critical: 'danger',
    high: 'warning',
    warning: 'warning',
    info: 'info'
  }
  return types[severity] || 'info'
}

const dialectLabel = (dialect: string) => {
  const labels: Record<string, string> = {
    mysql: 'MySQL',
    postgresql: 'PostgreSQL',
    oracle: 'Oracle'
  }
  return labels[dialect] || dialect
}

const loadRules = async () => {
  loading.value = true
  try {
    const params: AuditRuleListParams = {
      page: currentPage.value,
      pageSize: pageSize.value
    }
    if (filters.name) params.name = filters.name
    if (filters.category) params.category = filters.category
    if (filters.enabled !== null) params.enabled = filters.enabled

    const data = await getAuditRules(params)
    ruleList.value = data.list.map(rule => ({
      ...rule,
      enabledFlag: rule.enabled === 1
    }))
    total.value = data.total
  } catch {
    ElMessage.error(t('rules.messages.loadError'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadRules()
}

const handleReset = () => {
  filters.name = ''
  filters.category = ''
  filters.enabled = null
  handleSearch()
}

const handleAdd = () => {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: AuditRuleVO) => {
  isEdit.value = true
  currentId.value = row.id
  form.name = row.name
  form.code = row.code
  form.description = row.description || ''
  form.suggestion = row.suggestion || ''
  form.severity = row.severity
  form.category = row.category
  form.dialect = row.dialect || ''
  form.droolsContent = row.droolsContent
  dialogVisible.value = true
}

const handleDialogClose = () => {
  resetForm()
}

const resetForm = () => {
  form.name = ''
  form.code = ''
  form.description = ''
  form.suggestion = ''
  form.severity = 'warning'
  form.category = 'SQL_QUALITY'
  form.dialect = ''
  form.droolsContent = ''
  formRef.value?.clearValidate()
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
    submitLoading.value = true

    if (isEdit.value) {
      await updateAuditRule(currentId.value, {
        name: form.name,
        description: form.description,
        suggestion: form.suggestion,
        severity: form.severity,
        category: form.category,
        dialect: form.dialect,
        droolsContent: form.droolsContent
      })
      ElMessage.success(t('rules.messages.updateSuccess'))
    } else {
      await createAuditRule({
        name: form.name,
        code: form.code,
        description: form.description,
        suggestion: form.suggestion,
        severity: form.severity,
        category: form.category,
        dialect: form.dialect,
        droolsContent: form.droolsContent
      })
      ElMessage.success(t('rules.messages.createSuccess'))
    }

    dialogVisible.value = false
    loadRules()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? t('rules.messages.updateError') : t('rules.messages.createError'))
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: AuditRuleVO) => {
  const newEnabled = row.enabledFlag ?? false
  try {
    await toggleAuditRule(row.id, newEnabled)
    ElMessage.success(newEnabled
      ? t('rules.messages.toggleEnabled', { name: row.name })
      : t('rules.messages.toggleDisabled', { name: row.name }))
  } catch {
    row.enabledFlag = !newEnabled
    ElMessage.error(t('rules.messages.toggleError'))
  }
}

const handleDelete = async (row: AuditRuleVO) => {
  try {
    await ElMessageBox.confirm(t('rules.messages.deleteConfirm', { name: row.name }), t('rules.messages.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel')
    })
    await deleteAuditRule(row.id)
    ElMessage.success(t('rules.messages.deleteSuccess'))
    loadRules()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t('rules.messages.deleteError'))
    }
  }
}

onMounted(() => {
  loadRules()
})
</script>

<style lang="scss" scoped>
// 拉满 main-content 高度：卡片占满、工具条/分页固定高、表格弹性填充剩余空间
.rules {
  height: 100%;
  display: flex;
  flex-direction: column;

  .rules-card {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;

    // 卡片 body 与内容列同向拉伸
    :deep(.el-card__body) {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
  }

  .filter-toolbar {
    flex-shrink: 0;
    margin-bottom: 12px;
  }

  .rules-table {
    flex: 1;
    min-height: 0;
  }

  // 内置规则标记：跟随规则名，弱化展示
  .builtin-tag {
    margin-left: 6px;
  }

  // 通用方言（无方言限定）弱化展示
  .dialect-none {
    color: var(--jsp-text-placeholder, #a8abb2);
  }

  .pagination {
    flex-shrink: 0;
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 0;
    margin-top: 16px;
  }
}
</style>
