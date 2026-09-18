import { request } from '@/utils/request'
import type { PageResult } from '@/api/monitor'

export interface SysUser {
  id: number
  username: string
  realName?: string
  phone?: string
  email?: string
  status: number
  roleCodes: string[]
  createTime?: string
}

export interface SysRole {
  id: number
  roleCode: string
  roleName: string
  permCodes: string[]
}

export interface Permission {
  id: number
  permCode: string
  permName: string
}

export interface SysConfig {
  id: number
  configKey: string
  configValue: string
  remark?: string
}

export interface OperationLog {
  id: number
  userId?: number
  module: string
  operation: string
  params?: string
  ip?: string
  result: number
  createTime: string
}

export function pageUsers(params: Record<string, unknown>) {
  return request<PageResult<SysUser>>({ url: '/users', params })
}

export function createUser(data: Record<string, unknown>) {
  return request<void>({ url: '/users', method: 'post', data })
}

export function updateUser(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/users/${id}`, method: 'put', data })
}

export function changeUserStatus(id: number, enabled: boolean) {
  return request<void>({ url: `/users/${id}/status`, method: 'patch', params: { enabled } })
}

export function listRoles() {
  return request<SysRole[]>({ url: '/roles' })
}

export function listPermissions() {
  return request<Permission[]>({ url: '/roles/permissions' })
}

export function createRole(data: Record<string, unknown>) {
  return request<void>({ url: '/roles', method: 'post', data })
}

export function updateRole(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/roles/${id}`, method: 'put', data })
}

export function pageConfigs(params: Record<string, unknown>) {
  return request<PageResult<SysConfig>>({ url: '/sys/configs', params })
}

export function updateConfig(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/sys/configs/${id}`, method: 'put', data })
}

export function pageOperationLogs(params: Record<string, unknown>) {
  return request<PageResult<OperationLog>>({ url: '/sys/logs/operations', params })
}