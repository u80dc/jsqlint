import request from './request'

export interface ProjectVO {
  id: number
  name: string
  description?: string
  gitUrl: string
  ref?: string
  status?: number
  createTime?: string
  createUserId?: number
  creatorName?: string
  credentialId?: number
  credentialName?: string
  credentialCreatorId?: number
  credentialCreatorName?: string
  credentialVisibility?: number
  workspaceId?: number
  workspaceName?: string
  lastReviewStatus?: number
  reviewWeather?: number
  lastScan?: {
    dql?: number
    dml?: number
    ddl?: number
    dcl?: number
    scanTime?: string
  }
  lastReview?: {
    passed?: number
    warning?: number
    critical?: number
    reviewTime?: string
  }
}

export interface ProjectCreateDTO {
  name: string
  description?: string
  gitUrl: string
  ref: string
  credentialId?: number
  workspaceId?: number
}

export interface CredentialVO {
  id: number
  name: string
  account: string
  visibility: number
  status: number
  createUserId: number
  createUserName: string
  createTime: string
}

export interface ProjectVersionVO {
  id: number
  projectId: number
  projectName?: string
  ref: string
  commitId?: string
  commitMessage?: string
  author?: string
  operatorId?: number
  operatorName?: string
  operationTime?: string
  overallStatus?: number
  overallStatusName?: string
  currentStep?: number
  currentStepName?: string
  message?: string
  pullTime?: string
  status?: number
}

export interface ProjectAnalysisVO {
  success: boolean
  message: string
  data: {
    projectPath: string
    jdkVersion: string
    sourceVersion: string
    targetVersion: string
    dependencyCount: number
    dependencies: Array<{
      groupId: string
      artifactId: string
      version: string
      scope: string
    }>
  }
  errors: string[]
}

export interface ProjectVersionStepVO {
  id?: number
  projectId: number
  versionId?: number
  stepType: number
  stepCode: string
  stepName: string
  stepOrder: number
  status: number
  statusName: string
  completed: number
  taskId?: number
  errorMessage?: string
  startTime?: string
  endTime?: string
}

export interface ProjectStatusVO {
  projectId: number
  versionId?: number
  overallStatus?: number
  overallStatusName?: string
  currentStep?: number
  currentStepName?: string
  message?: string
  steps: ProjectVersionStepVO[]
}

export const getProjects = (workspaceId?: number): Promise<ProjectVO[]> => {
  const params = workspaceId ? { workspaceId } : {}
  return request.get<ProjectVO[]>('/api/workspaces/projects', { params })
}

export const getProject = (id: number): Promise<ProjectVO> => {
  return request.get<ProjectVO>(`/api/project/${id}`)
}

export const createProject = (data: ProjectCreateDTO): Promise<ProjectVO> => {
  return request.post<ProjectVO>('/api/project', data)
}

export const updateProject = (id: number, data: ProjectCreateDTO): Promise<ProjectVO> => {
  return request.put<ProjectVO>(`/api/project/${id}`, data)
}

export const getCredentials = (): Promise<CredentialVO[]> => {
  return request.get<CredentialVO[]>('/api/credentials')
}

export const getProjectCredentials = (): Promise<CredentialVO[]> => {
  return request.get<CredentialVO[]>('/api/project/credentials')
}

export const updateProjectCredential = (projectId: number, credentialId: number | null): Promise<ProjectVO> => {
  return request.put<ProjectVO>(`/api/project/${projectId}/credential`, null, { params: { credentialId } })
}

export const getProjectVersions = (projectId: number): Promise<ProjectVersionVO[]> => {
  return request.get<ProjectVersionVO[]>(`/api/project/${projectId}/versions`)
}

export const getProjectStatus = (projectId: number): Promise<ProjectStatusVO> => {
  return request.get<ProjectStatusVO>(`/api/project/${projectId}/status`)
}

export const syncProject = (projectId: number): Promise<ProjectVersionVO> => {
  return request.post<ProjectVersionVO>(`/api/project/${projectId}/sync`)
}

export const compileProject = (projectId: number): Promise<void> => {
  return request.post<void>(`/api/project/${projectId}/compile`)
}

export const analyzeProject = (projectId: number): Promise<ProjectAnalysisVO> => {
  return request.post<ProjectAnalysisVO>(`/api/project/${projectId}/analyze`)
}

export const auditProject = (projectId: number): Promise<void> => {
  return request.post<void>(`/api/project/${projectId}/audit`)
}

export const generateReportProject = (projectId: number): Promise<number> => {
  return request.post<number>(`/api/project/${projectId}/reports`)
}

export const executeProject = (projectId: number): Promise<boolean> => {
  return request.post<boolean>(`/api/project/${projectId}/execute`)
}

export const deleteProject = (projectId: number): Promise<void> => {
  return request.delete<void>(`/api/project/${projectId}`)
}
