import { request } from '@/utils/request'

export interface LoginReq {
  username: string
  password: string
}

export interface UserInfo {
  id: number
  username: string
  realName?: string
  roles: string[]
}

export interface LoginResp {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: UserInfo
}

export function login(data: LoginReq) {
  return request<LoginResp>({ url: '/auth/login', method: 'post', data })
}

export function refreshToken(token: string) {
  return request<LoginResp>({ url: '/auth/refresh', method: 'post', data: { refreshToken: token } })
}

export function logout() {
  return request<void>({ url: '/auth/logout', method: 'post' })
}

export function fetchProfile() {
  return request<{ id: number; username: string; roles: string[] }>({ url: '/auth/profile' })
}