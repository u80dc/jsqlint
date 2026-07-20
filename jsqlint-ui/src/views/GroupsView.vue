<template>
  <div class="groups">
    <component :is="embedded ? 'div' : 'el-card'" class="groups-panel">
      <template v-if="!embedded" #header>
        <div class="card-header">
          <span></span>
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            新增用户组
          </el-button>
        </div>
      </template>

      <div v-if="embedded" class="card-header card-header--embedded">
        <span></span>
        <el-button type="primary" @click="handleAdd">
          <el-icon><Plus /></el-icon>
          新增用户组
        </el-button>
      </div>

      <el-table :data="groups" v-loading="loading" stripe>
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="description" label="描述" min-width="200" />
        <el-table-column prop="creatorName" label="创建者" width="100" />
        <el-table-column label="成员数" prop="memberCount" width="80" />
        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <span class="action-btn" @click="handleEdit(row)">编辑</span>
            <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
          </template>
        </el-table-column>
      </el-table>
    </component>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入用户组名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入描述" />
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
import { getUserGroups, createUserGroup, updateUserGroup, deleteUserGroup } from '@/api/userGroup'
import type { UserGroupVO, UserGroupCreateDTO, UserGroupUpdateDTO } from '@/api/userGroup'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

defineProps<{
  embedded?: boolean
}>()

const loading = ref(false)
const submitLoading = ref(false)
const groups = ref<UserGroupVO[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  id: 0,
  name: '',
  description: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入用户组名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => isEdit.value ? '编辑用户组' : '新增用户组')

const formatDate = formatDateTime

const loadGroups = async () => {
  loading.value = true
  try {
    groups.value = await getUserGroups()
  } catch {
    ElMessage.error('加载用户组列表失败')
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.id = 0
  form.name = ''
  form.description = ''
  formRef.value?.clearValidate()
}

const handleAdd = () => {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: UserGroupVO) => {
  isEdit.value = true
  form.id = row.id
  form.name = row.name
  form.description = row.description || ''
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
      const data: UserGroupUpdateDTO = {
        id: form.id,
        name: form.name,
        description: form.description || undefined
      }
      await updateUserGroup(form.id, data)
      ElMessage.success('更新成功')
    } else {
      const data: UserGroupCreateDTO = {
        name: form.name,
        description: form.description || undefined
      }
      await createUserGroup(data)
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    loadGroups()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (row: UserGroupVO) => {
  try {
    await ElMessageBox.confirm(`确定要删除用户组「${row.name}」吗？`, '提示', {
      type: 'warning'
    })
    await deleteUserGroup(row.id)
    ElMessage.success('删除成功')
    loadGroups()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  loadGroups()
})
</script>

<style lang="scss" scoped>
.groups {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    &--embedded {
      margin-bottom: 16px;
    }
  }
}
</style>
