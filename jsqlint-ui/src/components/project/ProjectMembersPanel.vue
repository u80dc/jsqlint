<template>
  <div class="members-page">
    <el-card v-if="isProjectAdmin" class="members-action-card">
      <template #header>
        <div class="card-header">
          <span class="section-title">{{ t('projectDetail.members.inviteTitle') }}</span>
        </div>
      </template>

      <div class="invite-form">
        <el-tabs v-model="inviteTab" class="invite-tabs">
          <el-tab-pane :label="t('projectDetail.members.user')" name="user">
            <div class="invite-content">
              <el-select
                v-model="selectedUsers"
                multiple
                filterable
                remote
                reserve-keyword
                :placeholder="t('projectDetail.members.searchUser')"
                :remote-method="handleSearchUsers"
                :loading="searching"
                class="user-select"
                style="width: 400px"
              >
                <el-option
                  v-for="user in searchResults"
                  :key="user.id"
                  :label="user.realName || user.username"
                  :value="user.id"
                >
                  <span>{{ user.realName || user.username }}</span>
                  <span class="user-option-name">@{{ user.username }}</span>
                </el-option>
              </el-select>

              <el-select v-model="memberRole" :placeholder="t('projectDetail.members.selectRole')" style="width: 140px; margin-left: 12px">
                <el-option :value="0" :label="t('projectDetail.members.normalUser')" />
                <el-option :value="1" :label="t('projectDetail.members.projectAdmin')" />
              </el-select>

              <el-button
                type="primary"
                :disabled="selectedUsers.length === 0"
                :loading="adding"
                style="margin-left: 12px"
                @click="handleAddMembers"
              >
                {{ t('projectDetail.members.addMember') }}
              </el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="t('projectDetail.members.group')" name="group">
            <div class="invite-content">
              <el-select
                v-model="selectedGroup"
                filterable
                remote
                reserve-keyword
                :placeholder="t('projectDetail.members.searchGroup')"
                :remote-method="searchGroups"
                :loading="groupSearching"
                style="width: 300px"
                @change="handleGroupChange"
              >
                <el-option
                  v-for="group in groupResults"
                  :key="group.id"
                  :label="group.name"
                  :value="group.id"
                >
                  <span>{{ group.name }}</span>
                  <span v-if="group.memberCount" class="group-option-count">
                    {{ t('projectDetail.members.peopleCount', { count: group.memberCount }) }}
                  </span>
                </el-option>
              </el-select>

              <el-select v-model="memberRole" :placeholder="t('projectDetail.members.selectRole')" style="width: 140px; margin-left: 12px">
                <el-option :value="0" :label="t('projectDetail.members.normalUser')" />
                <el-option :value="1" :label="t('projectDetail.members.projectAdmin')" />
              </el-select>

              <el-button
                type="primary"
                :disabled="!selectedGroup"
                :loading="addingGroup"
                style="margin-left: 12px"
                @click="handleAddGroupMembers"
              >
                {{ t('projectDetail.members.addGroupMembers') }}
              </el-button>
            </div>

            <div v-if="selectedGroup && groupMembers.length > 0" class="group-members-preview">
              <div class="preview-header">
                <span class="preview-title">
                  {{ t('projectDetail.members.groupMembers', { count: groupMembers.length }) }}
                </span>
              </div>
              <div class="preview-list">
                <el-tag
                  v-for="member in groupMembers"
                  :key="member.id"
                  size="small"
                  class="member-tag"
                >
                  {{ member.realName || member.username }}
                </el-tag>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-card>

    <el-card class="members-list-card">
      <template #header>
        <div class="card-header">
          <span class="section-title">{{ t('projectDetail.members.title') }}</span>
          <span class="member-count">{{ t('projectDetail.members.peopleCount', { count: members.length }) }}</span>
        </div>
      </template>

      <el-table :data="members" v-loading="membersLoading" stripe>
        <el-table-column prop="account" :label="t('projectDetail.members.account')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.account || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="username" :label="t('projectDetail.members.username')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.username || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="roleDescription" :label="t('projectDetail.members.role')" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 1 ? 'warning' : 'info'" size="small">
              {{ row.roleDescription || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="joinTime" :label="t('projectDetail.members.joinTime')" width="180" align="center">
          <template #default="{ row }">
            {{ formatDate(row.joinTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('projectDetail.members.actions')" width="100" align="center">
          <template #default="{ row }">
            <span
              v-if="isProjectAdmin"
              class="action-btn action-btn--danger"
              @click="handleRemoveMember(row)"
            >
              {{ t('projectDetail.members.remove') }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('projectDetail.members.empty')" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addProjectGroupMembers,
  addProjectMembers,
  getProjectMembers,
  getUserGroupMembers,
  removeProjectMember,
  searchUserGroups,
  searchUsers as apiSearchUsers,
  type ProjectMemberVO,
  type UserGroupVO,
  type UserSearchVO
} from '@/api/projectMember'
import { formatDateTime } from '@/utils/date'

const props = defineProps<{
  active: boolean
  isProjectAdmin: boolean
  projectId: number
}>()

const { t } = useI18n()

const membersLoading = ref(false)
const members = ref<ProjectMemberVO[]>([])
const loaded = ref(false)

const inviteTab = ref('user')
const selectedUsers = ref<number[]>([])
const memberRole = ref(0)
const searchResults = ref<UserSearchVO[]>([])
const searching = ref(false)
const adding = ref(false)

const selectedGroup = ref<number | null>(null)
const groupResults = ref<UserGroupVO[]>([])
const groupMembers = ref<UserSearchVO[]>([])
const groupSearching = ref(false)
const addingGroup = ref(false)

const loadMembers = async () => {
  membersLoading.value = true
  try {
    members.value = await getProjectMembers(props.projectId)
    loaded.value = true
  } catch {
    ElMessage.error(t('projectDetail.members.loadError'))
  } finally {
    membersLoading.value = false
  }
}

const handleSearchUsers = async (keyword: string) => {
  if (!keyword || keyword.length < 1) {
    searchResults.value = []
    return
  }
  searching.value = true
  try {
    searchResults.value = await apiSearchUsers(keyword)
  } catch {
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

const handleAddMembers = async () => {
  if (selectedUsers.value.length === 0) {
    ElMessage.warning(t('projectDetail.members.selectMemberWarning'))
    return
  }

  adding.value = true
  try {
    await addProjectMembers(props.projectId, selectedUsers.value, memberRole.value === 1)
    ElMessage.success(t('projectDetail.members.addSuccess'))
    selectedUsers.value = []
    await loadMembers()
  } catch {
    ElMessage.error(t('projectDetail.members.addError'))
  } finally {
    adding.value = false
  }
}

const handleRemoveMember = async (member: ProjectMemberVO) => {
  try {
    await ElMessageBox.confirm(
      t('projectDetail.members.removeConfirm', { name: member.username || member.account }),
      t('projectDetail.members.removeTitle'),
      { type: 'warning' }
    )
    await removeProjectMember(props.projectId, member.userId)
    ElMessage.success(t('projectDetail.members.removeSuccess'))
    await loadMembers()
  } catch {
    // User cancelled.
  }
}

const searchGroups = async (keyword: string) => {
  if (!keyword || keyword.length < 1) {
    groupResults.value = []
    return
  }
  groupSearching.value = true
  try {
    groupResults.value = await searchUserGroups(keyword)
  } catch {
    groupResults.value = []
  } finally {
    groupSearching.value = false
  }
}

const handleGroupChange = async (groupId: number) => {
  if (!groupId) {
    groupMembers.value = []
    return
  }
  try {
    groupMembers.value = await getUserGroupMembers(groupId)
  } catch {
    groupMembers.value = []
  }
}

const handleAddGroupMembers = async () => {
  if (!selectedGroup.value) {
    ElMessage.warning(t('projectDetail.members.selectGroupWarning'))
    return
  }

  addingGroup.value = true
  try {
    await addProjectGroupMembers(props.projectId, selectedGroup.value, memberRole.value === 1)
    ElMessage.success(t('projectDetail.members.addGroupSuccess'))
    selectedGroup.value = null
    groupMembers.value = []
    await loadMembers()
  } catch {
    ElMessage.error(t('projectDetail.members.addGroupError'))
  } finally {
    addingGroup.value = false
  }
}

const formatDate = formatDateTime

watch(() => props.active, (active) => {
  if (active && !loaded.value) {
    loadMembers()
  }
}, { immediate: true })
</script>

<style lang="scss" scoped>
.members-page {
  .members-action-card {
    margin-bottom: 16px;

    :deep(.el-card__header) {
      padding: 16px 24px;
      border-bottom: 1px solid $border-light;
    }

    :deep(.el-card__body) {
      padding: 16px 24px;
    }
  }

  .members-list-card {
    :deep(.el-card__header) {
      padding: 16px 24px;
      border-bottom: 1px solid $border-light;
    }

    :deep(.el-card__body) {
      padding: 16px;
    }
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;

  .section-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }

  .member-count {
    font-size: 14px;
    color: var(--jsp-text-secondary);
  }
}

.invite-form {
  .invite-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 16px;
    }
  }

  .invite-content {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
  }
}

.user-select {
  :deep(.el-select__tags-text) {
    font-size: 12px;
  }

  .user-option-name {
    color: $text-placeholder;
    font-size: 12px;
    margin-left: 8px;
  }
}

.group-option-count {
  color: $text-placeholder;
  font-size: 12px;
  margin-left: 8px;
}

.group-members-preview {
  margin-top: 16px;
  padding: 12px;
  background: var(--jsp-bg-card);
  border-radius: 6px;
  border-bottom: 1px solid var(--jsp-border-color);

  .preview-header {
    margin-bottom: 8px;

    .preview-title {
      font-size: 13px;
      color: var(--jsp-text-secondary);
    }
  }

  .preview-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    .member-tag {
      margin-right: 0;
    }
  }
}
</style>
