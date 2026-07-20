<template>
  <div class="repositories">
    <el-card>
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="类型">
            <el-select v-model="filters.type" placeholder="请选择类型" clearable style="width: 150px">
              <el-option label="依赖仓库" :value="0" />
              <el-option label="插件仓库" :value="1" />
            </el-select>
          </el-form-item>
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
            新增仓库
          </el-button>
        </div>
      </div>

      <el-table :data="repositories" v-loading="loading" stripe>
        <el-table-column prop="type" label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.type === 0 ? 'success' : 'warning'" size="small">
              {{ row.type === 0 ? '依赖仓库' : '插件仓库' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="repoId" label="仓库ID" width="150" />
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="url" label="URL" min-width="300" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="releasesEnabled" label="Releases" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.releasesEnabled ? 'success' : 'info'" size="small">
              {{ row.releasesEnabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="snapshotsEnabled" label="Snapshots" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.snapshotsEnabled ? 'success' : 'info'" size="small">
              {{ row.snapshotsEnabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <span class="action-btn" @click="handleEdit(row)">编辑</span>
            <span class="action-btn action-btn--danger" @click="handleDelete(row)">删除</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑仓库弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择类型" style="width: 100%">
            <el-option label="依赖仓库" :value="0" />
            <el-option label="插件仓库" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库ID" prop="repoId">
          <el-input v-model="form.repoId" placeholder="唯一标识仓库的ID" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="仓库显示名称" />
        </el-form-item>
        <el-form-item label="URL" prop="url">
          <el-input v-model="form.url" placeholder="仓库地址" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" placeholder="请选择状态" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>

        <!-- Releases 配置 -->
        <el-divider content-position="left">Releases 配置</el-divider>
        <el-form-item label="启用">
          <el-switch v-model="form.releasesEnabled" />
        </el-form-item>

        <!-- Snapshots 配置 -->
        <el-divider content-position="left">Snapshots 配置</el-divider>
        <el-form-item label="启用">
          <el-switch v-model="form.snapshotsEnabled" />
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
  getMavenRepositories,
  getMavenRepositoryById,
  createMavenRepository,
  updateMavenRepository,
  deleteMavenRepository
} from '@/api/repository'
import type { MavenRepositoryVO, MavenRepositoryDTO } from '@/api/repository'
import { formatDateTime } from '@/utils/date'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

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
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  repoId: [{ required: true, message: '请输入仓库ID', trigger: 'blur' }],
  name: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  url: [
    { required: true, message: '请输入仓库URL', trigger: 'blur' },
    { type: 'url', message: '请输入有效的URL地址', trigger: 'blur' }
  ]
}

const dialogTitle = computed(() => isEdit.value ? '编辑仓库' : '新增仓库')

const formatDate = formatDateTime

const loadData = async () => {
  loading.value = true
  try {
    repositories.value = await getMavenRepositories(filters.type ?? undefined, filters.status ?? undefined)
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
    ElMessage.error('加载仓库信息失败')
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
      ElMessage.success('更新成功')
    } else {
      await createMavenRepository(submitData)
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

const handleDelete = async (row: MavenRepositoryVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该仓库吗？', '提示', {
      type: 'warning'
    })
    await deleteMavenRepository(row.id)
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
