<template>
  <div class="profile">
    <el-card>
      <template #header>
        <span>个人信息</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户名">{{ userInfo.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ userInfo.realName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ userInfo.roleDescription }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag type="success">启用</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div class="profile-actions">
        <el-button type="primary">修改密码</el-button>
        <el-button>编辑信息</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const userInfo = computed(() => ({
  username: userStore.username,
  realName: userStore.realName,
  role: userStore.role,
  roleDescription: userStore.roleDescription
}))
</script>

<style lang="scss" scoped>
.profile {
  .profile-actions {
    margin-top: 20px;
    display: flex;
    gap: 12px;
  }
}
</style>