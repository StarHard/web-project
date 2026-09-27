/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  /** 高德地图 JS API Key（未配置时地图页面降级为点位列表） */
  readonly VITE_AMAP_KEY?: string
  /** 高德地图 JS API 安全密钥（2021-12 之后申请的 Key 必须配套） */
  readonly VITE_AMAP_SECURITY_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}