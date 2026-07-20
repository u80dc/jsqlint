import request from './request'

export interface ProjectMemberVO {
  id: number
  projectId: number
  projectName?: string
  userId: number
  account: string
  username: string
  role: number
  roleDescription: string
  joinTime: string
}

export interface UserSearchVO {
  id: number
  username: string
  realName: string
}

export interface UserGroupVO {
  id: number
  name: string
  description: string
  memberCount?: number
}

export const getProjectMembers = (projectId: number): Promise<ProjectMemberVO[]> => {
  return request.get<ProjectMemberVO[]>(`/api/project/${projectId}/members`)
}

export const searchUsers = (keyword: string): Promise<UserSearchVO[]> => {
  return request.get<UserSearchVO[]>('/api/users/search', { params: { keyword } })
}

export const addProjectMembers = (projectId: number, userIds: number[], isAdmin: boolean): Promise<void> => {
  return request.post<void>(`/api/project/${projectId}/members/batch`, userIds, { params: { isAdmin } })
}

export const removeProjectMember = (projectId: number, userId: number): Promise<void> => {
  return request.delete<void>(`/api/project/${projectId}/members/${userId}`)
}

export const checkIsProjectAdmin = (projectId: number, userId: number): Promise<boolean> => {
  return request.get<boolean>(`/api/project/${projectId}/members/check`, { params: { userId } })
}

export const searchUserGroups = (keyword: string): Promise<UserGroupVO[]> => {
  return request.get<UserGroupVO[]>('/api/users/groups/search', { params: { keyword } })
}

export const getUserGroupMembers = (groupId: number): Promise<UserSearchVO[]> => {
  return request.get<UserSearchVO[]>('/api/users/groups/members', { params: { groupId } })
}

export const addProjectGroupMembers = (projectId: number, groupId: number, isAdmin: boolean): Promise<void> => {
  return request.post<void>(`/api/project/${projectId}/members/group`, null, { params: { groupId, isAdmin } })
}
