import request from './request'

export interface UserGroupVO {
  id: number
  name: string
}

export interface UserVO {
  id: number
  username: string
  realName: string
  email: string
  phone: string
  status: number
  role: string
  roleDescription: string
  createTime: string
  updateTime?: string
  groups?: UserGroupVO[]
}

export interface UserListParams {
  username?: string
  realName?: string
  phone?: string
  email?: string
  status?: number
  page?: number
  pageSize?: number
}

export interface UserPageVO {
  list: UserVO[]
  total: number
}

export interface UserCreateDTO {
  username: string
  password: string
  realName: string
  email?: string
  phone?: string
  status?: number
  role?: string
}

export interface UserUpdateDTO {
  id: number
  realName?: string
  email?: string
  phone?: string
  status?: number
  role?: string
  password?: string
}

export const getUsers = (params?: UserListParams): Promise<UserPageVO> => {
  return request.get<UserPageVO>('/api/admin/users', { params })
}

export const getUser = (id: number): Promise<UserVO> => {
  return request.get<UserVO>(`/api/admin/users/${id}`)
}

export const createUser = (data: UserCreateDTO): Promise<UserVO> => {
  return request.post<UserVO>('/api/admin/users', data)
}

export const updateUser = (data: UserUpdateDTO): Promise<UserVO> => {
  return request.put<UserVO>(`/api/admin/users/${data.id}`, data)
}

export const deleteUser = (id: number): Promise<void> => {
  return request.delete<void>(`/api/admin/users/${id}`)
}

export const enableUser = (id: number): Promise<void> => {
  return request.patch<void>(`/api/admin/users/${id}/enabled`, null, { params: { enabled: true } })
}

export const disableUser = (id: number): Promise<void> => {
  return request.patch<void>(`/api/admin/users/${id}/enabled`, null, { params: { enabled: false } })
}

export const setUserGroups = (userId: number, groupId?: number): Promise<void> => {
  return request.put<void>(`/api/admin/users/${userId}/groups`, null, { params: { groupId } })
}
