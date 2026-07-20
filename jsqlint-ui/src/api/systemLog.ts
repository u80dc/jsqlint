import request from './request'
import type { PageResult } from './types'

export interface SystemLog {
  id: number
  operationType: string
  operationUser?: string
  operationUserName?: string
  operationUserId: number
  operationTime: string
  ipAddress: string
  details?: string
  operationMessage?: string
  relatedResourceType?: string
  relatedResourceId?: number
  createTime: string
}

export interface SystemLogPageParams {
  page?: number
  size?: number
  operationType?: string
  operationUserId?: number
  startTime?: string
  endTime?: string
}

export const getSystemLogs = (params: SystemLogPageParams = {}): Promise<PageResult<SystemLog>> => {
  return request.get<PageResult<SystemLog>>('/api/system/logs', { params })
}

export const getSystemLogsByRelatedId = (
  relatedId: number,
  page: number = 1,
  size: number = 20
): Promise<PageResult<SystemLog>> => {
  return request.get<PageResult<SystemLog>>(`/api/system/logs/related/${relatedId}`, {
    params: { page, size }
  })
}
