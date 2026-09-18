import { request, downloadFile } from '@/utils/request'
import type { PageResult } from '@/api/monitor'

export interface SeriesPoint {
  time: string
  value: number | null
  qcFlag?: string
}

export interface HistoryResp {
  stationId: number
  stationCode: string
  granularity: string
  series: Record<string, SeriesPoint[]>
}

export interface ElementStat {
  count: number
  avg: number | null
  max: number | null
  maxTime?: string
  min: number | null
  minTime?: string
  sum: number | null
}

export interface StatsResp {
  stationId: number
  stationCode: string
  period: string
  startTime: string
  endTime: string
  stats: Record<string, ElementStat>
  climateSeries?: Array<{ year: number; avg: number; count: number }>
}

export function queryHistory(params: Record<string, unknown>) {
  return request<HistoryResp>({ url: '/history', params })
}

export function statsDaily(stationId: number, date?: string) {
  return request<StatsResp>({ url: '/stats/daily', params: { stationId, date } })
}

export function statsMonthly(stationId: number, month?: string) {
  return request<StatsResp>({ url: '/stats/monthly', params: { stationId, month } })
}

export function statsYearly(stationId: number, year?: string) {
  return request<StatsResp>({ url: '/stats/yearly', params: { stationId, year } })
}

export function statsExtreme(stationId: number, element: string, startTime?: string, endTime?: string) {
  return request<StatsResp>({ url: '/stats/extreme', params: { stationId, element, startTime, endTime } })
}

export function statsClimate(stationId: number, month: number, element: string) {
  return request<StatsResp>({ url: '/stats/climate', params: { stationId, month, element } })
}

export interface ExportTask {
  taskId: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  fileName?: string
  downloadUrl?: string
  message?: string
  createTime?: string
}

/** 提交异步导出任务 */
export function submitExport(params: Record<string, unknown>) {
  return request<string>({ url: '/export', params })
}

export function exportTaskStatus(taskId: string) {
  return request<ExportTask>({ url: `/export/tasks/${taskId}` })
}

export function downloadExport(taskId: string) {
  return downloadFile(`/export/tasks/${taskId}/download`)
}

export type { PageResult }