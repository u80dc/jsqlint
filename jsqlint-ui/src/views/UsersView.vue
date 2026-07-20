<template>
  <div class="users">
    <el-card>
      <el-tabs v-model="activeTab" class="user-tabs">
        <el-tab-pane label="用户" name="user">
          <div class="filter-toolbar">
            <el-form :inline="true" class="filter-form" :class="{ 'is-expand': advancedVisible }">
              <el-form-item label="账号">
                <el-input v-model="filters.username" placeholder="请输入账号" clearable style="width: 180px" />
              </el-form-item>
              <template v-if="advancedVisible">
                <el-form-item label="姓名">
                  <el-input v-model="filters.realName" placeholder="请输入姓名" clearable style="width: 160px" />
                </el-form-item>
                <el-form-item label="手机号">
                  <el-input v-model="filters.phone" placeholder="请输入手机号" clearable style="width: 160px" />
                </el-form-item>
                <el-form-item label="邮箱">
                  <el-input v-model="filters.email" placeholder="请输入邮箱" clearable style="width: 200px" />
                </el-form-item>
                <el-form-item label="状态">
                  <el-radio-group v-model="filters.status" size="small">
                    <el-radio-button :value="null">全部</el-radio-button>
                    <el-radio-button :value="1">启用</el-radio-button>
                    <el-radio-button :value="0">禁用</el-radio-button>
                  </el-radio-group>
                </el-form-item>
              </template>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">查询</el-button>
                <el-button @click="handleReset">重置</el-button>
                <el-button text @click="advancedVisible = !advancedVisible">
                  高级搜索
                  <el-icon><ArrowDown v-if="!advancedVisible" /><ArrowUp v-else /></el-icon>
                </el-button>
              </el-form-item>
            </el-form>
            <div class="filter-toolbar__actions">
              <el-button type="primary" @click="handleAdd">
                <el-icon><Plus /></el-icon>
                新增用户
              </el-button>
            </div>
          </div>

          <el-table :data="userList" v-loading="loading" stripe>
            <el-table-column prop="username" label="账号" min-width="120" />
            <el-table-column prop="realName" label="姓名" min-width="100" />
            <el-table-column prop="phone" label="手机号" min-width="130" />
            <el-table-column prop="email" label="邮箱" min-width="200" show-overflow-tooltip />
            <el-table-column prop="groups" label="用户组" width="150" align="center">
              <template #default="{ row }">
                <el-select
                  :model-value="selectedGroupIds(row)"
                  multiple
                  placeholder="选择用户组"
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
            <el-table-column prop="roleDescription" label="角色" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.role === 'ROLE_ADMIN' ? 'danger' : 'primary'" effect="light" round size="small">
                  {{ row.roleDescription }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="light" round size="small">
                  {{ row.status === 1 ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="170">
              <template #default="{ row }">
                {{ formatDate(row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right" align="center">
              <template #default="{ row }">
                <span class="action-btn" @click="handleEdit(row)">编辑</span>
                <span class="action-btn action-btn--warning" @click="handleResetPassword(row)">重置密码</span>
                <span class="action-btn" :class="row.status === 1 ? 'action-btn--warning' : 'action-btn--success'" @click="handleToggle(row)">
                  {{ row.status === 1 ? '禁用' : '启用' }}
                </span>
                <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 新增/编辑弹窗 -->
          <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" align-center @close="handleDialogClose">
            <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
              <el-form-item label="账号" prop="username" v-if="!isEdit">
                <el-input v-model="form.username" placeholder="请输入账号" />
              </el-form-item>
              <el-form-item label="密码" prop="password" v-if="!isEdit">
                <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
              </el-form-item>
              <el-form-item label="姓名" prop="realName">
                <el-input v-model="form.realName" placeholder="请输入姓名" />
              </el-form-item>
              <el-form-item label="手机号" prop="phone">
                <el-input v-model="form.phone" placeholder="请输入手机号" />
              </el-form-item>
              <el-form-item label="邮箱" prop="email">
                <el-input v-model="form.email" placeholder="请输入邮箱" />
              </el-form-item>
              <el-form-item label="角色" prop="role">
                <el-select v-model="form.role" placeholder="请选择角色" style="width: 100%">
                  <el-option label="普通用户" value="ROLE_USER" />
                  <el-option label="管理员" value="ROLE_ADMIN" />
                </el-select>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="dialogVisible = false">取消</el-button>
              <el-button type="primary" @click="handleSubmit" :loading="submitLoading">确定</el-button>
            </template>
          </el-dialog>

          <!-- 重置密码弹窗 -->
          <el-dialog v-model="resetPwdDialogVisible" title="重置密码" width="420px" align-center>
            <el-form ref="resetPwdFormRef" :model="resetPwdForm" :rules="resetPwdRules" label-width="80px">
              <el-form-item label="新密码" prop="password">
                <el-input v-model="resetPwdForm.password" type="password" placeholder="请输入新密码" show-password />
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="resetPwdDialogVisible = false">取消</el-button>
              <el-button type="primary" @click="handleResetPwdSubmit" :loading="resetPwdLoading">确定</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <el-tab-pane label="用户组" name="group">
          <GroupsView embedded />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
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

const resetPwdRules: FormRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' }
  ]
}

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

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const dialogTitle = computed(() => isEdit.value ? '编辑用户' : '新增用户')

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
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

const loadAllGroups = async () => {
  try {
    allGroups.value = await getUserGroups()
  } catch {
    ElMessage.error('加载用户组列表失败')
  }
}

const handleGroupChange = async (row: UserVO, selectedIds: number[]) => {
  try {
    const groupId = selectedIds.length > 0 ? selectedIds[0] : undefined
    await setUserGroups(row.id, groupId)
    ElMessage.success('用户组设置成功')
    loadUsers()
  } catch {
    ElMessage.error('用户组设置失败')
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
      ElMessage.success('更新成功')
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
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    loadUsers()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  } finally {
    submitLoading.value = false
  }
}

const handleToggle = async (row: UserVO) => {
  const action = row.status === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(`确定要${action}用户「${row.username}」吗？`, '确认提示', {
      type: 'warning'
    })
    if (row.status === 1) {
      await disableUser(row.id)
      ElMessage.success('禁用成功')
    } else {
      await enableUser(row.id)
      ElMessage.success('启用成功')
    }
    loadUsers()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(`${action}失败`)
    }
  }
}

const handleDelete = async (row: UserVO) => {
  try {
    await ElMessageBox.confirm(`确定要删除用户「${row.username}」吗？删除后无法恢复。`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteUser(row.id)
    ElMessage.success('删除成功')
    loadUsers()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error('删除失败')
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
    ElMessage.success('密码重置成功')
    resetPwdDialogVisible.value = false
    loadUsers()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error('密码重置失败')
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
