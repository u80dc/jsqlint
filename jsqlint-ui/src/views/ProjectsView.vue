<template>
  <div class="projects">
    <div class="projects-container">
      <!-- 标签栏 -->
      <div class="tab-bar">
        <div class="workspace-tabs">
          <div
            class="workspace-tab"
            :class="{ active: selectedWorkspace === 'all' }"
            @click="handleTabClick('all')"
          >
            <span class="tab-label">所有</span>
          </div>
          <div
            v-for="ws in workspaces"
            :key="ws.id"
            class="workspace-tab"
            :class="{ active: selectedWorkspace === String(ws.id) }"
            @click="handleTabClick(String(ws.id))"
          >
            <span class="tab-label" :title="ws.name">{{ ws.name }}</span>
            <span class="tab-close" @click.stop="handleDeleteWorkspace(ws.id)" v-if="ws.projectCount > 0">
              <el-icon><Close /></el-icon>
            </span>
          </div>
          <div class="workspace-tab workspace-tab--add" @click="openCreateDialog">
            <el-icon><Plus /></el-icon>
          </div>
        </div>
        <el-button type="primary" @click="handleAdd" class="add-btn">
          <el-icon><Plus /></el-icon>
          新增项目
        </el-button>
      </div>

      <!-- 表格区域 -->
      <div class="table-container" v-loading="loading" element-loading-text="加载中...">
        <!-- 空状态 -->
        <div v-if="showEmptyState" class="empty-state">
          <div class="empty-icon">
            <svg viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="10" y="20" width="60" height="50" rx="4" stroke="#cbd5e1" stroke-width="2" fill="none"/>
              <path d="M10 35h60" stroke="#cbd5e1" stroke-width="2"/>
              <rect x="20" y="45" width="20" height="4" rx="2" fill="#e2e8f0"/>
              <rect x="20" y="55" width="40" height="4" rx="2" fill="#e2e8f0"/>
              <circle cx="55" cy="50" r="12" stroke="#e2e8f0" stroke-width="2" fill="none"/>
              <path d="M52 47l6 6M52 53l6-6" stroke="#e2e8f0" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="empty-text">该工作空间暂无项目</div>
          <span class="action-btn action-btn--danger" @click="() => handleDeleteWorkspace()">
            删除空间
          </span>
        </div>

        <!-- 表格 -->
        <el-table v-else-if="!loading" :data="projects" stripe>
          <el-table-column width="70" align="center" label="S">
            <template #header>
              <el-tooltip content="上次审核状态" placement="top" :show-after="300">
                <span class="header-letter">S</span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <span :class="getStatusClass(row.lastReviewStatus)" class="status-icon">
                <svg v-if="!row.lastReviewStatus" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                </svg>
                <svg v-else-if="row.lastReviewStatus === 1" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
                </svg>
                <svg v-else-if="row.lastReviewStatus === 2" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/>
                </svg>
                <svg v-else-if="row.lastReviewStatus === 3" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                </svg>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="70" align="center" label="W">
            <template #header>
              <el-tooltip content="审核晴雨表" placement="top" :show-after="300">
                <span class="header-letter">W</span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <span :class="getWeatherClass(row.reviewWeather)" class="weather-icon">
                <svg v-if="!row.reviewWeather" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="icon-svg">
                  <circle cx="12" cy="12" r="4"/>
                  <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
                </svg>
                <svg v-else-if="row.reviewWeather === 1" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <circle cx="9" cy="9" r="4"/>
                  <path d="M2 17a9 9 0 0 1 14.5-7.5M5 12a6 6 0 0 1 10.5-5"/>
                </svg>
                <svg v-else-if="row.reviewWeather === 2" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <circle cx="10" cy="8" r="3"/>
                  <path d="M2 17a8 8 0 0 1 13-6M5 12a5 5 0 0 1 8-4M22 17a9 9 0 0 0-17-7"/>
                </svg>
                <svg v-else-if="row.reviewWeather === 3" viewBox="0 0 24 24" fill="currentColor" class="icon-svg">
                  <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                </svg>
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="项目名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
          <el-table-column width="180" align="center">
            <template #header>
              <span>上次扫描</span>
            </template>
            <template #default="{ row }">
              <span class="scan-count">
                <span class="scan-item" title="数据查询(DQL)"><span class="scan-label">Q</span><span class="scan-num">{{ row.lastScan?.dql || 0 }}</span></span>
                <span class="scan-item" title="数据操作(DML)"><span class="scan-label">M</span><span class="scan-num">{{ row.lastScan?.dml || 0 }}</span></span>
                <span class="scan-item" title="数据定义(DDL)"><span class="scan-label">L</span><span class="scan-num">{{ row.lastScan?.ddl || 0 }}</span></span>
                <span class="scan-item" title="数据控制(DCL)"><span class="scan-label">C</span><span class="scan-num">{{ row.lastScan?.dcl || 0 }}</span></span>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="160" align="center">
            <template #header>
              <span>上次扫描时间</span>
            </template>
            <template #default="{ row }">
              <span class="time-cell">{{ row.lastScan?.scanTime || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column width="140" align="center">
            <template #header>
              <span>上次审核</span>
            </template>
            <template #default="{ row }">
              <span class="review-count">
                <span class="count-passed" title="通过">{{ row.lastReview?.passed || 0 }}</span>
                <span class="count-warning" title="警告">{{ row.lastReview?.warning || 0 }}</span>
                <span class="count-critical" title="致命">{{ row.lastReview?.critical || 0 }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="160" align="center">
            <template #header>
              <span>上次审核时间</span>
            </template>
            <template #default="{ row }">
              <span class="time-cell">{{ row.lastReview?.reviewTime || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light" round size="small">
                {{ row.status === 1 ? '已就绪' : '未就绪' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="selectedWorkspace === 'all'" label="空间" width="140" align="center">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(cmd: number | null) => handleMoveToWorkspace(row, cmd)">
                <span class="workspace-dropdown">
                  <span v-if="row.workspaceName" class="workspace-tag">{{ row.workspaceName }}</span>
                  <span v-else class="no-workspace">未分配</span>
                  <el-icon class="dropdown-arrow"><ArrowDown /></el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :command="null" :class="{ 'is-active': !row.workspaceId }">移除</el-dropdown-item>
                    <el-dropdown-item v-for="ws in workspaces" :key="ws.id" :command="ws.id" :class="{ 'is-active': row.workspaceId === ws.id }">
                      {{ ws.name }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="center">
            <template #default="{ row }">
              <span class="action-btn" @click="handleUpdate(row)">更新</span>
              <span class="action-btn action-btn--success" @click="handleScan(row)">扫描</span>
              <span class="action-btn action-btn--warning" @click="handleAudit(row)">审核</span>
              <span class="action-btn action-btn--info" @click="handleView(row)">查看</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div v-if="!loading && projects.length > 0" class="pagination">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="total, sizes, prev, pager, next"
            background
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </div>
    </div>

    <!-- 创建工作空间弹窗 -->
    <el-dialog v-model="createDialogVisible" title="创建工作空间" width="420px" align-center>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="createForm.name" placeholder="请输入工作空间名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreateSubmit" :loading="createLoading">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增项目弹窗 -->
    <el-dialog v-model="projectDialogVisible" title="新增项目" width="520px" align-center @close="handleProjectDialogClose">
      <el-form ref="projectFormRef" :model="projectForm" :rules="projectRules" label-width="100px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="projectForm.name" placeholder="请输入项目名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="projectForm.description"
            type="textarea"
            :rows="3"
            maxlength="128"
            show-word-limit
            placeholder="请输入项目描述（选填）"
          />
        </el-form-item>
        <el-form-item label="地址" prop="gitUrl">
          <el-input v-model="projectForm.gitUrl" placeholder="请输入Git仓库地址" />
        </el-form-item>
        <el-form-item label="分支或Tag" prop="ref">
          <el-input v-model="projectForm.ref" placeholder="请输入分支或Tag" />
        </el-form-item>
        <el-form-item label="凭证" prop="credentialId">
          <el-select v-model="projectForm.credentialId" placeholder="请选择凭证" style="width: 100%">
            <el-option v-for="cred in credentials" :key="cred.id" :label="cred.name" :value="cred.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作空间" prop="workspaceId">
          <el-select v-model="projectForm.workspaceId" placeholder="请选择工作空间" style="width: 100%">
            <el-option label="无" :value="0" />
            <el-option v-for="ws in workspaces" :key="ws.id" :label="ws.name" :value="ws.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleProjectSubmit" :loading="projectLoading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Close, ArrowDown } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import {
  getWorkspaces,
  createWorkspace,
  deleteWorkspace,
  getProjects,
  getProjectCredentials,
  createProject,
  addProjectToWorkspace,
  removeProjectFromWorkspace
} from '@/api/workspace'
import type { WorkspaceVO, WorkspaceCreateDTO, ProjectVO, CredentialVO, ProjectCreateDTO } from '@/api/workspace'
import { isDialogCanceled, shouldShowActionError } from '@/utils/error'

const router = useRouter()

const loading = ref(false)
const createLoading = ref(false)
const projectLoading = ref(false)
const workspaces = ref<WorkspaceVO[]>([])
const credentials = ref<CredentialVO[]>([])
const projects = ref<ProjectVO[]>([])
const selectedWorkspace = ref<string>('all')

// 分页
const currentPage = ref(1)
const pageSize = ref(Number(localStorage.getItem('jsqlint_page_size')) || 10)
const total = ref(0)

const showEmptyState = computed(() => {
  return selectedWorkspace.value !== 'all' && projects.value.length === 0 && !loading.value
})

const createDialogVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  name: ''
})
const createRules: FormRules = {
  name: [{ required: true, message: '请输入工作空间名称', trigger: 'blur' }]
}

const projectDialogVisible = ref(false)
const projectFormRef = ref<FormInstance>()
const projectForm = reactive({
  name: '',
  description: '',
  gitUrl: '',
  ref: 'main',
  credentialId: null as number | null,
  workspaceId: 0
})
const projectRules: FormRules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  gitUrl: [{ required: true, message: '请输入Git仓库地址', trigger: 'blur' }],
  ref: [{ required: true, message: '请输入分支或Tag', trigger: 'blur' }],
  credentialId: [{ required: true, message: '请选择凭证', trigger: 'change' }],
  workspaceId: [{ required: true, message: '请选择工作空间', trigger: 'change' }]
}

const loadWorkspaces = async () => {
  try {
    workspaces.value = await getWorkspaces()
    // 检查保存的标签是否还存在，如果不存在则默认为"所有"
    const savedId = localStorage.getItem('projects_selected_workspace')
    if (savedId && savedId !== 'all') {
      const exists = workspaces.value.some(ws => String(ws.id) === savedId)
      if (!exists) {
        selectedWorkspace.value = 'all'
        localStorage.setItem('projects_selected_workspace', 'all')
      }
    }
  } catch {
    ElMessage.error('加载工作空间列表失败')
  }
}

const loadCredentials = async () => {
  try {
    credentials.value = await getProjectCredentials()
  } catch {
    ElMessage.error('加载凭证列表失败')
  }
}

const loadProjects = async () => {
  loading.value = true
  try {
    const workspaceId = selectedWorkspace.value === 'all' ? undefined : Number(selectedWorkspace.value)
    const data = await getProjects(workspaceId)
    projects.value = data
    total.value = data.length
    currentPage.value = 1
  } catch {
    ElMessage.error('加载项目列表失败')
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (val: number) => {
  pageSize.value = val
  loadProjects()
}

const handleCurrentChange = (val: number) => {
  currentPage.value = val
  loadProjects()
}

watch(selectedWorkspace, () => {
  loadProjects()
})

// 状态相关方法
const getStatusClass = (status?: number) => {
  if (status === undefined || status === null) return 'status-waiting'
  switch (status) {
    case 1: return 'status-passed'
    case 2: return 'status-warning'
    case 3: return 'status-critical'
    default: return 'status-waiting'
  }
}

// 晴雨表相关方法
const getWeatherClass = (weather?: number) => {
  if (weather === undefined || weather === null) return 'weather-sunny'
  switch (weather) {
    case 0: return 'weather-sunny'
    case 1: return 'weather-cloudy'
    case 2: return 'weather-overcast'
    case 3: return 'weather-rainy'
    default: return 'weather-sunny'
  }
}

const openCreateDialog = () => {
  createDialogVisible.value = true
}

const handleTabClick = (workspaceId: string) => {
  selectedWorkspace.value = workspaceId
  localStorage.setItem('projects_selected_workspace', workspaceId)
  loadProjects()
}

const handleAdd = () => {
  projectDialogVisible.value = true
}

const handleScan = (row: ProjectVO) => {
  ElMessage.info(`开始扫描项目: ${row.name}`)
}

const handleUpdate = (row: ProjectVO) => {
  ElMessage.info(`更新项目: ${row.name}`)
}

const handleAudit = (row: ProjectVO) => {
  ElMessage.info(`审核项目: ${row.name}`)
}

const handleView = (row: ProjectVO) => {
  router.push(`/workspace/projects/${row.id}`)
}

const handleMoveToWorkspace = async (row: ProjectVO, workspaceId: number | null) => {
  const targetWorkspace = workspaceId ? workspaces.value.find(ws => ws.id === workspaceId) : null
  const targetName = targetWorkspace?.name || null
  const oldWorkspaceId = row.workspaceId

  try {
    // 如果项目之前有空间，先移除
    if (oldWorkspaceId) {
      await removeProjectFromWorkspace(oldWorkspaceId, row.id)
    }
    // 如果选择了新空间，添加到新空间
    if (workspaceId) {
      await addProjectToWorkspace(workspaceId, row.id)
    }
    // 更新本地数据
    row.workspaceId = workspaceId || undefined
    row.workspaceName = targetName || undefined
    ElMessage.success(`已将「${row.name}」移动到${targetName ? '「' + targetName + '」' : '无空间'}`)
  } catch {
    ElMessage.error('移动项目失败')
  }
}

const handleDeleteWorkspace = async (wsId?: number) => {
  const idToDelete = wsId || Number(selectedWorkspace.value)
  if (idToDelete === 0 || selectedWorkspace.value === 'all') return

  try {
    await ElMessageBox.confirm('确定要删除该工作空间吗？', '确认删除', {
      type: 'warning'
    })
    await deleteWorkspace(idToDelete)
    ElMessage.success('删除成功')
    selectedWorkspace.value = 'all'
    loadWorkspaces()
    loadProjects()
  } catch (error: unknown) {
    if (!isDialogCanceled(error)) {
      ElMessage.error('删除失败')
    }
  }
}

const handleCreateSubmit = async () => {
  try {
    await createFormRef.value?.validate()
    createLoading.value = true

    const data: WorkspaceCreateDTO = { name: createForm.name }
    const result = await createWorkspace(data)
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    createForm.name = ''
    loadWorkspaces()
    selectedWorkspace.value = String(result.id)
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error('创建失败')
    }
  } finally {
    createLoading.value = false
  }
}

const handleProjectDialogClose = () => {
  projectFormRef.value?.clearValidate()
  projectForm.name = ''
  projectForm.description = ''
  projectForm.gitUrl = ''
  projectForm.ref = 'main'
  projectForm.credentialId = null
  projectForm.workspaceId = 0
}

const handleProjectSubmit = async () => {
  try {
    await projectFormRef.value?.validate()
    projectLoading.value = true

    const data: ProjectCreateDTO = {
      name: projectForm.name,
      description: projectForm.description || undefined,
      gitUrl: projectForm.gitUrl,
      ref: projectForm.ref,
      credentialId: projectForm.credentialId === 0 || projectForm.credentialId === null ? undefined : projectForm.credentialId,
      workspaceId: projectForm.workspaceId === 0 ? undefined : projectForm.workspaceId
    }
    await createProject(data)
    ElMessage.success('创建成功')
    projectDialogVisible.value = false
    loadProjects()
  } catch (error: unknown) {
    if (shouldShowActionError(error)) {
      ElMessage.error('创建失败')
    }
  } finally {
    projectLoading.value = false
  }
}

onMounted(() => {
  // 恢复上次选中的标签
  const savedWorkspace = localStorage.getItem('projects_selected_workspace')
  if (savedWorkspace) {
    selectedWorkspace.value = savedWorkspace
  }
  loadWorkspaces()
  loadCredentials()
  loadProjects()
})
</script>

<style lang="scss" scoped>
.projects {
  height: 100%;
}

.projects-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--jsp-bg-card);
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid $border-light;
}

.workspace-tabs {
  display: flex;
  align-items: center;
  gap: 0;
  flex-wrap: wrap;
  background: $border-light;
  border-radius: 22px;
  padding: 4px;
}

.workspace-tab {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  font-size: 13px;
  color: var(--jsp-text-secondary);
  background: transparent;
  border-radius: 18px;

  .tab-label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &:hover {
    color: $primary-color;
  }

  &.active {
    color: $text-inverse;
    background: $primary-color;
    font-weight: 500;
    box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
  }

  &--add {
    padding: 6px 12px;
    color: $text-placeholder;
    background: transparent;

    &:hover {
      color: $primary-color;
    }
  }

  .tab-close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    margin-left: 2px;
    transition: all 0.15s ease;

    &:hover {
      background: rgba(0, 0, 0, 0.08);
    }

    .el-icon {
      font-size: 12px;
    }
  }

  &.active .tab-close {
    &:hover {
      background: rgba(255, 255, 255, 0.2);
    }
  }
}

.table-container {
  flex: 1;
  padding: 16px 24px;
  overflow: auto;
  display: flex;
  flex-direction: column;

  :deep(.el-table) {
    --el-table-header-bg-color: $bg-page-light;
    --el-table-header-text-color: $text-secondary-dark;
    --el-table-header-font-weight: 600;
    --el-table-border-color: $border-light;
    flex: 1;

    th.el-table__cell {
      font-size: 13px;
      font-weight: 600;
      padding: 14px 16px;
    }

    td.el-table__cell {
      font-size: 14px;
      padding: 14px 16px;
    }

    tr:hover > td.el-table__cell {
      background: var(--jsp-bg-card);
    }
  }
}

.pagination {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0 0;
  border-top: 1px solid $border-light;
  margin-top: auto;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 150px;

  .empty-icon {
    width: 80px;
    height: 80px;
    opacity: 0.6;

    svg {
      width: 100%;
      height: 100%;
    }
  }

  .empty-text {
    font-size: 14px;
    color: $text-placeholder;
  }
}

// 状态样式
// 注意用块级 flex 而非 inline-flex：.cell 的 text-overflow:ellipsis 会作用于
// inline-flex（原子内联盒），在图标右侧渲染出多余省略号
.status-icon {
  display: flex;
  align-items: center;
  justify-content: center;

  .icon-svg {
    width: 18px;
    height: 18px;
  }
}

.status-passed {
  color: $success-color;
}

.status-warning {
  color: $warning-color;
}

.status-waiting {
  color: $primary-color;
}

.status-none, .weather-none {
  color: $text-placeholder;
}

// 晴雨表样式（同上：块级 flex 避免 ellipsis）
.weather-icon {
  display: flex;
  align-items: center;
  justify-content: center;

  .icon-svg {
    width: 18px;
    height: 18px;
  }
}

.weather-sunny {
  color: $warning-color;
}

.weather-cloudy {
  color: $text-placeholder;
}

.weather-overcast {
  color: var(--jsp-text-secondary);
}

.weather-rainy {
  color: $primary-color;
}

// 扫描数量样式
// S/W 单字母表头：不受 .cell 的 ellipsis 截断影响
.header-letter {
  display: inline-block;
  white-space: nowrap;
}

.scan-count {
  display: flex;
  gap: 8px;
  justify-content: center;

  .scan-item {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    padding: 2px 6px;
    border-radius: 4px;
    background: $border-light;

    .scan-label {
      color: $text-placeholder;
      font-size: 12px;
      font-weight: 500;
    }

    .scan-num {
      color: $text-secondary-dark;
      font-size: 12px;
      font-weight: 600;
      font-family: 'Monaco', 'Menlo', monospace;
    }
  }
}

// 审核数量样式
.review-count {
  display: flex;
  gap: 6px;
  justify-content: center;

  .count-passed {
    color: $success-color;
    font-weight: 500;
  }

  .count-warning {
    color: $warning-color;
    font-weight: 500;
  }

  .count-critical {
    color: $danger-color;
    font-weight: 600;
  }
}

// 时间单元格样式
.time-cell {
  font-size: 12px;
  color: var(--jsp-text-secondary);
}

// 空间标签样式
.workspace-tag {
  display: inline-block;
  padding: 2px 8px;
  background: $info-light;
  color: $primary-hover;
  border-radius: 4px;
  font-size: 12px;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.no-workspace {
  color: $text-placeholder;
  background: $border-light;
}

.workspace-dropdown {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;

  .workspace-tag {
    max-width: 80px;
    background: $info-light;
    color: $primary-hover;
  }

  .no-workspace {
    max-width: 80px;
    background: $border-light;
    color: $text-placeholder;
  }

  .dropdown-arrow {
    font-size: 12px;
    color: $text-placeholder;
    transition: transform 0.2s;
  }

  &:hover {
    background: $border-light;

    .workspace-tag, .no-workspace {
      background: $info-light;
    }

    .dropdown-arrow {
      color: var(--jsp-text-secondary);
    }
  }
}

.workspace-tag.readonly {
  display: inline-block;
  padding: 2px 8px;
  background: $info-light;
  color: $primary-hover;
  border-radius: 4px;
  font-size: 12px;
}

// 无数据样式
.no-data {
  color: $border-darker;
}
</style>
