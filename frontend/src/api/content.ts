import { request, downloadFile } from '@/utils/request'
import type { PageResult } from '@/api/monitor'

export interface ServiceArticle {
  id: number
  category: number
  title: string
  content?: string
  stationId?: number
  publishStatus: number
  publishTime?: string
  createTime?: string
}

export interface ReportFile {
  id: number
  stationId: number
  reportType: number
  periodStart: string
  periodEnd: string
  filePath: string
  createBy?: number
  createTime?: string
}

export function pageArticles(params: Record<string, unknown>) {
  return request<PageResult<ServiceArticle>>({ url: '/articles', params })
}

export function articleDetail(id: number) {
  return request<ServiceArticle>({ url: `/articles/${id}` })
}

export function createArticle(data: Record<string, unknown>) {
  return request<void>({ url: '/articles', method: 'post', data })
}

export function updateArticle(id: number, data: Record<string, unknown>) {
  return request<void>({ url: `/articles/${id}`, method: 'put', data })
}

export function changeArticlePublishStatus(id: number, publishStatus: number) {
  return request<void>({ url: `/articles/${id}/publish`, method: 'patch', params: { publishStatus } })
}

export function pageReports(params: Record<string, unknown>) {
  return request<PageResult<ReportFile>>({ url: '/reports', params })
}

export function generateReport(data: Record<string, unknown>) {
  return request<number>({ url: '/reports/generate', method: 'post', data })
}

export function downloadReport(id: number) {
  return downloadFile(`/reports/${id}/download`)
}