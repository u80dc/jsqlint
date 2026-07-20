import request from './request'

export interface AlertRuleDTO {
  name: string
  ruleType: string
  projectId?: number | null
  severity: string
  minSeverity: string
  thresholdCount: number
  notifyProjectMembers: boolean
  notifyCreator: boolean
}

export interface AlertRuleVO extends AlertRuleDTO {
  id: number
  ruleTypeDescription: string
  projectName?: string
  severityDescription: string
  minSeverityDescription: string
  enabled: number
  enabledFlag: boolean
  createTime: string
  updateTime: string
}

export interface AlertInstanceVO {
  id: number
  ruleId: number
  ruleName?: string
  projectId: number
  projectName?: string
  versionId?: number
  taskId?: number
  title: string
  content: string
  severity: string
  severityDescription: string
  status: string
  statusDescription: string
  triggerCount: number
  firstTriggeredAt: string
  lastTriggeredAt: string
  ackTime?: string
  resolvedTime?: string
  createTime: string
}

export interface AlertRuleListParams {
  projectId?: number
  ruleType?: string
  enabled?: boolean
}

export interface AlertInstanceListParams {
  projectId?: number
  severity?: string
  status?: string
}

export const listAlertRules = (params?: AlertRuleListParams): Promise<AlertRuleVO[]> => {
  return request.get<AlertRuleVO[]>('/api/alerts/rules', { params })
}

export const createAlertRule = (data: AlertRuleDTO): Promise<AlertRuleVO> => {
  return request.post<AlertRuleVO>('/api/alerts/rules', data)
}

export const updateAlertRule = (id: number, data: Partial<AlertRuleDTO> & { enabled?: boolean }): Promise<AlertRuleVO> => {
  return request.put<AlertRuleVO>(`/api/alerts/rules/${id}`, data)
}

export const deleteAlertRule = (id: number): Promise<void> => {
  return request.delete<void>(`/api/alerts/rules/${id}`)
}

export const toggleAlertRule = (id: number, enabled: boolean): Promise<AlertRuleVO> => {
  return request.put<AlertRuleVO>(`/api/alerts/rules/${id}/toggle`, { enabled })
}

export const listAlertInstances = (params?: AlertInstanceListParams): Promise<AlertInstanceVO[]> => {
  return request.get<AlertInstanceVO[]>('/api/alerts', { params })
}

export const updateAlertStatus = (id: number, status: string): Promise<void> => {
  return request.put<void>(`/api/alerts/${id}/status`, { status })
}
