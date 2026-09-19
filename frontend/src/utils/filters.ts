import { ref, watch, type Ref } from 'vue'

/** 筛选条件取值：仅支持可 JSON 序列化的简单类型 */
export type FilterValue = string | number | boolean | undefined | null
export type FilterState = Record<string, FilterValue>

const STORAGE_PREFIX = 'meteo:filters:'

function readStored(scope: string): FilterState {
  try {
    const raw = sessionStorage.getItem(STORAGE_PREFIX + scope)
    return raw ? (JSON.parse(raw) as FilterState) : {}
  } catch {
    return {}
  }
}

function writeStored(scope: string, state: FilterState): void {
  try {
    sessionStorage.setItem(STORAGE_PREFIX + scope, JSON.stringify(state))
  } catch {
    // 存储不可用（隐私模式、配额溢出）时静默降级为不持久化，不影响查询本身
  }
}

/**
 * 页面筛选条件：以 defaults 的键为准与 sessionStorage 缓存合并，变更即写回。
 *
 * - 缓存按 scope 隔离，切换页面或刷新浏览器后条件不丢
 * - undefined 表示「全部」，JSON 序列化会丢掉该键，恢复时自然落回默认值，语义一致
 * - 只恢复 defaults 中声明过的键，避免旧版本残留字段污染
 *
 * 返回值保持 Ref 形态，调用方原有的 filters.value.xxx 写法无需改动。
 */
export function useFilters<T extends FilterState>(
  scope: string,
  defaults: T
): { filters: Ref<T>; resetFilters: () => void } {
  const stored = readStored(scope)
  const initial: FilterState = { ...defaults }
  for (const key of Object.keys(defaults)) {
    if (key in stored) {
      initial[key] = stored[key]
    }
  }

  const filters = ref(initial) as unknown as Ref<T>

  watch(filters, (value) => writeStored(scope, { ...value }), { deep: true })

  /** 回到默认值并清空缓存；调用方通常还需重置分页页码后重新查询 */
  function resetFilters(): void {
    filters.value = { ...defaults }
  }

  return { filters, resetFilters }
}