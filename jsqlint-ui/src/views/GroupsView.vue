<template>
  <div class="groups">
    <component :is="embedded ? 'div' : 'el-card'" class="groups-panel">
      <template v-if="!embedded" #header>
        <div class="card-header">
          <span></span>
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            {{ t('groups.addGroup') }}
          </el-button>
        </div>
      </template>

      <div v-if="embedded" class="card-header card-header--embedded">
        <span></span>
        <el-button type="primary" @click="handleAdd">
          <el-icon><Plus /></el-icon>
          {{ t('groups.addGroup') }}
        </el-button>
      </div>

      <el-table :data="groups" v-loading="loading" stripe class="list-table" height="100%">
        <el-table-column prop="name" :label="t('groups.name')" width="150" />
        <el-table-column prop="description" :label="t('groups.description')" min-width="200" />
        <el-table-column prop="creatorName" :label="t('groups.creator')" width="100" />
        <el-table-column :label="t('groups.memberCount')" prop="memberCount" width="80" />
        <el-table-column prop="createTime" :label="t('groups.createTime')" width="170">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('groups.actions')" width="100" align="center" fixed="right">
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
          <el-empty :description="t('groups.empty')" />
        </template>
      </el-table>
    </component>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('groups.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('groups.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('groups.description')" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" :placeholder="t('groups.descriptionPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import { getUserGroups, createUserGroup, updateUserGroup, deleteUserGroup } from '@/api/userGroup'
import type { UserGroupVO, UserGroupCreateDTO, UserGroupUpdateDTO } from '@/api/userGroup'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

defineProps<{
  embedded?: boolean
}>()

const { t } = useI18n()

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

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('groups.nameRequired'), trigger: 'blur' }]
}))

const dialogTitle = computed(() => isEdit.value ? t('groups.editTitle') : t('groups.addGroup'))

const formatDate = formatDateTime

const loadGroups = async () => {
  loading.value = true
  try {
    groups.value = await getUserGroups()
  } catch {
    ElMessage.error(t('groups.loadError'))
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
      ElMessage.success(t('groups.updateSuccess'))
    } else {
      const data: UserGroupCreateDTO = {
        name: form.name,
        description: form.description || undefined
      }
      await createUserGroup(data)
      ElMessage.success(t('groups.createSuccess'))
    }

    dialogVisible.value = false
    loadGroups()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? t('groups.updateError') : t('groups.createError'))
    }
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (row: UserGroupVO) => {
  try {
    await ElMessageBox.confirm(t('groups.deleteConfirm', { name: row.name }), t('groups.deleteTitle'), {
      type: 'warning'
    })
    await deleteUserGroup(row.id)
    ElMessage.success(t('groups.deleteSuccess'))
    loadGroups()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t('groups.deleteError'))
    }
  }
}

onMounted(() => {
  loadGroups()
})
</script>

<style lang="scss" scoped>
// 满高布局：根拉满，面板占满（独立模式为 el-card，嵌入模式为 div），
// 表格弹性填充剩余空间（内部滚动）。
// 嵌入模式下高度链由父容器（UsersView 的 tab 链）提供。
.groups {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.groups-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
}

.card-header {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;

  &--embedded {
    margin-bottom: 16px;
  }
}

.list-table {
  flex: 1;
  min-height: 0;
}
</style>
