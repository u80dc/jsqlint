<template>
  <div class="rules">
    <el-card>
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="规则名称">
            <el-input v-model="filters.name" placeholder="请输入规则名称" clearable style="width: 220px" />
          </el-form-item>
          <el-form-item label="分类">
            <el-select v-model="filters.category" placeholder="请选择" clearable style="width: 150px">
              <el-option label="SQL质量" value="SQL_QUALITY" />
              <el-option label="安全检查" value="SECURITY" />
              <el-option label="性能优化" value="PERFORMANCE" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.enabled" placeholder="请选择" clearable style="width: 120px">
              <el-option label="启用" :value="true" />
              <el-option label="禁用" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="filter-toolbar__actions">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            新增规则
          </el-button>
        </div>
      </div>

      <el-table :data="ruleList" v-loading="loading" stripe>
        <el-table-column prop="name" label="规则名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="code" label="规则代码" min-width="120" />
        <el-table-column prop="categoryDescription" label="分类" width="100" align="center" />
        <el-table-column prop="severityDescription" label="严重级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ row.severityDescription }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="规则描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="suggestion" label="修改建议" min-width="180" show-overflow-tooltip />
        <el-table-column prop="enabled" label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabledFlag" @change="handleToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="createUserName" label="创建人" width="100" align="center" />
        <el-table-column prop="updateTime" label="更新时间" width="160" align="center" />
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <span class="action-btn" @click="handleEdit(row)">编辑</span>
            <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
          </template>
        </el-table-column>
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
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="规则代码" prop="code" v-if="!isEdit">
          <el-input v-model="form.code" placeholder="请输入规则代码，如 SQL_INJECTION_CHECK" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" placeholder="请选择分类" style="width: 100%">
            <el-option label="SQL质量" value="SQL_QUALITY" />
            <el-option label="安全检查" value="SECURITY" />
            <el-option label="性能优化" value="PERFORMANCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重级别" prop="severity">
          <el-select v-model="form.severity" placeholder="请选择严重级别" style="width: 100%">
            <el-option label="致命" value="critical" />
            <el-option label="高危" value="high" />
            <el-option label="警告" value="warning" />
            <el-option label="信息" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="请输入规则描述" />
        </el-form-item>
        <el-form-item label="修改建议" prop="suggestion">
          <el-input v-model="form.suggestion" type="textarea" :rows="2" placeholder="请输入修改建议" />
        </el-form-item>
        <el-form-item label="Drools规则" prop="droolsContent">
          <el-input
            v-model="form.droolsContent"
            type="textarea"
            :rows="8"
            placeholder="请输入Drools规则内容，示例：
rule &quot;ExampleRule&quot;
    when
        $fact : model.drools.cn.jnu.zhangcs.jsqlint.server.SqlAuditFact(sql.toUpperCase().contains(&quot;CONCAT(&quot;))
    then
        $fact.setRiskLevel(model.drools.cn.jnu.zhangcs.jsqlint.server.RiskLevel.WARNING);
        $fact.addRiskFactor(&quot;检测到CONCAT函数使用&quot;);
end"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getAuditRules,
  createAuditRule,
  updateAuditRule,
  deleteAuditRule,
  toggleAuditRule
} from '@/api/auditRule'
import type { AuditRuleVO, AuditRuleListParams } from '@/api/auditRule'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const loading = ref(false)
const submitLoading = ref(false)
const ruleList = ref<AuditRuleVO[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number>(0)
const formRef = ref<FormInstance>()

const currentPage = ref(1)
const pageSize = ref(10)
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
  droolsContent: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入规则代码', trigger: 'blur' },
    { pattern: /^[A-Z_][A-Z0-9_]*$/, message: '规则代码必须是大写字母、数字和下划线组成', trigger: 'blur' }
  ],
  severity: [{ required: true, message: '请选择严重级别', trigger: 'change' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  droolsContent: [{ required: true, message: '请输入Drools规则内容', trigger: 'blur' }]
}

const dialogTitle = computed(() => isEdit.value ? '编辑规则' : '新增规则')

const getSeverityType = (severity: string) => {
  const types: Record<string, string> = {
    critical: 'danger',
    high: 'warning',
    warning: 'warning',
    info: 'info'
  }
  return types[severity] || 'info'
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
    ElMessage.error('加载规则列表失败')
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
        droolsContent: form.droolsContent
      })
      ElMessage.success('更新成功')
    } else {
      await createAuditRule({
        name: form.name,
        code: form.code,
        description: form.description,
        suggestion: form.suggestion,
        severity: form.severity,
        category: form.category,
        droolsContent: form.droolsContent
      })
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    loadRules()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: AuditRuleVO) => {
  const newEnabled = row.enabledFlag ?? false
  try {
    await toggleAuditRule(row.id, newEnabled)
    ElMessage.success(`${row.name} 已${newEnabled ? '启用' : '禁用'}`)
  } catch {
    row.enabledFlag = !newEnabled
    ElMessage.error('切换状态失败')
  }
}

const handleDelete = async (row: AuditRuleVO) => {
  try {
    await ElMessageBox.confirm(`确定要删除规则「${row.name}」吗？删除后无法恢复。`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteAuditRule(row.id)
    ElMessage.success('删除成功')
    loadRules()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  loadRules()
})
</script>

<style lang="scss" scoped>
.rules {
  .pagination {
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 0;
    margin-top: 16px;
  }
}
</style>
