import { request } from '@/utils/request'
import type { PageResult } from '@/api/monitor'

export interface QcTask {
  id: number
  stationId: number
  element: string
  obsTime: string
  obsValue: number
  qcType: number
  qcDetail?: string
  status: number
  reviewedValue?: number
  reviewerId?: number
  reviewTime?: string
}

export function pageQcTasks(params: Record<string, unknown>) {
  return request<PageResult<QcTask>>({ url: '/qc-tasks', params })
}

/** 审核动作：confirm 确认有效 / revise 修正 / void 作废 */
export function reviewQcTask(id: number, action: 'confirm' | 'revise' | 'void', revisedValue?: number) {
  return request<void>({
    url: `/qc-tasks/${id}/review`,
    method: 'patch',
    data: { action, revisedValue }
  })
}