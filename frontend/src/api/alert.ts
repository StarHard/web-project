import { request } from '@/utils/request'
import type { PageResult } from '@/api/monitor'

export interface AlertRule {
  id: number
  stationId: number
  alertType: number
  element: string
  condition: number
  threshold: number
  durationMin?: number
  level: number
  upgradeLevel?: number
  channels: string
  status: number
}

export interface AlertRecord {
  id: number
  ruleId: number
  stationId: number
  level: number
  alertTime: string
  obsValue: number
  /** 规则判定的事实描述 */
  content: string
  /** AI 生成的处置建议，大模型不可用时为空 */
  aiContent?: string | null
  status: number
  relieveTime?: string
}

export interface AlertSubscribe {
  id: number
  userId: number
  stationId: number
  alertType: number
  channels: string
}

export function pageAlertRules(params: Record<string, unknown>) {
  return request<PageResult<AlertRule>>({ url: '/alert-rules', params })
}

export function createAlertRule(data: Record<string, unknown>) {
  return request<void>({ url: '/alert-rules', method: 'post', data })
}

export function updateAlertRule(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/alert-rules/${id}`, method: 'put', data })
}

export function deleteAlertRule(id: number) {
  return request<void>({ url: `/alert-rules/${id}`, method: 'delete' })
}

export function toggleAlertRule(id: number, enabled: boolean) {
  return request<void>({ url: `/alert-rules/${id}/status`, method: 'patch', params: { enabled } })
}

export function pageAlerts(params: Record<string, unknown>) {
  return request<PageResult<AlertRecord>>({ url: '/alerts', params })
}

export function alertStat(params: Record<string, unknown>) {
  return request<{
    total: number
    byLevel: Record<string, number>
    byStatus: Record<string, number>
    byStation: Record<string, number>
  }>({ url: '/alerts/stat', params })
}

export function relieveAlert(id: number) {
  return request<void>({ url: `/alerts/${id}/relieve`, method: 'patch' })
}

export function mySubscribes() {
  return request<AlertSubscribe[]>({ url: '/alert-subscribes' })
}

export function subscribeAlert(data: Record<string, unknown>) {
  return request<void>({ url: '/alert-subscribes', method: 'post', data })
}

export function unsubscribeAlert(stationId?: number, alertType?: number) {
  return request<void>({ url: '/alert-subscribes', method: 'delete', params: { stationId, alertType } })
}