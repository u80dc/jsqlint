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

export const login = (data: LoginDTO): Promise<LoginVO> => {
  return request.post<LoginVO>('/api/auth/login', data)
}

export const getUserInfo = (): Promise<LoginVO> => {
  return request.get<LoginVO>('/api/auth/userinfo')
}
