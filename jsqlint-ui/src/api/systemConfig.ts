import request from './request'

export interface SystemBasicSettingsVO {
  themeColor?: string
  language?: string
  platformName?: string
}

export interface SystemProjectAnalysisSettingsVO {
  analysisTaskTimeoutMinutes?: number
  compileTaskTimeoutMinutes?: number
  maxConcurrentTasks?: number
  reportRetentionCount?: number
  runningTaskTimeoutMinutes?: number
  highRiskAlertEnabled?: boolean
  allowNormalUserCreateProject?: boolean
}

export interface SystemMessageNotificationSettingsVO {
  notifyNewProject?: boolean
  notifyRemoveProject?: boolean
  notifyAuditComplete?: boolean
  notifyHighRiskSql?: boolean
  emailEnabled?: boolean
  host?: string
  port?: number
  useSsl?: boolean
  senderEmail?: string
  senderName?: string
  username?: string
  password?: string
  notificationTemplate?: string
  recipientRules?: string[]
}

export interface SystemOperationLogSettingsVO {
  operationLogRetentionDays?: number
  operationLogEnabled?: boolean
  loginLogEnabled?: boolean
  auditExportEnabled?: boolean
}

export interface SystemSettingsVO {
  basic?: SystemBasicSettingsVO
  messageNotification?: SystemMessageNotificationSettingsVO
  projectAnalysis?: SystemProjectAnalysisSettingsVO
  operationLog?: SystemOperationLogSettingsVO
}

export interface SystemSettingsDTO {
  basic?: {
    themeColor?: string
    language?: string
    platformName?: string
  }
  messageNotification?: SystemMessageNotificationSettingsVO
  projectAnalysis?: SystemProjectAnalysisSettingsVO
  operationLog?: SystemOperationLogSettingsVO
}

export const getSystemSettings = (): Promise<SystemSettingsVO> => {
  return request.get<SystemSettingsVO>('/api/system/settings')
}

export const saveSystemSettings = (data: SystemSettingsDTO): Promise<SystemSettingsVO> => {
  return request.put<SystemSettingsVO>('/api/system/settings', data)
}
