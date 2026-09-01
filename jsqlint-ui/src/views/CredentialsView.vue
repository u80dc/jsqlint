<template>
  <div class="credentials">
    <el-card>
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item :label="t('credentials.status')">
            <el-select v-model="filters.status" :placeholder="t('credentials.statusPlaceholder')" clearable style="width: 140px">
              <el-option :label="t('common.enable')" :value="1" />
              <el-option :label="t('common.disable')" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">{{ t('credentials.search') }}</el-button>
            <el-button @click="handleReset">{{ t('common.reset') }}</el-button>
          </el-form-item>
        </el-form>
        <div class="filter-toolbar__actions">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            {{ t('credentials.addCredential') }}
          </el-button>
        </div>
      </div>

      <el-table :data="credentials" v-loading="loading" stripe>
        <el-table-column prop="name" :label="t('credentials.name')" />
        <el-table-column prop="visibilityDescription" :label="t('credentials.visibility')" />
        <el-table-column prop="account" :label="t('credentials.account')">
          <template #default="{ row }">
            {{ row.account || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="createUserName" :label="t('credentials.creator')" />
        <el-table-column prop="createTime" :label="t('credentials.createTime')" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('credentials.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? t('common.enable') : t('common.disable') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('credentials.actions')" width="220">
          <template #default="{ row }">
            <span class="action-btn" @click="handleEdit(row)">{{ t('common.edit') }}</span>
            <span class="action-btn" :class="row.status === 1 ? 'action-btn--warning' : 'action-btn--success'" @click="handleToggle(row)">
              {{ row.status === 1 ? t('common.disable') : t('common.enable') }}
            </span>
            <span class="action-btn action-btn--danger" @click="handleDelete(row)">{{ t('common.delete') }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('credentials.empty')" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增/编辑凭证弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('credentials.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('credentials.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('credentials.visibility')" prop="visibility">
          <el-select v-model="form.visibility" :placeholder="t('credentials.visibilityPlaceholder')" style="width: 100%">
            <el-option :label="t('credentials.public')" value="1" />
            <el-option :label="t('credentials.private')" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('credentials.authType')" prop="authType">
          <el-radio-group v-model="form.authType">
            <el-radio value="TOKEN">Token</el-radio>
            <el-radio value="PASSWORD">{{ t('credentials.passwordAuth') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Token" v-if="form.authType === 'TOKEN'" prop="token">
          <el-input v-model="form.token" :placeholder="t('credentials.tokenPlaceholder')" />
        </el-form-item>
        <template v-if="form.authType === 'PASSWORD'">
          <el-form-item :label="t('credentials.account')" prop="account">
            <el-input v-model="form.account" :placeholder="t('credentials.accountPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('credentials.password')" prop="password">
            <el-input v-model="form.password" type="password" :placeholder="t('credentials.passwordPlaceholder')" show-password />
          </el-form-item>
        </template>
        <el-form-item :label="t('credentials.enableLabel')">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">{{ t('credentials.ok') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listCredentials, getCredentialById, createCredential, updateCredential, deleteCredential, toggleCredential } from '@/api/credential'
import type { CredentialVO, CredentialDTO } from '@/api/credential'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const { t } = useI18n()

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
  name: [{ required: true, message: t('credentials.nameRequired'), trigger: 'blur' }],
  authType: [{ required: true, message: t('credentials.authTypeRequired'), trigger: 'change' }],
  token: [{ required: true, message: t('credentials.tokenRequired'), trigger: 'blur' }],
  account: [{ required: true, message: t('credentials.accountRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('credentials.passwordRequired'), trigger: 'blur' }]
}

const dialogTitle = computed(() => isEdit.value ? t('credentials.editTitle') : t('credentials.addTitle'))

const formatDate = formatDateTime

const loadData = async () => {
  loading.value = true
  try {
    const data = await listCredentials(filters.status ?? undefined)
    credentials.value = data
  } catch {
    ElMessage.error(t('credentials.loadError'))
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
    ElMessage.error(t('credentials.loadDetailError'))
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
      ElMessage.success(t('credentials.updateSuccess'))
    } else {
      await createCredential(submitData)
      ElMessage.success(t('credentials.createSuccess'))
    }
    dialogVisible.value = false
    loadData()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? t('credentials.updateError') : t('credentials.createError'))
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: CredentialVO) => {
  try {
    const newStatus = row.status === 1 ? 0 : 1
    await toggleCredential(row.id, newStatus)
    ElMessage.success(row.status === 1 ? t('credentials.disableSuccess') : t('credentials.enableSuccess'))
    loadData()
  } catch {
    ElMessage.error(t('credentials.actionFailed'))
  }
}

const handleDelete = async (row: CredentialVO) => {
  try {
    await ElMessageBox.confirm(t('credentials.deleteConfirm'), t('credentials.deleteTitle'), {
      type: 'warning'
    })
    await deleteCredential(row.id)
    ElMessage.success(t('credentials.deleteSuccess'))
    loadData()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t('credentials.deleteError'))
    }
  }
}

onMounted(() => {
  loadData()
})
</script>
