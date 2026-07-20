<template>
  <div class="settings">
    <el-card>
      <el-tabs v-model="activeTab" class="settings-tabs">
        <!-- 基础配置 -->
        <el-tab-pane :label="t('settings.basic.tab')" name="basic">
          <el-form :model="basicSettings" label-width="140px" class="settings-form">
            <el-form-item :label="t('settings.basic.themeColor')">
              <el-select v-model="basicSettings.themeColor" :placeholder="t('settings.basic.themeColor')">
                <el-option :label="t('settings.basic.themeBlue')" value="blue" />
                <el-option :label="t('settings.basic.themePurple')" value="purple" />
                <el-option :label="t('settings.basic.themeGreen')" value="green" />
              </el-select>
            </el-form-item>

            <el-form-item :label="t('settings.basic.language')">
              <el-select v-model="basicSettings.language" :placeholder="t('settings.basic.language')">
                <el-option :label="t('settings.basic.languageZh')" value="zh-CN" />
                <el-option :label="t('settings.basic.languageEn')" value="en-US" />
              </el-select>
            </el-form-item>
          </el-form>

          <div class="settings-actions">
            <el-button type="primary" @click="saveBasicSettings">{{ t('common.save') }}</el-button>
            <el-button @click="resetBasicSettings">{{ t('common.reset') }}</el-button>
          </div>
        </el-tab-pane>

        <!-- 消息与通知 -->
        <el-tab-pane :label="t('settings.messageNotification.tab')" name="messageNotification">
          <el-form :model="messageNotificationSettings" label-width="180px" class="settings-form">
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

            <el-form-item :label="t('settings.messageNotification.recipientRules')">
              <el-checkbox-group v-model="messageNotificationSettings.recipientRules">
                <el-checkbox label="PROJECT_CREATOR">{{ t('settings.messageNotification.recipientCreator') }}</el-checkbox>
                <el-checkbox label="PROJECT_ADMINS">{{ t('settings.messageNotification.recipientAdmins') }}</el-checkbox>
                <el-checkbox label="PROJECT_MEMBERS">{{ t('settings.messageNotification.recipientMembers') }}</el-checkbox>
                <el-checkbox label="TASK_OPERATOR">{{ t('settings.messageNotification.recipientOperator') }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-form>

          <div class="settings-actions">
            <el-button type="primary" @click="saveMessageNotificationSettings">{{ t('common.save') }}</el-button>
            <el-button @click="testEmailConnection">{{ t('settings.messageNotification.testConnection') }}</el-button>
            <el-button @click="resetMessageNotificationSettings">{{ t('common.reset') }}</el-button>
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

          <div class="settings-actions">
            <el-button type="primary" @click="saveProjectSettings">{{ t('common.save') }}</el-button>
            <el-button @click="resetProjectSettings">{{ t('common.reset') }}</el-button>
          </div>
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

          <div class="settings-actions">
            <el-button type="primary" @click="saveLogSettings">{{ t('common.save') }}</el-button>
            <el-button @click="resetLogSettings">{{ t('common.reset') }}</el-button>
          </div>
        </el-tab-pane>

      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { locale, t } = useI18n()

const activeTab = ref('basic')

const basicSettings = reactive({
  themeColor: 'blue',
  language: localStorage.getItem('language') || 'zh-CN'
})

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
  // 如果语言发生变化，需要刷新页面
  const currentLanguage = localStorage.getItem('language') || 'zh-CN'
  const languageChanged = basicSettings.language !== currentLanguage

  // 保存语言设置
  localStorage.setItem('language', basicSettings.language)
  locale.value = basicSettings.language
  document.title = basicSettings.language === 'en-US' ? 'JSQLint System' : 'SQL质量管理平台'

  // TODO: 调用保存基础配置的 API
  ElMessage.success(t('settings.basic.saveSuccess'))

  // 如果语言变化，提示刷新
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

const resetBasicSettings = () => {
  basicSettings.themeColor = 'blue'
  basicSettings.language = 'zh-CN'
  ElMessage.info(t('settings.basic.resetSuccess'))
}

const saveMessageNotificationSettings = () => {
  // TODO: 调用保存消息与通知配置的 API
  ElMessage.success(t('settings.messageNotification.saveSuccess'))
}

const resetMessageNotificationSettings = () => {
  messageNotificationSettings.notifyNewProject = true
  messageNotificationSettings.notifyRemoveProject = true
  messageNotificationSettings.notifyAuditComplete = true
  messageNotificationSettings.notifyHighRiskSql = true
  messageNotificationSettings.emailEnabled = true
  messageNotificationSettings.host = ''
  messageNotificationSettings.port = 465
  messageNotificationSettings.useSsl = true
  messageNotificationSettings.senderEmail = ''
  messageNotificationSettings.senderName = defaultSenderName.value
  messageNotificationSettings.username = ''
  messageNotificationSettings.password = ''
  messageNotificationSettings.notificationTemplate = defaultNotificationTemplate.value
  messageNotificationSettings.recipientRules = ['PROJECT_CREATOR', 'PROJECT_ADMINS']
  ElMessage.info(t('settings.messageNotification.resetSuccess'))
}

const saveProjectSettings = () => {
  // TODO: 调用保存项目配置的 API
  ElMessage.success(t('settings.project.saveSuccess'))
}

const resetProjectSettings = () => {
  projectSettings.analysisTaskTimeoutMinutes = 60
  projectSettings.compileTaskTimeoutMinutes = 30
  projectSettings.maxConcurrentTasks = 4
  projectSettings.reportRetentionCount = 20
  projectSettings.highRiskAlertEnabled = true
  projectSettings.allowNormalUserCreateProject = true
  ElMessage.info(t('settings.project.resetSuccess'))
}

const saveLogSettings = () => {
  // TODO: 调用保存日志配置的 API
  ElMessage.success(t('settings.log.saveSuccess'))
}

const resetLogSettings = () => {
  logSettings.operationLogRetentionDays = 180
  logSettings.operationLogEnabled = true
  logSettings.loginLogEnabled = true
  logSettings.auditExportEnabled = true
  ElMessage.info(t('settings.log.resetSuccess'))
}

const testEmailConnection = () => {
  // TODO: 调用测试邮件连接的 API
  ElMessage.info(t('settings.messageNotification.testingConnection'))
}
</script>

<style lang="scss" scoped>
.settings {
  .settings-tabs {
    :deep(.el-tabs__content) {
      padding-top: 20px;
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
      color: #64748b;
    }

    .form-tip {
      display: block;
      margin-top: 8px;
      font-size: 12px;
      color: #94a3b8;
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
        color: #1e293b;
      }
    }
  }

  .settings-actions {
    margin-top: 32px;
    padding-top: 20px;
    border-top: 1px solid #f1f5f9;
    display: flex;
    gap: 12px;
  }
}
</style>
