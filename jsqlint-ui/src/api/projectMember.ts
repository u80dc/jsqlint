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
  return request.get<ProjectMemberVO[]>(`/api/projects/${projectId}/members`)
}

export const searchUsers = (keyword: string): Promise<UserSearchVO[]> => {
  return request.get<UserSearchVO[]>('/api/users/search', { params: { keyword } })
}

export const addProjectMembers = (projectId: number, userIds: number[], isAdmin: boolean): Promise<void> => {
  return request.post<void>(`/api/projects/${projectId}/members/batch`, userIds, { params: { isAdmin } })
}

export const removeProjectMember = (projectId: number, userId: number): Promise<void> => {
  return request.delete<void>(`/api/projects/${projectId}/members/${userId}`)
}

export const checkIsProjectAdmin = (projectId: number, userId: number): Promise<boolean> => {
  return request.get<boolean>(`/api/projects/${projectId}/members/check`, { params: { userId } })
}

export const searchUserGroups = (keyword: string): Promise<UserGroupVO[]> => {
  return request.get<UserGroupVO[]>('/api/user-groups/search', { params: { keyword } })
}

export const getUserGroupMembers = (groupId: number): Promise<UserSearchVO[]> => {
  return request.get<UserSearchVO[]>(`/api/user-groups/${groupId}/members`)
}

export const addProjectGroupMembers = (projectId: number, groupId: number, isAdmin: boolean): Promise<void> => {
  return request.post<void>(`/api/projects/${projectId}/members/group`, null, { params: { groupId, isAdmin } })
}
