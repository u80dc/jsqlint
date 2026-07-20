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
