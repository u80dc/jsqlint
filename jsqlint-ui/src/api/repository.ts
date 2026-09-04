import request from './request'

export interface MavenRepositoryVO {
  id: number
  type: number
  repoId: string
  name: string
  url: string
  layout: string
  releasesEnabled: boolean
  releasesUpdatePolicy: string
  releasesChecksumPolicy: string
  snapshotsEnabled: boolean
  snapshotsUpdatePolicy: string
  snapshotsChecksumPolicy: string
  status: number
  createTime: string
  updateTime: string
}

export interface MavenRepositoryDTO {
  type: number
  repoId: string
  name: string
  url: string
  layout?: string
  releasesEnabled?: boolean
  releasesUpdatePolicy?: string
  releasesChecksumPolicy?: string
  snapshotsEnabled?: boolean
  snapshotsUpdatePolicy?: string
  snapshotsChecksumPolicy?: string
  status?: number
}

export interface MavenRepositoryPageVO {
  list: MavenRepositoryVO[]
  total: number
}

export interface MavenRepositoryListParams {
  type?: number
  status?: number
  page?: number
  pageSize?: number
}

export const getMavenRepositories = (params?: MavenRepositoryListParams): Promise<MavenRepositoryPageVO> => {
  return request.get<MavenRepositoryPageVO>('/api/maven-repositories', { params })
}

export const getMavenRepositoryById = (id: number): Promise<MavenRepositoryVO> => {
  return request.get<MavenRepositoryVO>(`/api/maven-repositories/${id}`)
}

export const createMavenRepository = (data: MavenRepositoryDTO): Promise<MavenRepositoryVO> => {
  return request.post<MavenRepositoryVO>('/api/maven-repositories', data)
}

export const updateMavenRepository = (id: number, data: MavenRepositoryDTO): Promise<MavenRepositoryVO> => {
  return request.put<MavenRepositoryVO>(`/api/maven-repositories/${id}`, data)
}

export const deleteMavenRepository = (id: number): Promise<void> => {
  return request.delete<void>(`/api/maven-repositories/${id}`)
}
