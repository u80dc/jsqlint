import request from './request'

export interface InspectionConfigVO {
  id: number
  projectId: number
  projectName?: string
  cronExpr: string
  enabled: boolean
  nextExecTime?: number
  lastExecTime?: number
  lastStatus?: string
  lastMessage?: string
  operatorId?: number
  createTime?: string
  updateTime?: string
}

export interface InspectionConfigDTO {
  cronExpr: string
  enabled?: boolean
}

export const listInspectionConfigs = (enabled?: boolean): Promise<InspectionConfigVO[]> => {
  const params = enabled === undefined ? {} : { enabled }
  return request.get<InspectionConfigVO[]>('/api/inspection-configs', { params })
}

export const getInspectionConfig = (projectId: number): Promise<InspectionConfigVO> => {
  return request.get<InspectionConfigVO>(`/api/inspection-configs/${projectId}`)
}

export const saveInspectionConfig = (
  projectId: number,
  data: InspectionConfigDTO
): Promise<InspectionConfigVO> => {
  return request.put<InspectionConfigVO>(`/api/inspection-configs/${projectId}`, data)
}

export const setInspectionEnabled = (projectId: number, enabled: boolean): Promise<void> => {
  return request.patch<void>(`/api/inspection-configs/${projectId}/enabled`, null, {
    params: { enabled }
  })
}

export const deleteInspectionConfig = (projectId: number): Promise<void> => {
  return request.delete<void>(`/api/inspection-configs/${projectId}`)
}
