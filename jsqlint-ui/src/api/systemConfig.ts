import request from './request'

export interface SystemBasicSettingsVO {
  themeColor?: string
  language?: string
  platformName?: string
}

export interface SystemSettingsVO {
  basic?: SystemBasicSettingsVO
  messageNotification?: Record<string, unknown>
  projectAnalysis?: Record<string, unknown>
  operationLog?: Record<string, unknown>
}

export interface SystemSettingsDTO {
  basic?: {
    themeColor?: string
    language?: string
    platformName?: string
  }
  messageNotification?: Record<string, unknown>
  projectAnalysis?: Record<string, unknown>
  operationLog?: Record<string, unknown>
}

export const getSystemSettings = (): Promise<SystemSettingsVO> => {
  return request.get<SystemSettingsVO>('/api/system/settings')
}

export const saveSystemSettings = (data: SystemSettingsDTO): Promise<SystemSettingsVO> => {
  return request.put<SystemSettingsVO>('/api/system/settings', data)
}
