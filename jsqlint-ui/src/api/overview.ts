import request from './request'

export interface OverviewProjectSummaryVO {
  totalProjects: number
  safeSqlCount?: number
  warningSqlCount?: number
  dangerSqlCount?: number
}

export interface OverviewRecentProjectVO {
  projectId: number
  projectName: string
  versionId: number
  ref?: string
  commitId?: string
  commitMessage?: string
  status?: number
  statusDescription?: string
  analysisTime?: string
}

export interface OverviewTrendVO {
  period: string
  labels: string[]
  safe: number[]
  warning: number[]
  danger: number[]
}

export interface OverviewCurrentTaskVO {
  id: number
  projectId: number
  projectName?: string
  versionId?: number
  type?: number
  typeDescription?: string
  status?: number
  statusDescription?: string
  nextExecTime?: number
  retryCount?: number
  maxRetries?: number
  errorMessage?: string
  createTime?: string
  updateTime?: string
}

export const getOverviewProjectSummary = (): Promise<OverviewProjectSummaryVO> => {
  return request.get<OverviewProjectSummaryVO>('/api/overview/projects/summary')
}

export const getOverviewRecentProjects = (): Promise<OverviewRecentProjectVO[]> => {
  return request.get<OverviewRecentProjectVO[]>('/api/overview/recent-projects')
}

export const getOverviewAnalysisTrend = (period: 'week' | 'month' | 'year' = 'week'): Promise<OverviewTrendVO> => {
  return request.get<OverviewTrendVO>('/api/overview/analysis-trend', { params: { period } })
}

export const getOverviewCurrentTasks = (): Promise<OverviewCurrentTaskVO[]> => {
  return request.get<OverviewCurrentTaskVO[]>('/api/overview/current-tasks')
}

export interface OverviewRuleHitItemVO {
  ruleId?: number
  ruleName: string
  ruleCode?: string
  severity?: string
  hitCount: number
}

export interface OverviewRuleHitVO {
  totalHitCount: number
  topRules: OverviewRuleHitItemVO[]
}

export interface OverviewSeverityDistributionVO {
  totalIssueCount: number
  criticalCount: number
  highCount: number
  warningCount: number
  infoCount: number
}

export interface OverviewProjectScoreItemVO {
  projectId: number
  projectName: string
  score: number
  rating: string
  issueSqlCount: number
}

export interface OverviewProjectScoreVO {
  projects: OverviewProjectScoreItemVO[]
}

export const getOverviewRuleHits = (limit = 10): Promise<OverviewRuleHitVO> => {
  return request.get<OverviewRuleHitVO>('/api/overview/rule-hits', { params: { limit } })
}

export const getOverviewSeverityDistribution = (): Promise<OverviewSeverityDistributionVO> => {
  return request.get<OverviewSeverityDistributionVO>('/api/overview/severity-distribution')
}

export const getOverviewProjectScores = (limit = 10): Promise<OverviewProjectScoreVO> => {
  return request.get<OverviewProjectScoreVO>('/api/overview/project-scores', { params: { limit } })
}
