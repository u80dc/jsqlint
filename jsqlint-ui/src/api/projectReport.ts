import request from './request'

export interface ProjectReportVO {
  id: number
  projectId: number
  versionId: number
  taskId?: number
  score: number
  rating: string
  totalSqlCount: number
  issueSqlCount: number
  issueSqlRate: number
  issueCount: number
  criticalCount: number
  criticalSqlCount: number
  highCount: number
  warningCount: number
  infoCount: number
  securityIssueSqlCount: number
  performanceIssueSqlCount: number
  qualityIssueSqlCount: number
  otherIssueSqlCount: number
  topRuleJson?: Array<Record<string, unknown>>
  topFileJson?: Array<Record<string, unknown>>
  highRiskSqlJson?: Array<Record<string, unknown>>
  generatedTime: string
  createTime?: string
  updateTime?: string
}

export const getProjectReport = (projectId: number, versionId?: number): Promise<ProjectReportVO | null> => {
  return request.get<ProjectReportVO | null>(`/api/project/${projectId}/reports`, {
    params: versionId ? { versionId } : undefined
  })
}

export const downloadProjectReportPdf = (projectId: number, versionId?: number): Promise<Blob> => {
  return request.get<Blob>(`/api/project/${projectId}/reports/download`, {
    params: versionId ? { versionId } : undefined,
    responseType: 'blob'
  })
}
