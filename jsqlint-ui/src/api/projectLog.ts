import request from './request'
import type { PageResult } from './types'

export interface ProjectLog {
  id: number
  projectId: number
  operationType: string
  operationUserId: number
  operationUserName: string
  operationMessage: string
  operationTime: string
  ipAddress: string
  relatedId?: number
  extraInfo?: string
  projectName?: string
}

export interface ProjectLogPageParams {
  page?: number
  size?: number
  projectId?: number
  operationType?: string
  operationUserId?: number
  startTime?: string
  endTime?: string
}

export const getProjectLogs = (params: ProjectLogPageParams = {}): Promise<PageResult<ProjectLog>> => {
  return request.get<PageResult<ProjectLog>>('/api/project/logs', { params })
}

export const getProjectLogsByProjectId = (
  projectId: number,
  page: number = 1,
  size: number = 20
): Promise<PageResult<ProjectLog>> => {
  return request.get<PageResult<ProjectLog>>(`/api/project/logs/${projectId}`, {
    params: { page, size }
  })
}

export const getProjectLogsByRelatedId = (
  projectId: number,
  relatedId: number,
  page: number = 1,
  size: number = 20
): Promise<PageResult<ProjectLog>> => {
  return request.get<PageResult<ProjectLog>>(`/api/project/logs/${projectId}/related/${relatedId}`, {
    params: { page, size }
  })
}
