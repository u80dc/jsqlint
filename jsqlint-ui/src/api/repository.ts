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

export const getMavenRepositories = (type?: number, status?: number): Promise<MavenRepositoryVO[]> => {
  const params: Record<string, number> = {}
  if (type !== undefined && type !== null) {
    params.type = type
  }
  if (status !== undefined && status !== null) {
    params.status = status
  }
  return request.get<MavenRepositoryVO[]>('/api/maven-repositories', { params })
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
