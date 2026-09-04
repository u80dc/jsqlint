<template>
  <div class="profile">
    <el-card v-loading="loading">
      <template #header>
        <span>{{ t('profile.title') }}</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="t('profile.username')">{{ userInfo?.username || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.realName')">{{ userInfo?.realName || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.role')">{{ userInfo?.roleDescription || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.status')">
          <el-tag type="success">{{ t('profile.enabled') }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('profile.registerTime')">{{ formatDateTime(userInfo?.createTime) }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.phone')">{{ userInfo?.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.email')">{{ userInfo?.email || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.updateTime')">{{ formatDateTime(userInfo?.updateTime) }}</el-descriptions-item>
        <el-descriptions-item :label="t('profile.userGroups')" :span="2">
          <template v-if="groupNames.length">
            <el-tag v-for="name in groupNames" :key="name" class="group-tag" size="small">
              {{ name }}
            </el-tag>
          </template>
          <template v-else>-</template>
        </el-descriptions-item>
      </el-descriptions>
      <div class="profile-actions">
        <el-button type="primary" @click="openPasswordDialog">{{ t('profile.changePassword') }}</el-button>
        <el-button @click="openEditDialog">{{ t('profile.editInfo') }}</el-button>
      </div>
    </el-card>

    <!-- 修改密码弹窗 -->
    <el-dialog v-model="passwordDialogVisible" :title="t('profile.changePassword')" width="440px" @close="resetPasswordForm">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
        <el-form-item :label="t('profile.oldPassword')" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item :label="t('profile.newPassword')" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item :label="t('profile.confirmPassword')" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPassword">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 编辑信息弹窗 -->
    <el-dialog v-model="editDialogVisible" :title="t('profile.editInfo')" width="440px" @close="resetEditForm">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item :label="t('profile.realName')" prop="realName">
          <el-input v-model="editForm.realName" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('profile.email')" prop="email">
          <el-input v-model="editForm.email" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('profile.phone')" prop="phone">
          <el-input v-model="editForm.phone" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()

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

const passwordRules = computed<FormRules>(() => ({
  oldPassword: [{ required: true, message: t('profile.oldPasswordRequired'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('profile.newPasswordRequired'), trigger: 'blur' },
    { min: 6, max: 64, message: t('profile.newPasswordLength'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: t('profile.confirmPasswordRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error(t('profile.passwordMismatch')))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}))

const editDialogVisible = ref(false)
const editSaving = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  realName: '',
  email: '',
  phone: ''
})

const editRules = computed<FormRules>(() => ({
  realName: [{ max: 64, message: t('profile.realNameMaxLength'), trigger: 'blur' }],
  email: [
    { type: 'email', message: t('profile.emailInvalid'), trigger: 'blur' },
    { max: 128, message: t('profile.emailMaxLength'), trigger: 'blur' }
  ],
  phone: [{ max: 20, message: t('profile.phoneMaxLength'), trigger: 'blur' }]
}))

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
    ElMessage.success(t('profile.passwordChanged'))
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
    ElMessage.success(t('profile.profileUpdated'))
    editDialogVisible.value = false
  } finally {
    editSaving.value = false
  }
}
</script>

<style lang="scss" scoped>
// 满高布局：根拉满内容区高度，卡片占满（与列表页一致的填充效果）
.profile {
  height: 100%;
  display: flex;
  flex-direction: column;

  .el-card {
    flex: 1;
  }

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