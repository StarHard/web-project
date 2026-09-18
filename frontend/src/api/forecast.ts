import { request } from '@/utils/request'

export interface ForecastPoint {
  time: string
  elements: Record<string, number>
}

export interface ForecastResp {
  stationCode: string
  model: string
  issueTime: string | null
  rangeHours: number
  points: ForecastPoint[]
}

export interface ComparePoint {
  time: string
  value: number | null
}

export interface CompareResp {
  stationCode: string
  element: string
  rangeHours: number
  models: Record<string, ComparePoint[]>
}

export function queryForecast(stationCode: string, model = 'stat', range = 72) {
  return request<ForecastResp>({ url: '/forecasts', params: { stationCode, model, range } })
}

export interface VerificationElementStat {
  element: string
  samples: number
  mae: number
  rmse: number
  bias: number
}

export interface VerificationRainStat {
  threshold: number
  samples: number
  hits: number
  misses: number
  falseAlarms: number
  ts: number | null
  pod: number | null
  far: number | null
}

export interface VerificationResp {
  stationCode: string
  model: string
  days: number
  generatedAt: string
  forecastSamples: number
  elements: VerificationElementStat[]
  rain: VerificationRainStat
}

export function verification(stationCode: string, model = 'stat', days = 7) {
  return request<VerificationResp>({
    url: '/forecasts/verification',
    params: { stationCode, model, days }
  })
}

export function generateForecast(stationCode?: string) {
  return request<{ total: number; success: number }>({
    url: '/forecasts/generate',
    method: 'post',
    params: stationCode ? { stationCode } : {}
  })
}

export function backtestForecast(stationCode?: string, days = 3) {
  return request<{ stations: number; issues: number; days: number }>({
    url: '/forecasts/backtest',
    method: 'post',
    params: { stationCode, days }
  })
}

export function compareModels(stationCode: string, element: string, range = 72) {
  return request<CompareResp>({
    url: '/forecasts/model-compare',
    params: { stationCode, element, range }
  })
}

/** 预报订正：targetEpochSecond 为预报目标时刻的 epoch 秒 */
export function reviseForecast(targetEpochSecond: number, data: Record<string, unknown>) {
  return request<number>({ url: `/forecasts/${targetEpochSecond}/revisions`, method: 'post', data })
}