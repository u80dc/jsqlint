import request from './request'

export interface LoginDTO {
  username: string
  password: string
}

export interface LoginVO {
  id: number
  token: string
  username: string
  realName: string
  role: string
  roleDescription: string
  expiresIn: number
}

export interface UserInfoVO {
  id: number
  username: string
  realName: string
  email?: string
  phone?: string
  status?: number
  role: string
  roleDescription: string
  createTime?: string
  updateTime?: string
}

export interface ChangePasswordDTO {
  oldPassword: string
  newPassword: string
}

export interface UpdateProfileDTO {
  realName?: string
  email?: string
  phone?: string
}

export const login = (data: LoginDTO): Promise<LoginVO> => {
  return request.post<LoginVO>('/api/auth/login', data)
}

export const getUserInfo = (): Promise<UserInfoVO> => {
  return request.get<UserInfoVO>('/api/auth/userinfo')
}

export const changePassword = (data: ChangePasswordDTO): Promise<void> => {
  return request.put<void>('/api/auth/password', data)
}

export const updateProfile = (data: UpdateProfileDTO): Promise<UserInfoVO> => {
  return request.put<UserInfoVO>('/api/auth/profile', data)
}
