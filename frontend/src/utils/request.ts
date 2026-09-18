import axios, { type AxiosRequestConfig, type AxiosInstance } from 'axios'
import { toastError } from '@/utils/toast'

export const ACCESS_TOKEN_KEY = 'meteo_access_token'
export const REFRESH_TOKEN_KEY = 'meteo_refresh_token'

/** 后端统一返回结构（见 RESTful API 规范 1.2） */
interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

const ERROR_CODE_UNAUTHORIZED = 40101

const service: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 20000
})

service.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    // 二进制下载（报表/导出）直接返回原始响应，跳过业务码解析
    if (response.config.responseType === 'blob') {
      return response
    }
    const body = response.data as ApiResult<unknown>
    if (body.code === 0) {
      // 拦截器统一剥离 Result 包装，返回值由 request<T> 承担类型
      return body.data as unknown as typeof response
    }
    if (body.code === ERROR_CODE_UNAUTHORIZED) {
      redirectToLogin()
    } else {
      toastError(body.message || '请求失败')
    }
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data as ApiResult<unknown> | undefined
    if (status === 401 || body?.code === ERROR_CODE_UNAUTHORIZED) {
      redirectToLogin()
    } else if (status === 403) {
      toastError('无访问权限')
    } else {
      toastError(body?.message || error.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

let redirecting = false
function redirectToLogin(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  if (!redirecting) {
    redirecting = true
    toastError('登录状态已失效，请重新登录')
    setTimeout(() => {
      redirecting = false
      window.location.href = '/login'
    }, 600)
  }
}

/** 统一请求入口：返回值已剥离 Result 包装，直接拿到业务数据 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request(config) as unknown as Promise<T>
}

/** 下载接口：返回 Blob 与文件名 */
export async function downloadFile(url: string, params?: Record<string, unknown>): Promise<void> {
  const response = await service.get(url, { params, responseType: 'blob' })
  const disposition = (response.headers['content-disposition'] as string) || ''
  const matched = /filename="?([^";]+)"?/.exec(disposition)
  const fileName = matched ? matched[1] : 'download'

  const blobUrl = window.URL.createObjectURL(response.data as Blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(blobUrl)
}

export default service