import request from './request'

export interface UserGroupVO {
  id: number
  name: string
  description: string
  creatorId: number
  creatorName: string
  status: number
  memberCount: number
  createTime: string
  updateTime?: string
}

export interface UserGroupCreateDTO {
  name: string
  description?: string
}

export interface UserGroupUpdateDTO {
  id: number
  name?: string
  description?: string
  status?: number
}

export interface UserGroupMemberVO {
  id: number
  username: string
  realName?: string
  email?: string
  phone?: string
  role?: string
  isAdmin?: boolean
}

export const getUserGroups = (): Promise<UserGroupVO[]> => {
  return request.get<UserGroupVO[]>('/api/user-groups')
}

export const getUserGroup = (id: number): Promise<UserGroupVO> => {
  return request.get<UserGroupVO>(`/api/user-groups/${id}`)
}

export const createUserGroup = (data: UserGroupCreateDTO): Promise<UserGroupVO> => {
  return request.post<UserGroupVO>('/api/user-groups', data)
}

export const updateUserGroup = (id: number, data: UserGroupUpdateDTO): Promise<UserGroupVO> => {
  return request.put<UserGroupVO>(`/api/user-groups/${id}`, data)
}

export const deleteUserGroup = (id: number): Promise<void> => {
  return request.delete<void>(`/api/user-groups/${id}`)
}

export const getUserGroupMembers = (id: number): Promise<UserGroupMemberVO[]> => {
  return request.get<UserGroupMemberVO[]>(`/api/user-groups/${id}/members`)
}

export const addUserGroupMember = (groupId: number, userId: number, isAdmin: boolean = false): Promise<void> => {
  return request.post<void>(`/api/user-groups/${groupId}/members`, null, {
    params: { userId, isAdmin }
  })
}

export const removeUserGroupMember = (groupId: number, userId: number): Promise<void> => {
  return request.delete<void>(`/api/user-groups/${groupId}/members/${userId}`)
}

export const getMyUserGroups = (): Promise<UserGroupVO[]> => {
  return request.get<UserGroupVO[]>('/api/user-groups/my')
}
