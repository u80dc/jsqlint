import request from './request'

export interface AuditRuleDTO {
  name: string
  code: string
  description?: string
  severity: string
  category: string
  droolsContent: string
  suggestion?: string
}

export interface AuditRuleVO {
  id: number
  name: string
  code: string
  description?: string
  severity: string
  severityDescription: string
  category: string
  categoryDescription: string
  droolsContent: string
  suggestion?: string
  enabled: number
  enabledFlag?: boolean
  /** 是否内置规则: 0-用户规则, 1-内置规则 */
  isBuiltin?: number
  createUserName?: string
  createTime: string
  updateTime: string
}

export interface AuditRuleListResponse {
  list: AuditRuleVO[]
  total: number
}

export interface AuditRuleUpdateDTO {
  name?: string
  description?: string
  severity?: string
  category?: string
  droolsContent?: string
  suggestion?: string
}

export interface AuditRuleListParams {
  page?: number
  pageSize?: number
  name?: string
  category?: string
  enabled?: boolean
}

export const getAuditRules = (params: AuditRuleListParams): Promise<AuditRuleListResponse> => {
  return request.get<AuditRuleListResponse>('/api/audit-rules', { params })
}

export const getAuditRule = (id: number): Promise<AuditRuleVO> => {
  return request.get<AuditRuleVO>(`/api/audit-rules/${id}`)
}

export const createAuditRule = (data: AuditRuleDTO): Promise<AuditRuleVO> => {
  return request.post<AuditRuleVO>('/api/audit-rules', data)
}

export const updateAuditRule = (id: number, data: AuditRuleUpdateDTO): Promise<AuditRuleVO> => {
  return request.put<AuditRuleVO>(`/api/audit-rules/${id}`, data)
}

export const deleteAuditRule = (id: number): Promise<void> => {
  return request.delete<void>(`/api/audit-rules/${id}`)
}

export const toggleAuditRule = (id: number, enabled: boolean): Promise<AuditRuleVO> => {
  return request.put<AuditRuleVO>(`/api/audit-rules/${id}/toggle`, { enabled })
}

export const validateAuditRule = (ruleContent: string): Promise<{ valid: boolean }> => {
  return request.post<{ valid: boolean }>('/api/audit-rules/validate', { ruleContent })
}

export const reloadAuditRules = (): Promise<{ success: boolean; message: string }> => {
  return request.post<{ success: boolean; message: string }>('/api/audit-rules/reload')
}
