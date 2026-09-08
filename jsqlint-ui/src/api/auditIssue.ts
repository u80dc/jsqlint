import request from './request'

export interface AuditIssueVO {
  id: number
  projectId: number
  scanId?: number
  ruleId?: number
  ruleName?: string
  severity?: string
  message?: string
  filePath?: string
  lineNumber?: number
  sqlContent?: string
  sqlSummary?: string
  suggestion?: string
  status: string
  statusDescription?: string
  handleUserId?: number
  handleUserName?: string
  handleTime?: string
  handleRemark?: string
  createTime?: string
}

export interface AuditIssueListParams {
  scanId?: number
  status?: string
  severity?: string
  page?: number
  pageSize?: number
}

export interface TransitStatusDTO {
  status: string
  remark?: string
  addToWhitelist?: boolean
}

export const getAuditIssues = (
  projectId: number,
  params?: AuditIssueListParams
): Promise<{ list: AuditIssueVO[]; total: number }> => {
  return request.get(`/api/projects/${projectId}/issues`, { params })
}

export const getAuditIssueStatistics = (projectId: number): Promise<Record<string, number>> => {
  return request.get(`/api/projects/${projectId}/issues/statistics`)
}

export const transitIssueStatus = (
  projectId: number,
  resultId: number,
  data: TransitStatusDTO
): Promise<AuditIssueVO> => {
  return request.put(`/api/projects/${projectId}/issues/${resultId}/status`, data)
}
