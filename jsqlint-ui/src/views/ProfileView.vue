<template>
  <div class="profile">
    <el-card v-loading="loading">
      <template #header>
        <span>个人信息</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户名">{{ userInfo?.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ userInfo?.realName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ userInfo?.roleDescription || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag type="success">启用</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ formatDateTime(userInfo?.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="手机号码">{{ userInfo?.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ userInfo?.email || '-' }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatDateTime(userInfo?.updateTime) }}</el-descriptions-item>
        <el-descriptions-item label="所属用户组" :span="2">
          <template v-if="groupNames.length">
            <el-tag v-for="name in groupNames" :key="name" class="group-tag" size="small">
              {{ name }}
            </el-tag>
          </template>
          <template v-else>-</template>
        </el-descriptions-item>
      </el-descriptions>
      <div class="profile-actions">
        <el-button type="primary" @click="openPasswordDialog">修改密码</el-button>
        <el-button @click="openEditDialog">编辑信息</el-button>
      </div>
    </el-card>

    <!-- 修改密码弹窗 -->
    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="440px" @close="resetPasswordForm">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="80px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑信息弹窗 -->
    <el-dialog v-model="editDialogVisible" title="编辑信息" width="440px" @close="resetEditForm">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="80px">
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="editForm.realName" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="手机号码" prop="phone">
          <el-input v-model="editForm.phone" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import {
  changePassword,
  getUserInfo,
  updateProfile,
  type UserInfoVO
} from '@/api/auth'
import { getMyUserGroups } from '@/api/userGroup'
import { formatDateTime } from '@/utils/date'

const loading = ref(false)
const userInfo = ref<UserInfoVO>()
const groups = ref<{ id: number; name: string }[]>([])

const groupNames = computed(() => groups.value.map(group => group.name))

const passwordDialogVisible = ref(false)
const passwordSaving = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '新密码长度须为6-64位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

const editDialogVisible = ref(false)
const editSaving = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  realName: '',
  email: '',
  phone: ''
})

const editRules: FormRules = {
  realName: [{ max: 64, message: '姓名长度不能超过64个字符', trigger: 'blur' }],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 128, message: '邮箱长度不能超过128个字符', trigger: 'blur' }
  ],
  phone: [{ max: 20, message: '手机号长度不能超过20个字符', trigger: 'blur' }]
}

const loadUserInfo = async () => {
  const [info, myGroups] = await Promise.all([
    getUserInfo(),
    getMyUserGroups()
  ])
  userInfo.value = info
  groups.value = myGroups
}

onMounted(async () => {
  loading.value = true
  try {
    await loadUserInfo()
  } finally {
    loading.value = false
  }
})

const openPasswordDialog = () => {
  passwordDialogVisible.value = true
}

const resetPasswordForm = () => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordFormRef.value?.clearValidate()
}

const submitPassword = async () => {
  await passwordFormRef.value?.validate()
  passwordSaving.value = true
  try {
    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码修改成功')
    passwordDialogVisible.value = false
  } finally {
    passwordSaving.value = false
  }
}

const openEditDialog = () => {
  editForm.realName = userInfo.value?.realName || ''
  editForm.email = userInfo.value?.email || ''
  editForm.phone = userInfo.value?.phone || ''
  editDialogVisible.value = true
}

const resetEditForm = () => {
  editForm.realName = ''
  editForm.email = ''
  editForm.phone = ''
  editFormRef.value?.clearValidate()
}

const submitEdit = async () => {
  await editFormRef.value?.validate()
  editSaving.value = true
  try {
    userInfo.value = await updateProfile({
      realName: editForm.realName.trim(),
      email: editForm.email.trim(),
      phone: editForm.phone.trim()
    })
    ElMessage.success('个人信息已更新')
    editDialogVisible.value = false
  } finally {
    editSaving.value = false
  }
}
</script>

<style lang="scss" scoped>
.profile {
  .profile-actions {
    margin-top: 20px;
    display: flex;
    gap: 12px;
  }

  .group-tag {
    margin-right: 8px;

    &:last-child {
      margin-right: 0;
    }
  }
}
</style>