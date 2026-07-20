import request from './request'
import type { AuditRuleVO } from './auditRule'

export interface ProjectAuditRuleDTO {
  name?: string
  code?: string
  description?: string
  severity?: string
  category?: string
  droolsContent?: string
  suggestion?: string
  enabled?: boolean
}

export interface ProjectAuditRuleListResponse {
  list: AuditRuleVO[]
  total: number
}

export interface ProjectAuditRuleListParams {
  page?: number
  pageSize?: number
  name?: string
  category?: string
  enabled?: boolean
}

export const getProjectAuditRules = (
  projectId: number,
  params: ProjectAuditRuleListParams
): Promise<ProjectAuditRuleListResponse> => {
  return request.get<ProjectAuditRuleListResponse>(`/api/project/${projectId}/rules`, { params })
}

export const getProjectAuditRule = (projectId: number, id: number): Promise<AuditRuleVO> => {
  return request.get<AuditRuleVO>(`/api/project/${projectId}/rules/${id}`)
}

export const createProjectAuditRule = (projectId: number, data: ProjectAuditRuleDTO): Promise<AuditRuleVO> => {
  return request.post<AuditRuleVO>(`/api/project/${projectId}/rules`, data)
}

export const updateProjectAuditRule = (projectId: number, id: number, data: ProjectAuditRuleDTO): Promise<AuditRuleVO> => {
  return request.put<AuditRuleVO>(`/api/project/${projectId}/rules/${id}`, data)
}

export const deleteProjectAuditRule = (projectId: number, id: number): Promise<void> => {
  return request.delete<void>(`/api/project/${projectId}/rules/${id}`)
}

export const toggleProjectAuditRule = (projectId: number, id: number, enabled: boolean): Promise<AuditRuleVO> => {
  return request.put<AuditRuleVO>(`/api/project/${projectId}/rules/${id}/toggle`, { enabled })
}
