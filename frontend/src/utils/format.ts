/**
 * 展示层格式化：时间、要素单位与取值标签统一在此维护（AGENTS.md 4.4）
 */

/** 要素单位（展示层拼接） */
export const ELEMENT_UNITS: Record<string, string> = {
  temp: '℃',
  humi: '%',
  pres: 'hPa',
  wind_speed: 'm/s',
  wind_dir: '°',
  rain: 'mm',
  rad: 'W/m²',
  vis: 'km',
  evap: 'mm',
  pop: '%'
}

/** 要素中文名 */
export const ELEMENT_LABELS: Record<string, string> = {
  temp: '气温',
  humi: '湿度',
  pres: '气压',
  wind_speed: '风速',
  wind_dir: '风向',
  rain: '降水',
  rad: '辐射',
  vis: '能见度',
  evap: '蒸发',
  pop: '降水概率'
}

/** 告警等级：1蓝 2黄 3橙 4红 */
export const ALERT_LEVEL_LABELS: Record<number, string> = {
  1: '蓝色',
  2: '黄色',
  3: '橙色',
  4: '红色'
}

export const ALERT_LEVEL_COLORS: Record<number, string> = {
  1: '#2563eb',
  2: '#eab308',
  3: '#ea580c',
  4: '#dc2626'
}

/** 告警类型：1暴雨 2大风 3高温 4寒潮 5冰雹 */
export const ALERT_TYPE_LABELS: Record<number, string> = {
  1: '暴雨',
  2: '大风',
  3: '高温',
  4: '寒潮',
  5: '冰雹'
}

/** 质控任务状态：0待审核 1确认有效 2修正 3作废 */
export const QC_STATUS_LABELS: Record<number, string> = {
  0: '待审核',
  1: '确认有效',
  2: '已修正',
  3: '已作废'
}

/** 质控标记 */
export const QC_FLAG_LABELS: Record<string, string> = {
  raw: '原始',
  passed: '质控通过',
  suspect: '可疑',
  interpolated: '插补',
  revised: '人工修正'
}

export function elementLabel(element: string): string {
  return ELEMENT_LABELS[element] || element
}

/** 拼接单位后的要素展示值 */
export function formatElementValue(element: string, value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '--'
  }
  const unit = ELEMENT_UNITS[element] || ''
  return `${value}${unit}`
}

/** 时间字符串 → yyyy-MM-dd HH:mm */
export function formatTime(value?: string | number | Date | null, withSeconds = false): string {
  if (!value) {
    return '--'
  }
  const date = value instanceof Date ? value : new Date(typeof value === 'string' ? value.replace(/-/g, '/') : value)
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  const base = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  return withSeconds ? `${base}:${pad(date.getSeconds())}` : base
}

/** 相对时间：用于展示数据新鲜度 */
export function formatRelative(value?: string | null): string {
  if (!value) {
    return '--'
  }
  const date = new Date(value.replace(/-/g, '/'))
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  const diffSeconds = Math.floor((Date.now() - date.getTime()) / 1000)
  if (diffSeconds < 60) return '刚刚'
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)} 分钟前`
  if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)} 小时前`
  return `${Math.floor(diffSeconds / 86400)} 天前`
}

/** 数字保留位数，避免浮点尾差 */
export function round(value?: number | null, digits = 1): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return null
  }
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}