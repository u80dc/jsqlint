import request from './request'

export interface ProjectOverviewSqlTypeCountVO {
  sqlType: string
  count: number
}

export interface ProjectOverviewSqlVO {
  projectId: number
  versionId?: number
  totalSqlCount: number
  typeCounts?: ProjectOverviewSqlTypeCountVO[]
}

export interface ProjectOverviewIssueCategoryVO {
  projectId: number
  versionId?: number
  securitySqlCount: number
  performanceSqlCount: number
  qualitySqlCount: number
  otherSqlCount: number
}

export interface ProjectOverviewIssueRectificationVO {
  projectId: number
  latestVersionId?: number
  previousVersionId?: number
  latestIssueSqlCount: number
  previousIssueSqlCount: number
  changeCount: number
  rectificationRate: number | string
  increaseRate: number | string
  decreaseRate: number | string
  trend: 'UP' | 'DOWN' | 'FLAT'
}

export interface ProjectOverviewIssueSqlRateVO {
  projectId: number
  latestVersionId?: number
  previousVersionId?: number
  latestTotalSqlCount: number
  latestIssueSqlCount: number
  previousIssueSqlCount: number
  issueSqlRate: number | string
  changeCount: number
  increaseRate: number | string
  decreaseRate: number | string
  trend: 'UP' | 'DOWN' | 'FLAT'
}

export interface ProjectOverviewScoreVO {
  projectId: number
  versionId?: number
  totalSqlCount: number
  issueSqlCount: number
  securitySqlCount: number
  performanceSqlCount: number
  qualitySqlCount: number
  otherSqlCount: number
  score: number
  rating: string
}

export interface ProjectOverviewSqlQualityTrendItemVO {
  versionId: number
  ref?: string
  commitId?: string
  operationTime?: string
  totalSqlCount: number
  criticalSqlCount: number
  highSqlCount: number
  warningSqlCount: number
}

export interface ProjectOverviewSqlQualityTrendVO {
  projectId: number
  items?: ProjectOverviewSqlQualityTrendItemVO[]
}

export const getProjectOverviewSql = (projectId: number): Promise<ProjectOverviewSqlVO> => {
  return request.get<ProjectOverviewSqlVO>(`/api/project/overview/${projectId}/sql`)
}

export const getProjectOverviewIssueCategories = (projectId: number): Promise<ProjectOverviewIssueCategoryVO> => {
  return request.get<ProjectOverviewIssueCategoryVO>(`/api/project/overview/${projectId}/issue-categories`)
}

export const getProjectOverviewIssueRectificationRate = (projectId: number): Promise<ProjectOverviewIssueRectificationVO> => {
  return request.get<ProjectOverviewIssueRectificationVO>(`/api/project/overview/${projectId}/issue-rectification-rate`)
}

export const getProjectOverviewIssueSqlRate = (projectId: number): Promise<ProjectOverviewIssueSqlRateVO> => {
  return request.get<ProjectOverviewIssueSqlRateVO>(`/api/project/overview/${projectId}/issue-sql-rate`)
}

export const getProjectOverviewScore = (projectId: number): Promise<ProjectOverviewScoreVO> => {
  return request.get<ProjectOverviewScoreVO>(`/api/project/overview/${projectId}/score`)
}

export const getProjectOverviewSqlQualityTrend = (projectId: number): Promise<ProjectOverviewSqlQualityTrendVO> => {
  return request.get<ProjectOverviewSqlQualityTrendVO>(`/api/project/overview/${projectId}/sql-quality-trend`)
}
