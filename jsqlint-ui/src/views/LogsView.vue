<template>
  <div class="logs-page">
    <el-card class="list-card">
      <div class="filter-toolbar">
        <el-form :inline="true" class="filter-form">
          <el-form-item :label="t('logs.operationType')">
            <el-select v-model="filters.operationType" :placeholder="t('logs.allTypes')" clearable style="width: 180px">
              <el-option
                v-for="option in operationTypeOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('logs.operationUser')">
            <el-select
              v-model="filters.operationUserId"
              :placeholder="t('logs.allUsers')"
              clearable
              filterable
              style="width: 180px"
            >
              <el-option
                v-for="user in users"
                :key="user.id"
                :label="user.realName || user.username"
                :value="user.id"
              >
                <span>{{ user.realName || user.username }}</span>
                <span class="user-option-name">@{{ user.username }}</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item :label="t('logs.time')">
            <el-date-picker
              v-model="filters.operationDate"
              type="date"
              :placeholder="t('logs.selectDate')"
              value-format="YYYY-MM-DD"
              clearable
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="logs" v-loading="loading" stripe class="list-table" height="100%">
        <el-table-column prop="operationTime" :label="t('logs.operationTime')" width="180">
          <template #default="{ row }">
            {{ formatDate(row.operationTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="operationType" :label="t('logs.operationType')" width="150" />
        <el-table-column :label="t('logs.operationUser')" width="120">
          <template #default="{ row }">
            {{ row.operationUserName || row.operationUser || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" :label="t('logs.ipAddress')" width="140" />
        <el-table-column :label="t('logs.details')" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.operationMessage || row.details || '-' }}
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { getSystemLogs, type SystemLog } from '@/api/systemLog'
import { getUsers, type UserVO } from '@/api/admin'
import { ElMessage } from 'element-plus'

const { t } = useI18n()

const loading = ref(false)
const logs = ref<SystemLog[]>([])
const users = ref<UserVO[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

const filters = reactive({
  operationType: '',
  operationUserId: undefined as number | undefined,
  operationDate: ''
})

const operationTypeOptions = [
  { value: 'USER_CREATE', label: '创建用户' },
  { value: 'USER_UPDATE', label: '更新用户' },
  { value: 'USER_DELETE', label: '删除用户' },
  { value: 'USER_ENABLE', label: '启用用户' },
  { value: 'USER_DISABLE', label: '禁用用户' },
  { value: 'USER_RESET_PASSWORD', label: '重置密码' },
  { value: 'CREDENTIAL_CREATE', label: '创建凭证' },
  { value: 'CREDENTIAL_UPDATE', label: '更新凭证' },
  { value: 'CREDENTIAL_DELETE', label: '删除凭证' },
  { value: 'CREDENTIAL_ENABLE', label: '启用凭证' },
  { value: 'CREDENTIAL_DISABLE', label: '禁用凭证' },
  { value: 'RULE_CREATE', label: '创建审核规则' },
  { value: 'RULE_UPDATE', label: '更新审核规则' },
  { value: 'RULE_DELETE', label: '删除审核规则' },
  { value: 'RULE_ENABLE', label: '启用审核规则' },
  { value: 'RULE_DISABLE', label: '禁用审核规则' },
  { value: 'RULE_VERSION_CREATE', label: '创建规则版本' },
  { value: 'NOTIFICATION_CREATE', label: '创建通知设置' },
  { value: 'NOTIFICATION_UPDATE', label: '更新通知设置' },
  { value: 'NOTIFICATION_DELETE', label: '删除通知设置' },
  { value: 'USER_GROUP_CREATE', label: '创建用户组' },
  { value: 'USER_GROUP_UPDATE', label: '更新用户组' },
  { value: 'USER_GROUP_DELETE', label: '删除用户组' },
  { value: 'USER_GROUP_MEMBER_ADD', label: '添加用户组成员' },
  { value: 'USER_GROUP_MEMBER_REMOVE', label: '移除用户组成员' }
]

const formatDate = (date?: string) => {
  if (!date) return '-'
  return new Date(date).toLocaleString('zh-CN')
}

const loadUsers = async () => {
  try {
    // 筛选下拉需要全量用户，取足够大的单页
    const result = await getUsers({ page: 1, pageSize: 200 })
    users.value = result.list
  } catch {
    users.value = []
  }
}

const loadLogs = async () => {
  loading.value = true
  try {
    const startTime = filters.operationDate ? `${filters.operationDate} 00:00:00` : undefined
    const endTime = filters.operationDate ? `${filters.operationDate} 23:59:59` : undefined
    const result = await getSystemLogs({
      page: currentPage.value,
      size: pageSize.value,
      operationType: filters.operationType || undefined,
      operationUserId: filters.operationUserId,
      startTime,
      endTime
    })
    logs.value = result.records
    total.value = result.total
  } catch (error) {
    console.error('加载日志失败', error)
    ElMessage.error(t('logs.loadError'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadLogs()
}

const handleReset = () => {
  filters.operationType = ''
  filters.operationUserId = undefined
  filters.operationDate = ''
  handleSearch()
}

const handleSizeChange = (size: number) => {
  pageSize.value = size
  currentPage.value = 1
  loadLogs()
}

const handleCurrentChange = (page: number) => {
  currentPage.value = page
  loadLogs()
}

onMounted(() => {
  loadUsers()
  loadLogs()
})
</script>

<style lang="scss" scoped>
// 满高布局：根拉满内容区高度，卡片占满，表格弹性填充剩余空间（内部滚动）
.logs-page {
  height: 100%;
  display: flex;
  flex-direction: column;

  .list-card {
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

    .filter-toolbar {
      flex-shrink: 0;
    }
  }

  .list-table {
    flex: 1;
    min-height: 0;
  }

  .user-option-name {
    color: $text-placeholder;
    font-size: 12px;
    margin-left: 8px;
  }

  .pagination {
    flex-shrink: 0;
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}
</style>
