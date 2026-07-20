import request from './request'

export interface WorkspaceVO {
  id: number
  name: string
  creatorId: number
  creatorName: string
  projectCount: number
  createTime: string
  updateTime?: string
}

export interface WorkspaceCreateDTO {
  name: string
}

export const getWorkspaces = (): Promise<WorkspaceVO[]> => {
  return request.get<WorkspaceVO[]>('/api/workspaces')
}

export const createWorkspace = (data: WorkspaceCreateDTO): Promise<WorkspaceVO> => {
  return request.post<WorkspaceVO>('/api/workspaces', data)
}

export const deleteWorkspace = (id: number): Promise<void> => {
  return request.delete<void>(`/api/workspaces/${id}`)
}

export const addProjectToWorkspace = (workspaceId: number, projectId: number): Promise<void> => {
  return request.post<void>(`/api/workspaces/${workspaceId}/projects/${projectId}`)
}

export const removeProjectFromWorkspace = (workspaceId: number, projectId: number): Promise<void> => {
  return request.delete<void>(`/api/workspaces/${workspaceId}/projects/${projectId}`)
}

export * from './project'
export * from './projectMember'
export * from './projectReport'
