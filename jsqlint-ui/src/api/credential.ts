import request from './request'

export interface CredentialDTO {
  name: string
  visibility?: number
  status?: number
  account?: string
  token?: string
  password?: string
}

export interface CredentialVO {
  id: number
  name: string
  visibility: number
  visibilityDescription: string
  status: number
  account: string
  token?: string
  password?: string
  createUserId: number
  createUserName: string
  createTime: string
}

export interface CredentialPageVO {
  list: CredentialVO[]
  total: number
}

export const listCredentials = (params?: { status?: number; page?: number; pageSize?: number }): Promise<CredentialPageVO> => {
  return request.get<CredentialPageVO>('/api/credentials', { params })
}

export const getCredential = (id: number): Promise<CredentialVO> => {
  return request.get<CredentialVO>(`/api/credentials/${id}`)
}

export const getCredentialById = (id: number): Promise<CredentialVO> => {
  return getCredential(id)
}

export const createCredential = (data: CredentialDTO): Promise<CredentialVO> => {
  return request.post<CredentialVO>('/api/credentials', data)
}

export const updateCredential = (id: number, data: CredentialDTO): Promise<CredentialVO> => {
  return request.put<CredentialVO>(`/api/credentials/${id}`, data)
}

export const deleteCredential = (id: number): Promise<void> => {
  return request.delete<void>(`/api/credentials/${id}`)
}

export const toggleCredential = (id: number, status: number): Promise<CredentialVO> => {
  return request.put<CredentialVO>(`/api/credentials/${id}/toggle`, { status })
}
