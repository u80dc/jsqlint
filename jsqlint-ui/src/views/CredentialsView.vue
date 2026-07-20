<template>
  <div class="credentials">
    <el-card>
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="状态">
            <el-select v-model="filters.status" placeholder="请选择状态" clearable style="width: 140px">
              <el-option label="启用" :value="1" />
              <el-option label="禁用" :value="0" />
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
            新增凭证
          </el-button>
        </div>
      </div>

      <el-table :data="credentials" v-loading="loading" stripe>
        <el-table-column prop="name" label="凭证名称" />
        <el-table-column prop="visibilityDescription" label="可见性" />
        <el-table-column prop="account" label="账号">
          <template #default="{ row }">
            {{ row.account || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="createUserName" label="创建人" />
        <el-table-column prop="createTime" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <span class="action-btn" @click="handleEdit(row)">编辑</span>
            <span class="action-btn" :class="row.status === 1 ? 'action-btn--warning' : 'action-btn--success'" @click="handleToggle(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </span>
            <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑凭证弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="凭证名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入凭证名称" />
        </el-form-item>
        <el-form-item label="可见性" prop="visibility">
          <el-select v-model="form.visibility" placeholder="请选择可见性" style="width: 100%">
            <el-option label="公开" value="1" />
            <el-option label="私人" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="认证方式" prop="authType">
          <el-radio-group v-model="form.authType">
            <el-radio value="TOKEN">Token</el-radio>
            <el-radio value="PASSWORD">账号密码</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Token" v-if="form.authType === 'TOKEN'" prop="token">
          <el-input v-model="form.token" placeholder="请输入个人访问令牌" />
        </el-form-item>
        <template v-if="form.authType === 'PASSWORD'">
          <el-form-item label="账号" prop="account">
            <el-input v-model="form.account" placeholder="请输入账号" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
          </el-form-item>
        </template>
        <el-form-item label="是否启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
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
import { listCredentials, getCredentialById, createCredential, updateCredential, deleteCredential, toggleCredential } from '@/api/credential'
import type { CredentialVO, CredentialDTO } from '@/api/credential'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const loading = ref(false)
const submitLoading = ref(false)
const credentials = ref<CredentialVO[]>([])

const filters = reactive({
  status: null as number | null
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '',
  authType: 'TOKEN',
  visibility: '0',
  status: 1,
  account: '',
  token: '',
  password: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入凭证名称', trigger: 'blur' }],
  authType: [{ required: true, message: '请选择认证方式', trigger: 'change' }],
  token: [{ required: true, message: '请输入Token', trigger: 'blur' }],
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const dialogTitle = computed(() => isEdit.value ? '编辑凭证' : '新增凭证')

const formatDate = formatDateTime

const loadData = async () => {
  loading.value = true
  try {
    const data = await listCredentials(filters.status ?? undefined)
    credentials.value = data
  } catch {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  loadData()
}

const handleReset = () => {
  filters.status = null
  loadData()
}

const resetForm = () => {
  form.name = ''
  form.authType = 'TOKEN'
  form.visibility = '0'
  form.status = 1
  form.account = ''
  form.token = ''
  form.password = ''
  formRef.value?.clearValidate()
}

const handleAdd = () => {
  isEdit.value = false
  currentId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: CredentialVO) => {
  isEdit.value = true
  currentId.value = row.id
  try {
    const data = await getCredentialById(row.id)
    const authType = data.token ? 'TOKEN' : 'PASSWORD'
    const visibilityStr = data.visibility === 1 ? '1' : '0'
    Object.assign(form, {
      name: data.name,
      authType,
      visibility: visibilityStr,
      status: data.status,
      account: data.account || '',
      token: data.token || '',
      password: data.password || ''
    })
    dialogVisible.value = true
  } catch {
    ElMessage.error('加载凭证信息失败')
  }
}

const handleDialogClose = () => {
  resetForm()
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
    submitLoading.value = true

    const submitData: CredentialDTO = {
      name: form.name,
      visibility: form.visibility === '1' ? 1 : 0,
      status: form.status,
      account: form.authType === 'PASSWORD' ? form.account : undefined,
      token: form.authType === 'TOKEN' ? form.token : undefined,
      password: form.authType === 'PASSWORD' ? form.password : undefined
    }

    if (isEdit.value && currentId.value) {
      await updateCredential(currentId.value, submitData)
      ElMessage.success('更新成功')
    } else {
      await createCredential(submitData)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: CredentialVO) => {
  try {
    const newStatus = row.status === 1 ? 0 : 1
    await toggleCredential(row.id, newStatus)
    ElMessage.success(`${row.status === 1 ? '禁用' : '启用'}成功`)
    loadData()
  } catch {
    ElMessage.error('操作失败')
  }
}

const handleDelete = async (row: CredentialVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该凭证吗？', '提示', {
      type: 'warning'
    })
    await deleteCredential(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  loadData()
})
</script>
