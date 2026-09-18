import { request } from '@/utils/request'

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  list: T[]
}

export interface Device {
  id: number
  stationId: number
  deviceCode: string
  deviceType: number
  model?: string
  manufacturer?: string
  installDate?: string
  status: number
}

export interface Station {
  id: number
  stationCode: string
  name: string
  province?: string
  city?: string
  district?: string
  longitude: number
  latitude: number
  altitude?: number
  stationType?: number
  status: number
  onlineFlag: number
  lastReportTime?: string
  commissionDate?: string
  devices?: Device[]
  latest?: { ts?: number; elements?: Record<string, number> } | null
}

export interface StationMapPoint {
  id: number
  stationCode: string
  name: string
  longitude: number
  latitude: number
  onlineFlag: number
  status: number
  alertLevel: number
}

export interface MaintenanceRecord {
  id: number
  stationId: number
  deviceId?: number
  type: number
  content: string
  operator: string
  maintDate: string
}

export function pageStations(params: Record<string, unknown>) {
  return request<PageResult<Station>>({ url: '/stations', params })
}

export function stationDetail(id: number) {
  return request<Station>({ url: `/stations/${id}` })
}

export function stationMap() {
  return request<StationMapPoint[]>({ url: '/stations/map' })
}

export function createStation(data: Record<string, unknown>) {
  return request<void>({ url: '/stations', method: 'post', data })
}

export function updateStation(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/stations/${id}`, method: 'put', data })
}

export function deleteStation(id: number) {
  return request<void>({ url: `/stations/${id}`, method: 'delete' })
}

export function listDevices(stationId?: number) {
  return request<Device[]>({ url: '/devices', params: { stationId } })
}

export function createDevice(data: Record<string, unknown>) {
  return request<void>({ url: '/devices', method: 'post', data })
}

export function updateDevice(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/devices/${id}`, method: 'put', data })
}

export function deleteDevice(id: number) {
  return request<void>({ url: `/devices/${id}`, method: 'delete' })
}

export function pageMaintenance(params: Record<string, unknown>) {
  return request<PageResult<MaintenanceRecord>>({ url: '/maintenance-records', params })
}

export function createMaintenance(data: Record<string, unknown>) {
  return request<void>({ url: '/maintenance-records', method: 'post', data })
}

/** 实时监测 */
export function latestRealtime(stationCode: string) {
  return request<{ source: string; payload?: string; ts?: string; elements?: Record<string, number> }>({
    url: '/realtime/latest',
    params: { stationCode }
  })
}

export interface ObsPoint {
  stationCode: string
  ts: string
  elements: Record<string, number>
  qcFlag?: string
}

export function realtimeCurve(stationCode: string, hours = 24) {
  return request<ObsPoint[]>({ url: '/realtime/curve', params: { stationCode, hours } })
}