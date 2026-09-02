<template>
  <div class="repositories">
    <el-card>
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item :label="t('repositories.type')">
            <el-select v-model="filters.type" :placeholder="t('repositories.typePlaceholder')" clearable style="width: 150px">
              <el-option :label="t('repositories.dependencyRepo')" :value="0" />
              <el-option :label="t('repositories.pluginRepo')" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('repositories.status')">
            <el-select v-model="filters.status" :placeholder="t('repositories.statusPlaceholder')" clearable style="width: 140px">
              <el-option :label="t('common.enable')" :value="1" />
              <el-option :label="t('common.disable')" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">{{ t('repositories.search') }}</el-button>
            <el-button @click="handleReset">{{ t('common.reset') }}</el-button>
          </el-form-item>
        </el-form>
        <div class="filter-toolbar__actions">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            {{ t('repositories.addRepo') }}
          </el-button>
        </div>
      </div>

      <el-table :data="repositories" v-loading="loading" stripe>
        <el-table-column prop="type" :label="t('repositories.type')" width="120">
          <template #default="{ row }">
            <el-tag :type="row.type === 0 ? 'success' : 'warning'" size="small">
              {{ row.type === 0 ? t('repositories.dependencyRepo') : t('repositories.pluginRepo') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="repoId" :label="t('repositories.repoId')" width="150" />
        <el-table-column prop="name" :label="t('repositories.name')" width="150" />
        <el-table-column prop="url" label="URL" min-width="300" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('repositories.status')" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? t('common.enable') : t('common.disable') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="releasesEnabled" label="Releases" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.releasesEnabled ? 'success' : 'info'" size="small">
              {{ row.releasesEnabled ? t('common.enable') : t('common.disable') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="snapshotsEnabled" label="Snapshots" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.snapshotsEnabled ? 'success' : 'info'" size="small">
              {{ row.snapshotsEnabled ? t('common.enable') : t('common.disable') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" :label="t('repositories.createTime')" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('repositories.actions')" width="100" align="center">
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
          <el-empty :description="t('repositories.empty')" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增/编辑仓库弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('repositories.type')" prop="type">
          <el-select v-model="form.type" :placeholder="t('repositories.typePlaceholder')" style="width: 100%">
            <el-option :label="t('repositories.dependencyRepo')" :value="0" />
            <el-option :label="t('repositories.pluginRepo')" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('repositories.repoId')" prop="repoId">
          <el-input v-model="form.repoId" :placeholder="t('repositories.repoIdPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('repositories.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('repositories.namePlaceholder')" />
        </el-form-item>
        <el-form-item label="URL" prop="url">
          <el-input v-model="form.url" :placeholder="t('repositories.urlPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('repositories.status')" prop="status">
          <el-select v-model="form.status" :placeholder="t('repositories.statusPlaceholder')" style="width: 100%">
            <el-option :label="t('common.enable')" :value="1" />
            <el-option :label="t('common.disable')" :value="0" />
          </el-select>
        </el-form-item>

        <!-- Releases 配置 -->
        <el-divider content-position="left">{{ t('repositories.releasesConfig') }}</el-divider>
        <el-form-item :label="t('repositories.enableLabel')">
          <el-switch v-model="form.releasesEnabled" />
        </el-form-item>

        <!-- Snapshots 配置 -->
        <el-divider content-position="left">{{ t('repositories.snapshotsConfig') }}</el-divider>
        <el-form-item :label="t('repositories.enableLabel')">
          <el-switch v-model="form.snapshotsEnabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">{{ t('repositories.ok') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import {
  getMavenRepositories,
  getMavenRepositoryById,
  createMavenRepository,
  updateMavenRepository,
  deleteMavenRepository
} from '@/api/repository'
import type { MavenRepositoryVO, MavenRepositoryDTO } from '@/api/repository'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const { t } = useI18n()

const loading = ref(false)
const submitLoading = ref(false)
const repositories = ref<MavenRepositoryVO[]>([])

const filters = reactive({
  type: null as number | null,
  status: null as number | null
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<MavenRepositoryDTO>({
  type: 0,
  repoId: '',
  name: '',
  url: '',
  layout: 'default',
  releasesEnabled: true,
  releasesUpdatePolicy: 'daily',
  releasesChecksumPolicy: 'fail',
  snapshotsEnabled: false,
  snapshotsUpdatePolicy: 'daily',
  snapshotsChecksumPolicy: 'fail',
  status: 1
})

const rules: FormRules = {
  type: [{ required: true, message: t('repositories.typeRequired'), trigger: 'change' }],
  repoId: [{ required: true, message: t('repositories.repoIdRequired'), trigger: 'blur' }],
  name: [{ required: true, message: t('repositories.nameRequired'), trigger: 'blur' }],
  status: [{ required: true, message: t('repositories.statusRequired'), trigger: 'change' }],
  url: [
    { required: true, message: t('repositories.urlRequired'), trigger: 'blur' },
    { type: 'url', message: t('repositories.urlInvalid'), trigger: 'blur' }
  ]
}

const dialogTitle = computed(() => isEdit.value ? t('repositories.editTitle') : t('repositories.addTitle'))

const formatDate = formatDateTime

const loadData = async () => {
  loading.value = true
  try {
    repositories.value = await getMavenRepositories(filters.type ?? undefined, filters.status ?? undefined)
  } catch {
    ElMessage.error(t('repositories.loadError'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  loadData()
}

const handleReset = () => {
  filters.type = null
  filters.status = null
  loadData()
}

const resetForm = () => {
  form.type = 0
  form.repoId = ''
  form.name = ''
  form.url = ''
  form.layout = 'default'
  form.releasesEnabled = true
  form.releasesUpdatePolicy = 'daily'
  form.releasesChecksumPolicy = 'fail'
  form.snapshotsEnabled = false
  form.snapshotsUpdatePolicy = 'daily'
  form.snapshotsChecksumPolicy = 'fail'
  form.status = 1
  formRef.value?.clearValidate()
}

const handleAdd = () => {
  isEdit.value = false
  currentId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: MavenRepositoryVO) => {
  isEdit.value = true
  currentId.value = row.id
  try {
    const data = await getMavenRepositoryById(row.id)
    Object.assign(form, {
      type: data.type,
      repoId: data.repoId,
      name: data.name,
      url: data.url,
      layout: data.layout || 'default',
      releasesEnabled: data.releasesEnabled,
      releasesUpdatePolicy: data.releasesUpdatePolicy || 'daily',
      releasesChecksumPolicy: data.releasesChecksumPolicy || 'fail',
      snapshotsEnabled: data.snapshotsEnabled,
      snapshotsUpdatePolicy: data.snapshotsUpdatePolicy || 'daily',
      snapshotsChecksumPolicy: data.snapshotsChecksumPolicy || 'fail',
      status: data.status ?? 1
    })
    dialogVisible.value = true
  } catch {
    ElMessage.error(t('repositories.loadDetailError'))
  }
}

const handleDialogClose = () => {
  resetForm()
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
    submitLoading.value = true

    const submitData: MavenRepositoryDTO = { ...form }

    if (isEdit.value && currentId.value) {
      await updateMavenRepository(currentId.value, submitData)
      ElMessage.success(t('repositories.updateSuccess'))
    } else {
      await createMavenRepository(submitData)
      ElMessage.success(t('repositories.createSuccess'))
    }
    dialogVisible.value = false
    loadData()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error(isEdit.value ? t('repositories.updateError') : t('repositories.createError'))
    }
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (row: MavenRepositoryVO) => {
  try {
    await ElMessageBox.confirm(t('repositories.deleteConfirm'), t('repositories.deleteTitle'), {
      type: 'warning'
    })
    await deleteMavenRepository(row.id)
    ElMessage.success(t('repositories.deleteSuccess'))
    loadData()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error(t('repositories.deleteError'))
    }
  }
}

onMounted(() => {
  loadData()
})
</script>
