<template>
  <div class="users">
    <el-card>
      <el-tabs v-model="activeTab" class="user-tabs">
        <el-tab-pane :label="t('users.userTab')" name="user">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form" :class="{ 'is-expand': advancedVisible }">
              <el-form-item :label="t('users.username')">
                <el-input v-model="filters.username" :placeholder="t('users.usernamePlaceholder')" clearable style="width: 180px" />
              </el-form-item>
              <template v-if="advancedVisible">
                <el-form-item :label="t('users.realName')">
                  <el-input v-model="filters.realName" :placeholder="t('users.realNamePlaceholder')" clearable style="width: 160px" />
                </el-form-item>
                <el-form-item :label="t('users.phone')">
                  <el-input v-model="filters.phone" :placeholder="t('users.phonePlaceholder')" clearable style="width: 160px" />
                </el-form-item>
                <el-form-item :label="t('users.email')">
                  <el-input v-model="filters.email" :placeholder="t('users.emailPlaceholder')" clearable style="width: 200px" />
                </el-form-item>
                <el-form-item :label="t('users.status')">
                  <el-radio-group v-model="filters.status" size="small">
                    <el-radio-button :value="null">{{ t('users.all') }}</el-radio-button>
                    <el-radio-button :value="1">{{ t('common.enable') }}</el-radio-button>
                    <el-radio-button :value="0">{{ t('common.disable') }}</el-radio-button>
                  </el-radio-group>
                </el-form-item>
              </template>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">{{ t('users.query') }}</el-button>
                <el-button @click="handleReset">{{ t('common.reset') }}</el-button>
                <el-button text @click="advancedVisible = !advancedVisible">
                  {{ t('users.advancedSearch') }}
                  <el-icon><ArrowDown v-if="!advancedVisible" /><ArrowUp v-else /></el-icon>
                </el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button type="primary" @click="handleAdd">
                <el-icon><Plus /></el-icon>
                {{ t('users.addUser') }}
              </el-button>
            </div>
          </div>

          <el-table :data="userList" v-loading="loading" stripe>
            <el-table-column prop="username" :label="t('users.username')" min-width="120" />
            <el-table-column prop="realName" :label="t('users.realName')" min-width="100" />
            <el-table-column prop="phone" :label="t('users.phone')" min-width="130" />
            <el-table-column prop="email" :label="t('users.email')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="groups" :label="t('users.userGroup')" width="150" align="center">
              <template #default="{ row }">
                <el-select
                  :model-value="selectedGroupIds(row)"
                  multiple
                  :placeholder="t('users.selectGroupPlaceholder')"
                  style="width: 100%"
                  @update:model-value="(val: number[]) => handleGroupChange(row, val)"
                >
                  <el-option
                    v-for="group in allGroups"
                    :key="group.id"
                    :label="group.name"
                    :value="group.id"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="roleDescription" :label="t('users.role')" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.role === 'ROLE_ADMIN' ? 'danger' : 'primary'" effect="light" size="small">
                  {{ row.roleDescription }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" :label="t('users.status')" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="light" size="small">
                  {{ row.status === 1 ? t('common.enable') : t('common.disable') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" :label="t('users.createTime')" width="170">
              <template #default="{ row }">
                {{ formatDate(row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('users.operation')" width="280" fixed="right" align="center">
              <template #default="{ row }">
                <span class="action-btn" @click="handleEdit(row)">{{ t('common.edit') }}</span>
                <span class="action-btn action-btn--warning" @click="handleResetPassword(row)">{{ t('users.resetPassword') }}</span>
                <span class="action-btn" :class="row.status === 1 ? 'action-btn--warning' : 'action-btn--success'" @click="handleToggle(row)">
                  {{ row.status === 1 ? t('common.disable') : t('common.enable') }}
                </span>
                <span class="action-btn action-btn--danger" @click="handleDelete(row)">{{ t('common.delete') }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="t('users.empty')" />
            </template>
          </el-table>

          <!-- 新增/编辑弹窗 -->
          <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" align-center @close="handleDialogClose">
            <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
              <el-form-item :label="t('users.username')" prop="username" v-if="!isEdit">
                <el-input v-model="form.username" :placeholder="t('users.usernamePlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('users.password')" prop="password" v-if="!isEdit">
                <el-input v-model="form.password" type="password" :placeholder="t('users.passwordPlaceholder')" show-password />
              </el-form-item>
              <el-form-item :label="t('users.realName')" prop="realName">
                <el-input v-model="form.realName" :placeholder="t('users.realNamePlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('users.phone')" prop="phone">
                <el-input v-model="form.phone" :placeholder="t('users.phonePlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('users.email')" prop="email">
                <el-input v-model="form.email" :placeholder="t('users.emailPlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('users.role')" prop="role">
                <el-select v-model="form.role" :placeholder="t('users.rolePlaceholder')" style="width: 100%">
                  <el-option :label="t('users.roleOptions.user')" value="ROLE_USER" />
                  <el-option :label="t('users.roleOptions.admin')" value="ROLE_ADMIN" />
                </el-select>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
              <el-button type="primary" @click="handleSubmit" :loading="submitLoading">{{ t('users.ok') }}</el-button>
            </template>
          </el-dialog>

          <!-- 重置密码弹窗 -->
          <el-dialog v-model="resetPwdDialogVisible" :title="t('users.resetPassword')" width="420px" align-center>
            <el-form ref="resetPwdFormRef" :model="resetPwdForm" :rules="resetPwdRules" label-width="100px">
              <el-form-item :label="t('users.newPassword')" prop="password">
                <el-input v-model="resetPwdForm.password" type="password" :placeholder="t('users.newPasswordPlaceholder')" show-password />
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="resetPwdDialogVisible = false">{{ t('common.cancel') }}</el-button>
              <el-button type="primary" @click="handleResetPwdSubmit" :loading="resetPwdLoading">{{ t('users.ok') }}</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <el-tab-pane :label="t('users.groupTab')" name="group">
          <GroupsView embedded />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Plus, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import {
  getUsers,
  createUser,
  updateUser,
  deleteUser,
  enableUser,
  disableUser,
  setUserGroups
} from '@/api/admin'
import type { UserVO, UserCreateDTO, UserUpdateDTO, UserListParams } from '@/api/admin'
import { getUserGroups } from '@/api/userGroup'
import type { UserGroupVO } from '@/api/userGroup'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'
import GroupsView from './GroupsView.vue'

const { t } = useI18n()

const activeTab = ref('user')

const loading = ref(false)
const submitLoading = ref(false)
const resetPwdLoading = ref(false)
const userList = ref<UserVO[]>([])
const dialogVisible = ref(false)
const resetPwdDialogVisible = ref(false)
const isEdit = ref(false)
const advancedVisible = ref(false)
const formRef = ref<FormInstance>()
const resetPwdFormRef = ref<FormInstance>()
const resetPwdUserId = ref<number>(0)
const allGroups = ref<UserGroupVO[]>([])

const resetPwdForm = reactive({
  password: ''
})

const resetPwdRules = computed<FormRules>(() => ({
  password: [
    { required: true, message: t('users.validation.newPasswordRequired'), trigger: 'blur' },
    { min: 6, message: t('users.validation.passwordMinLength'), trigger: 'blur' }
  ]
}))

const filters = reactive({
  username: '',
  realName: '',
  phone: '',
  email: '',
  status: null as number | null
})

const form = reactive({
  id: 0,
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  role: 'ROLE_USER',
  status: 1
})

const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('users.validation.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('users.validation.passwordRequired'), trigger: 'blur' }],
  realName: [{ required: true, message: t('users.validation.realNameRequired'), trigger: 'blur' }],
  role: [{ required: true, message: t('users.validation.roleRequired'), trigger: 'change' }]
}))

const dialogTitle = computed(() => isEdit.value ? t('users.editTitle') : t('users.createTitle'))

const formatDate = formatDateTime

const selectedGroupIds = (row: UserVO) => {
  return row.groups?.map(group => group.id) ?? []
}

const loadUsers = async () => {
  loading.value = true
  try {
    const params: UserListParams = {}
    if (filters.username) params.username = filters.username
    if (filters.realName) params.realName = filters.realName
    if (filters.phone) params.phone = filters.phone
    if (filters.email) params.email = filters.email
    if (filters.status !== null) params.status = filters.status

    userList.value = await getUsers(params)
  } catch {
    ElMessage.error(t('users.messages.loadUsersError'))
  } finally {
    loading.value = false
  }
}

const loadAllGroups = async () => {
  try {
    allGroups.value = await getUserGroups()
  } catch {
    ElMessage.error(t('users.messages.loadGroupsError'))
  }
}

const handleGroupChange = async (row: UserVO, selectedIds: number[]) => {
  try {
    const groupId = selectedIds.length > 0 ? selectedIds[0] : undefined
    await setUserGroups(row.id, groupId)
    ElMessage.success(t('users.messages.groupSetSuccess'))
    loadUsers()
  } catch {
    ElMessage.error(t('users.messages.groupSetError'))
    loadUsers()
  }
}

const handleSearch = () => {
  loadUsers()
}

const handleReset = () => {
  filters.username = ''
  filters.realName = ''
  filters.phone = ''
  filters.email = ''
  filters.status = null
  loadUsers()
}

const resetForm = () => {
  form.id = 0
  form.username = ''
  form.password = ''
  form.realName = ''
  form.phone = ''
  form.email = ''
  form.role = 'ROLE_USER'
  form.status = 1
  formRef.value?.clearValidate()
}

const handleAdd = () => {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: UserVO) => {
  isEdit.value = true
  form.id = row.id
  form.username = row.username
  form.password = ''
  form.realName = row.realName || ''
  form.phone = row.phone || ''
  form.email = row.email || ''
  form.role = row.role
  form.status = row.status
  dialogVisible.value = true
}

const handleDialogClose = () => {
  resetForm()
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
    submitLoading.value = true

    if (isEdit.value) {
      const data: UserUpdateDTO = {
        id: form.id,
        realName: form.realName,
        phone: form.phone || undefined,
        email: form.email || undefined,
        role: form.role,
        status: form.status
      }
      if (form.password) {
        data.password = form.password
      }
      await updateUser(data)
      ElMessage.success(t('users.messages.updateSuccess'))
    } else {
      const data: UserCreateDTO = {
        username: form.username,
        password: form.password,
        realName: form.realName,
        phone: form.phone || undefined,
        email: form.email || undefined,
        role: form.role,
        status: form.status
      }
      await createUser(data)
      ElMessage.success(t('users.messages.createSuccess'))
    }

    dialogVisible.value = false
    loadUsers()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? t('users.messages.updateError') : t('users.messages.createError'))
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: UserVO) => {
  const disabling = row.status === 1
  try {
    await ElMessageBox.confirm(
      t(disabling ? 'users.messages.toggleConfirmDisable' : 'users.messages.toggleConfirmEnable', { username: row.username }),
      t('users.messages.toggleTitle'),
      {
        type: 'warning'
      }
    )
    if (disabling) {
      await disableUser(row.id)
      ElMessage.success(t('users.messages.disableSuccess'))
    } else {
      await enableUser(row.id)
      ElMessage.success(t('users.messages.enableSuccess'))
    }
    loadUsers()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t(disabling ? 'users.messages.toggleErrorDisable' : 'users.messages.toggleErrorEnable'))
    }
  }
}

const handleDelete = async (row: UserVO) => {
  try {
    await ElMessageBox.confirm(t('users.messages.deleteConfirm', { username: row.username }), t('users.messages.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel')
    })
    await deleteUser(row.id)
    ElMessage.success(t('users.messages.deleteSuccess'))
    loadUsers()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t('users.messages.deleteError'))
    }
  }
}

onMounted(() => {
  loadUsers()
  loadAllGroups()
})

const handleResetPassword = (row: UserVO) => {
  resetPwdUserId.value = row.id
  resetPwdForm.password = ''
  resetPwdFormRef.value?.clearValidate()
  resetPwdDialogVisible.value = true
}

const handleResetPwdSubmit = async () => {
  try {
    await resetPwdFormRef.value?.validate()
    resetPwdLoading.value = true

    const data: UserUpdateDTO = {
      id: resetPwdUserId.value,
      password: resetPwdForm.password
    }
    await updateUser(data)
    ElMessage.success(t('users.messages.resetPwdSuccess'))
    resetPwdDialogVisible.value = false
    loadUsers()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(t('users.messages.resetPwdError'))
    }
  } finally {
    resetPwdLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.users {
  .user-tabs {
    :deep(.el-tabs__content) {
      padding-top: 20px;
    }
  }

}
</style>
