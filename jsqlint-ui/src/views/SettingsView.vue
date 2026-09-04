<template>
  <div class="settings">
    <el-card>
      <el-tabs v-model="activeTab" tab-position="left" class="settings-tabs">
        <!-- 基础配置 -->
        <el-tab-pane :label="t('settings.basic.tab')" name="basic">
          <!-- 个人偏好（存本地，即时生效） -->
          <el-form label-width="140px" class="settings-form">
            <el-divider content-position="left">{{ t('settings.basic.preferenceGroup') }}</el-divider>

            <el-form-item :label="t('settings.basic.themeMode')">
              <el-radio-group v-model="themePreference" @change="handleThemeChange">
                <el-radio-button label="light">{{ t('settings.basic.themeLight') }}</el-radio-button>
                <el-radio-button label="dark">{{ t('settings.basic.themeDark') }}</el-radio-button>
                <el-radio-button label="system">{{ t('settings.basic.themeSystem') }}</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item :label="t('settings.basic.language')">
              <el-select v-model="basicSettings.language" :placeholder="t('settings.basic.language')">
                <el-option :label="t('settings.basic.languageZh')" value="zh-CN" />
                <el-option :label="t('settings.basic.languageEn')" value="en-US" />
              </el-select>
            </el-form-item>

            <el-form-item :label="t('settings.basic.landingPage')">
              <el-select v-model="preferenceSettings.landingPage">
                <el-option :label="t('nav.dashboard')" value="/dashboard" />
                <el-option :label="t('nav.projects')" value="/workspace/projects" />
              </el-select>
            </el-form-item>

            <el-form-item :label="t('settings.basic.defaultPageSize')">
              <el-select v-model="preferenceSettings.defaultPageSize">
                <el-option :label="t('settings.basic.pageSizeRows', { count: 10 })" :value="10" />
                <el-option :label="t('settings.basic.pageSizeRows', { count: 20 })" :value="20" />
                <el-option :label="t('settings.basic.pageSizeRows', { count: 50 })" :value="50" />
              </el-select>
            </el-form-item>
          </el-form>

          <!-- 系统配置（管理员，存后端） -->
          <el-form :model="systemSettings" label-width="140px" class="settings-form">
            <el-divider content-position="left">{{ t('settings.basic.systemGroup') }}</el-divider>

            <el-form-item :label="t('settings.basic.platformName')">
              <el-input
                v-model="systemSettings.platformName"
                maxlength="50"
                show-word-limit
                :placeholder="t('settings.basic.platformNamePlaceholder')"
              />
              <span class="form-tip">{{ t('settings.basic.platformNameTip') }}</span>
            </el-form-item>
          </el-form>

          <!-- 版本信息 -->
          <el-divider content-position="left">{{ t('settings.basic.aboutGroup') }}</el-divider>
          <el-descriptions :column="1" border class="about-desc">
            <el-descriptions-item :label="t('settings.basic.version')">{{ appVersion }}</el-descriptions-item>
            <el-descriptions-item :label="t('settings.basic.backendUrl')">{{ backendUrl }}</el-descriptions-item>
            <el-descriptions-item :label="t('settings.basic.license')">MIT</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- 消息与通知 -->
        <el-tab-pane :label="t('settings.messageNotification.tab')" name="messageNotification">
          <div class="notification-columns">
            <!-- 左列：通知开关 + 收件规则 -->
            <el-form :model="messageNotificationSettings" label-width="180px" class="settings-form notification-col">
              <el-divider content-position="left">{{ t('settings.messageNotification.messageGroup') }}</el-divider>

              <el-form-item :label="t('settings.messageNotification.notifyNewProject')">
                <el-switch v-model="messageNotificationSettings.notifyNewProject" />
                <span class="switch-tip">{{ t('settings.messageNotification.notifyNewProjectTip') }}</span>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.notifyRemoveProject')">
                <el-switch v-model="messageNotificationSettings.notifyRemoveProject" />
                <span class="switch-tip">{{ t('settings.messageNotification.notifyRemoveProjectTip') }}</span>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.notifyAuditComplete')">
                <el-switch v-model="messageNotificationSettings.notifyAuditComplete" />
                <span class="switch-tip">{{ t('settings.messageNotification.notifyAuditCompleteTip') }}</span>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.notifyHighRiskSql')">
                <el-switch v-model="messageNotificationSettings.notifyHighRiskSql" />
                <span class="switch-tip">{{ t('settings.messageNotification.notifyHighRiskSqlTip') }}</span>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.recipientRules')">
                <el-checkbox-group v-model="messageNotificationSettings.recipientRules">
                  <el-checkbox label="PROJECT_CREATOR">{{ t('settings.messageNotification.recipientCreator') }}</el-checkbox>
                  <el-checkbox label="PROJECT_ADMINS">{{ t('settings.messageNotification.recipientAdmins') }}</el-checkbox>
                  <el-checkbox label="PROJECT_MEMBERS">{{ t('settings.messageNotification.recipientMembers') }}</el-checkbox>
                  <el-checkbox label="TASK_OPERATOR">{{ t('settings.messageNotification.recipientOperator') }}</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-form>

            <!-- 右列：邮件服务器 + 模板 -->
            <el-form :model="messageNotificationSettings" label-width="180px" class="settings-form notification-col">
              <el-divider content-position="left">{{ t('settings.messageNotification.notificationGroup') }}</el-divider>

              <el-form-item :label="t('settings.messageNotification.emailEnabled')">
                <el-switch v-model="messageNotificationSettings.emailEnabled" />
                <span class="switch-tip">{{ t('settings.messageNotification.emailEnabledTip') }}</span>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.emailSmtp')">
                <div class="smtp-grid">
                  <el-input
                    v-model="messageNotificationSettings.host"
                    :placeholder="t('settings.messageNotification.smtpHostPlaceholder')"
                  />
                  <el-input-number
                    v-model="messageNotificationSettings.port"
                    :min="1"
                    :max="65535"
                    controls-position="right"
                  />
                  <el-switch
                    v-model="messageNotificationSettings.useSsl"
                    :active-text="t('settings.messageNotification.sslOn')"
                    :inactive-text="t('settings.messageNotification.sslOff')"
                  />
                </div>
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.senderEmail')">
                <el-input
                  v-model="messageNotificationSettings.senderEmail"
                  placeholder="noreply@example.com"
                />
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.senderName')">
                <el-input
                  v-model="messageNotificationSettings.senderName"
                  :placeholder="t('settings.messageNotification.senderNamePlaceholder')"
                />
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.authAccount')">
                <el-input
                  v-model="messageNotificationSettings.username"
                  :placeholder="t('settings.messageNotification.authAccountPlaceholder')"
                />
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.authPassword')">
                <el-input
                  v-model="messageNotificationSettings.password"
                  type="password"
                  :placeholder="t('settings.messageNotification.authPasswordPlaceholder')"
                  show-password
                />
              </el-form-item>

              <el-form-item :label="t('settings.messageNotification.notificationTemplate')">
                <el-input
                  v-model="messageNotificationSettings.notificationTemplate"
                  type="textarea"
                  :rows="5"
                  :placeholder="t('settings.messageNotification.notificationTemplatePlaceholder')"
                />
                <span class="form-tip">{{ t('settings.messageNotification.notificationTemplateTip') }}</span>
              </el-form-item>

              <div class="settings-actions">
                <el-button @click="testEmailConnection">{{ t('settings.messageNotification.testConnection') }}</el-button>
              </div>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- 项目配置 -->
        <el-tab-pane :label="t('settings.project.tab')" name="project">
          <el-form :model="projectSettings" label-width="180px" class="settings-form">
            <el-divider content-position="left">{{ t('settings.project.taskGroup') }}</el-divider>

            <el-form-item :label="t('settings.project.analysisTaskTimeoutMinutes')">
              <el-input-number
                v-model="projectSettings.analysisTaskTimeoutMinutes"
                :min="1"
                :max="1440"
                controls-position="right"
              />
              <span class="form-tip inline-tip">{{ t('settings.project.unitMinute') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.project.compileTaskTimeoutMinutes')">
              <el-input-number
                v-model="projectSettings.compileTaskTimeoutMinutes"
                :min="1"
                :max="1440"
                controls-position="right"
              />
              <span class="form-tip inline-tip">{{ t('settings.project.unitMinute') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.project.maxConcurrentTasks')">
              <el-input-number
                v-model="projectSettings.maxConcurrentTasks"
                :min="1"
                :max="64"
                controls-position="right"
              />
            </el-form-item>

            <el-form-item :label="t('settings.project.runningTaskTimeoutMinutes')">
              <el-input-number
                v-model="projectSettings.runningTaskTimeoutMinutes"
                :min="5"
                :max="1440"
                controls-position="right"
              />
              <span class="form-tip inline-tip">{{ t('settings.project.unitMinute') }}</span>
              <span class="form-tip">{{ t('settings.project.runningTaskTimeoutMinutesTip') }}</span>
            </el-form-item>

            <el-divider content-position="left">{{ t('settings.project.reportGroup') }}</el-divider>

            <el-form-item :label="t('settings.project.reportRetentionCount')">
              <el-input-number
                v-model="projectSettings.reportRetentionCount"
                :min="1"
                :max="500"
                controls-position="right"
              />
            </el-form-item>

            <el-form-item :label="t('settings.project.highRiskAlertEnabled')">
              <el-switch v-model="projectSettings.highRiskAlertEnabled" />
              <span class="switch-tip">{{ t('settings.project.highRiskAlertEnabledTip') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.project.allowNormalUserCreateProject')">
              <el-switch v-model="projectSettings.allowNormalUserCreateProject" />
              <span class="switch-tip">{{ t('settings.project.allowNormalUserCreateProjectTip') }}</span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 日志配置 -->
        <el-tab-pane :label="t('settings.log.tab')" name="log">
          <el-form :model="logSettings" label-width="180px" class="settings-form">
            <el-divider content-position="left">{{ t('settings.log.auditGroup') }}</el-divider>

            <el-form-item :label="t('settings.log.operationLogRetentionDays')">
              <el-input-number
                v-model="logSettings.operationLogRetentionDays"
                :min="1"
                :max="3650"
                controls-position="right"
              />
              <span class="form-tip inline-tip">{{ t('settings.log.unitDay') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.log.operationLogEnabled')">
              <el-switch v-model="logSettings.operationLogEnabled" />
              <span class="switch-tip">{{ t('settings.log.operationLogEnabledTip') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.log.loginLogEnabled')">
              <el-switch v-model="logSettings.loginLogEnabled" />
              <span class="switch-tip">{{ t('settings.log.loginLogEnabledTip') }}</span>
            </el-form-item>

            <el-form-item :label="t('settings.log.auditExportEnabled')">
              <el-switch v-model="logSettings.auditExportEnabled" />
              <span class="switch-tip">{{ t('settings.log.auditExportEnabledTip') }}</span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useTheme } from '@/composables/useTheme'
import { usePlatformName } from '@/composables/usePlatformName'
import { saveSystemSettings, getSystemSettings } from '@/api/systemConfig'

const { locale, t } = useI18n()

const activeTab = ref('basic')

const basicSettings = reactive({
  language: localStorage.getItem('language') || 'zh-CN'
})

// 个人偏好（localStorage，跨会话保留）
const { themePreference, setTheme } = useTheme()

const preferenceSettings = reactive({
  landingPage: localStorage.getItem('jsqlint_landing_page') || '/dashboard',
  defaultPageSize: Number(localStorage.getItem('jsqlint_page_size')) || 10
})

const handleThemeChange = (value: string | number | boolean) => {
  setTheme(value as 'light' | 'dark' | 'system')
}

// 系统配置（管理员级，存后端）
const { platformName, setPlatformName, refreshPlatformName } = usePlatformName()

const systemSettings = reactive({
  platformName: platformName.value
})

// 版本信息
const appVersion = import.meta.env.MODE === 'production'
  ? `v${document.querySelector('meta[name="version"]')?.getAttribute('content') || '0.1.0'}`
  : '0.1.0 (dev)'
const backendUrl = import.meta.env.VITE_API_BASE_URL || window.location.origin

// 含 ${} 占位符的默认模板按当前语言取值，避免 vue-i18n 命名参数插值的限制
const defaultNotificationTemplate = computed(() => t('settings.messageNotification.defaultTemplate'))
const defaultSenderName = computed(() => t('settings.messageNotification.senderNamePlaceholder'))

const messageNotificationSettings = reactive({
  notifyNewProject: true,
  notifyRemoveProject: true,
  notifyAuditComplete: true,
  notifyHighRiskSql: true,
  emailEnabled: true,
  host: '',
  port: 465,
  useSsl: true,
  senderEmail: '',
  senderName: defaultSenderName.value,
  username: '',
  password: '',
  notificationTemplate: defaultNotificationTemplate.value,
  recipientRules: ['PROJECT_CREATOR', 'PROJECT_ADMINS'] as string[]
})

const projectSettings = reactive({
  analysisTaskTimeoutMinutes: 60,
  compileTaskTimeoutMinutes: 30,
  maxConcurrentTasks: 4,
  reportRetentionCount: 20,
  runningTaskTimeoutMinutes: 60,
  highRiskAlertEnabled: true,
  allowNormalUserCreateProject: true
})

const logSettings = reactive({
  operationLogRetentionDays: 180,
  operationLogEnabled: true,
  loginLogEnabled: true,
  auditExportEnabled: true
})

const saveBasicSettings = async () => {
  // 语言变化时需刷新页面让全站文案生效
  const currentLanguage = localStorage.getItem('language') || 'zh-CN'
  const languageChanged = basicSettings.language !== currentLanguage

  localStorage.setItem('language', basicSettings.language)
  locale.value = basicSettings.language
  document.title = localStorage.getItem('jsqlint_platform_name')
    || (basicSettings.language === 'en-US' ? 'JSQLint System' : 'SQL质量管理平台')

  if (languageChanged) {
    ElMessageBox.confirm(
      t('settings.basic.languageChangedMessage'),
      t('settings.basic.languageChangedTitle'),
      {
        confirmButtonText: t('settings.basic.refreshNow'),
        cancelButtonText: t('settings.basic.refreshLater'),
        type: 'info'
      }
    ).then(() => {
      window.location.reload()
    }).catch(() => {
      // 用户选择稍后刷新
    })
  }
}

// ============================================
// 自动保存：配置变更后防抖落库
// basicSettings 只含语言，由下方专用 watch 即时处理，不参与防抖
// 初始化回填（loadAllSettings）会触发深度 watch，须在回填完成前跳过保存
// ============================================
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null
let settingsHydrated = false

watch(
  [messageNotificationSettings, projectSettings, logSettings],
  () => {
    if (!settingsHydrated) {
      return
    }
    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer)
    }
    autoSaveTimer = setTimeout(() => {
      saveAllSettings()
      autoSaveTimer = null
    }, 600)
  },
  { deep: true }
)

const saveAllSettings = async () => {
  try {
    await saveSystemSettings({
      messageNotification: { ...messageNotificationSettings },
      projectAnalysis: { ...projectSettings },
      operationLog: { ...logSettings }
    })
    ElMessage.success(t('settings.autoSaved'))
  } catch {
    // 保存失败静默，等下次变更重试
  }
}

const loadAllSettings = async () => {
  try {
    const settings = await getSystemSettings()
    if (settings.projectAnalysis) {
      const pa = settings.projectAnalysis
      projectSettings.analysisTaskTimeoutMinutes = pa.analysisTaskTimeoutMinutes ?? 60
      projectSettings.compileTaskTimeoutMinutes = pa.compileTaskTimeoutMinutes ?? 30
      projectSettings.maxConcurrentTasks = pa.maxConcurrentTasks ?? 4
      projectSettings.reportRetentionCount = pa.reportRetentionCount ?? 20
      projectSettings.runningTaskTimeoutMinutes = pa.runningTaskTimeoutMinutes ?? 60
      projectSettings.highRiskAlertEnabled = pa.highRiskAlertEnabled ?? true
      projectSettings.allowNormalUserCreateProject = pa.allowNormalUserCreateProject ?? true
    }
    if (settings.messageNotification) {
      const mn = settings.messageNotification
      messageNotificationSettings.notifyNewProject = mn.notifyNewProject ?? true
      messageNotificationSettings.notifyRemoveProject = mn.notifyRemoveProject ?? true
      messageNotificationSettings.notifyAuditComplete = mn.notifyAuditComplete ?? true
      messageNotificationSettings.notifyHighRiskSql = mn.notifyHighRiskSql ?? true
      messageNotificationSettings.emailEnabled = mn.emailEnabled ?? true
      messageNotificationSettings.host = mn.host ?? ''
      messageNotificationSettings.port = mn.port ?? 465
      messageNotificationSettings.useSsl = mn.useSsl ?? true
      messageNotificationSettings.senderEmail = mn.senderEmail ?? ''
      messageNotificationSettings.senderName = mn.senderName ?? defaultSenderName.value
      messageNotificationSettings.username = mn.username ?? ''
      messageNotificationSettings.password = mn.password ?? ''
      messageNotificationSettings.notificationTemplate = mn.notificationTemplate ?? defaultNotificationTemplate.value
      messageNotificationSettings.recipientRules = Array.isArray(mn.recipientRules) ? mn.recipientRules as string[] : ['PROJECT_CREATOR', 'PROJECT_ADMINS']
    }
    if (settings.operationLog) {
      const ol = settings.operationLog
      logSettings.operationLogRetentionDays = ol.operationLogRetentionDays ?? 180
      logSettings.operationLogEnabled = ol.operationLogEnabled ?? true
      logSettings.loginLogEnabled = ol.loginLogEnabled ?? true
      logSettings.auditExportEnabled = ol.auditExportEnabled ?? true
    }
  } catch {
    // 读取失败保持默认值，仍允许后续手动变更触发保存
  } finally {
    // 等 watch 回调（同步 flush）跳过后再启用自动保存
    setTimeout(() => {
      settingsHydrated = true
    }, 0)
  }
}

loadAllSettings()

// 语言切换需要即时生效（含刷新提示），单独立即触发不走防抖
watch(() => basicSettings.language, () => {
  saveBasicSettings()
})

// 个人偏好：写入 localStorage 即时生效（自动保存）
watch(preferenceSettings, ({ landingPage, defaultPageSize }) => {
  localStorage.setItem('jsqlint_landing_page', landingPage)
  localStorage.setItem('jsqlint_page_size', String(defaultPageSize))
}, { deep: true })

// 平台名称：防抖保存到后端
watch(() => systemSettings.platformName, (name) => {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
  }
  autoSaveTimer = setTimeout(() => {
    savePlatformName(name)
    autoSaveTimer = null
  }, 800)
})

const savePlatformName = async (name: string) => {
  try {
    await saveSystemSettings({ basic: { platformName: name.trim() || 'SQL质量管理平台' } })
    setPlatformName(name.trim() || 'SQL质量管理平台')
    document.title = platformName.value
    ElMessage.success(t('settings.autoSaved'))
  } catch {
    // 保存失败静默，等下次变更重试
  }
}

// 加载后端系统配置
const loadSystemSettings = async () => {
  await refreshPlatformName()
  const cached = localStorage.getItem('jsqlint_platform_name')
  if (cached) {
    systemSettings.platformName = cached
  }
}

loadSystemSettings()

const testEmailConnection = () => {
  // TODO: 调用测试邮件连接的 API
  ElMessage.info(t('settings.messageNotification.testingConnection'))
}
</script>

<style lang="scss" scoped>
.settings {
  .settings-tabs {
    // 左侧竖向标签：固定宽度导航列 + 右侧内容
    :deep(.el-tabs__header) {
      margin-right: 32px;

      .el-tabs__item {
        height: 44px;
        line-height: 44px;
        justify-content: flex-start;
        text-align: left;
      }
    }

    :deep(.el-tabs__content) {
      padding-top: 4px;
      flex: 1;
      min-width: 0;
    }

    // 占满卡片宽度
    display: flex;
    flex-direction: row;
  }

  // 消息通知双栏
  .notification-columns {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 40px;
    align-items: start;

    .notification-col {
      max-width: none;
    }
  }

  @media (max-width: 1100px) {
    // 窄屏退回单栏
    .notification-columns {
      grid-template-columns: 1fr;
    }
  }

  .settings-form {
    max-width: 760px;

    .el-select {
      width: 200px;
    }

    .el-input {
      max-width: 420px;
    }

    .el-textarea {
      max-width: 520px;
    }

    .el-input-number {
      width: 180px;
    }

    .smtp-grid {
      display: grid;
      grid-template-columns: minmax(220px, 1fr) 140px 140px;
      gap: 12px;
      align-items: center;
      max-width: 560px;

      .el-input,
      .el-input-number {
        width: 100%;
        max-width: none;
      }
    }

    .switch-tip {
      margin-left: 12px;
      font-size: 13px;
      color: var(--jsp-text-secondary);
    }

    .form-tip {
      display: block;
      margin-top: 8px;
      font-size: 12px;
      color: $text-placeholder;
      line-height: 1.5;
    }

    .inline-tip {
      display: inline-flex;
      margin-top: 0;
      margin-left: 10px;
      vertical-align: middle;
    }

    :deep(.el-divider) {
      margin: 24px 0;

      .el-divider__text {
        font-size: 14px;
        font-weight: 600;
        color: var(--jsp-text-primary);
      }
    }
  }

  .settings-actions {
    margin-top: 32px;
    padding-top: 20px;
    border-top: 1px solid $border-light;
    display: flex;
    gap: 12px;
  }

  // 版本信息表与表单视觉同宽，不独占整行
  .about-desc {
    max-width: 560px;
    margin-top: 8px;

    :deep(.el-descriptions__label) {
      width: 120px;
    }
  }
}
</style>
