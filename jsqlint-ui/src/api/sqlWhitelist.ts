import request from './request'

export interface SqlWhitelistCreateDTO {
  sql: string
  ruleId: number
  reason?: string
}

export interface SqlWhitelistVO {
  id: number
  projectId: number
  sqlFingerprint: string
  sqlSample: string
  ruleId: number
  ruleName?: string
  reason?: string
  createUserName?: string
  createTime?: string
}

export interface SqlWhitelistListParams {
  page: number
  pageSize: number
  ruleId?: number
}

export interface SqlWhitelistListResult {
  list: SqlWhitelistVO[]
  total: number
}

export const getSqlWhitelistList = (
  projectId: number,
  params: SqlWhitelistListParams
): Promise<SqlWhitelistListResult> => {
  return request.get<SqlWhitelistListResult>(`/api/projects/${projectId}/whitelist`, { params })
}

export const createSqlWhitelist = (
  projectId: number,
  data: SqlWhitelistCreateDTO
): Promise<SqlWhitelistVO> => {
  return request.post<SqlWhitelistVO>(`/api/projects/${projectId}/whitelist`, data)
}

export const deleteSqlWhitelist = (projectId: number, id: number): Promise<void> => {
  return request.delete<void>(`/api/projects/${projectId}/whitelist/${id}`)
}
