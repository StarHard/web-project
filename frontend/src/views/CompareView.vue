<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import AppChart from '@/components/AppChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { SERIES_COLORS, sparseSymbolStyle } from '@/utils/chart'
import { pageStations, realtimeCompare, type CompareResp, type Station } from '@/api/monitor'
import { ELEMENT_UNITS, elementLabel, formatElementValue, formatTime } from '@/utils/format'
import { useFilters } from '@/utils/filters'
import { toastError } from '@/utils/toast'

const ELEMENT_OPTIONS = ['temp', 'humi', 'pres', 'wind_speed', 'rain', 'rad']

/** 单次对比站点上限，与后端 RealtimeServiceImpl.MAX_STATIONS 保持一致 */
const MAX_STATIONS = 6

const stations = ref<Station[]>([])
const result = ref<CompareResp | null>(null)
const loading = ref(false)

/**
 * 选中站点以逗号串持久化：useFilters 只支持简单类型，数组无法直接序列化。
 */
const { filters, resetFilters } = useFilters<{
  stationIds: string
  element: string
  hours: number
}>('compare', {
  stationIds: '',
  element: 'temp',
  hours: 24
})

const selectedIds = computed<number[]>(() =>
  filters.value.stationIds
    .split(',')
    .map((item) => Number(item))
    .filter((id) => Number.isInteger(id) && id > 0)
)

const hasData = computed(() => (result.value?.times.length ?? 0) > 0)

/** 空态分型：没选站点是操作缺失，选了站点无数据是筛选过窄，两者该引导的方向不同 */
const emptyState = computed(() => {
  if (selectedIds.value.length === 0) {
    return {
      icon: 'search',
      title: '请先选择对比站点',
      hint: `最多可选 ${MAX_STATIONS} 个站点，时间跨度不超过 7 天`
    }
  }
  if (!result.value) {
    return { icon: 'inbox', title: '暂无数据', hint: '站点上报观测后即可在此对比' }
  }
  return {
    icon: 'search',
    title: '所选时段内无该要素数据',
    hint: '可放宽时间范围或更换要素后重新查询'
  }
})

const chartOption = computed<EChartsOption>(() => {
  const data = result.value
  if (!data) return {}
  const unit = ELEMENT_UNITS[data.element] ?? ''
  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => (value === null || value === undefined ? '缺测' : `${value} ${unit}`)
    },
    legend: {
      top: 0,
      textStyle: { color: '#98a0ab', fontSize: 12 },
      inactiveColor: '#5c646f'
    },
    // containLabel：让栅格让出轴标签所需空间，否则末位长日期标签会有一半落到画布外
    grid: { left: 54, right: 24, top: 40, bottom: 48, containLabel: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 8, borderColor: '#292e36' }],
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.times,
      axisLine: { lineStyle: { color: '#292e36' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: unit,
      nameTextStyle: { color: '#98a0ab' },
      splitLine: { lineStyle: { color: '#21252c' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    series: data.series.map((item, index) => {
      const color = SERIES_COLORS[index % SERIES_COLORS.length]
      return {
        name: item.stationName,
        type: 'line' as const,
        smooth: true,
        // 计数排除缺测补位：时间轴并集对齐后补的 null 不是真实数据点
        ...sparseSymbolStyle(item.values.filter((value) => value !== null).length),
        // 缺测处断开而非连线：跨站时间轴对齐后补的 null 不代表真实缺测时刻之间的连续变化
        connectNulls: false,
        data: item.values,
        lineStyle: { width: 2, color },
        itemStyle: { color }
      }
    })
  }
})

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
}

/** 默认选前两个站点（优先在线），保证进入页面即有对比曲线 */
function applyDefaultStations(): void {
  const online = stations.value.filter((item) => item.onlineFlag === 1)
  const picks = (online.length >= 2 ? online : stations.value).slice(0, 2)
  filters.value.stationIds = picks.map((item) => item.id).join(',')
}

function toggleStation(id: number): void {
  const ids = selectedIds.value.filter((item) => item !== id)
  if (ids.length === selectedIds.value.length) {
    if (ids.length >= MAX_STATIONS) {
      toastError(`单次最多对比 ${MAX_STATIONS} 个站点`)
      return
    }
    ids.push(id)
  }
  filters.value.stationIds = ids.join(',')
  search()
}

async function search(): Promise<void> {
  if (selectedIds.value.length === 0) {
    result.value = null
    return
  }
  loading.value = true
  try {
    // 后端按 yyyy-MM-dd HH:mm:ss 解析，与历史数据页的格式约定一致
    const now = new Date()
    const start = new Date(now.getTime() - filters.value.hours * 3600_000)
    result.value = await realtimeCompare(
      selectedIds.value,
      filters.value.element,
      formatTime(start, true),
      formatTime(now, true)
    )
  } finally {
    loading.value = false
  }
}

async function handleReset(): Promise<void> {
  resetFilters()
  applyDefaultStations()
  await search()
}

onMounted(async () => {
  await loadStations()
  if (selectedIds.value.length === 0) {
    applyDefaultStations()
  }
  await search()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">多站对比</h1>
        <p class="page-subtitle">
          同要素多站点并列曲线
          <span v-if="result"> · {{ result.startTime }} ~ {{ result.endTime }}</span>
        </p>
      </div>
      <div class="row">
        <select v-model="filters.element" class="select" style="width: 130px" @change="search">
          <option v-for="element in ELEMENT_OPTIONS" :key="element" :value="element">
            {{ elementLabel(element) }}
          </option>
        </select>
        <select v-model.number="filters.hours" class="select" style="width: 120px" @change="search">
          <option :value="6">近 6 小时</option>
          <option :value="24">近 24 小时</option>
          <option :value="72">近 72 小时</option>
        </select>
        <button class="btn" :disabled="loading" @click="search">{{ loading ? '加载中…' : '刷新' }}</button>
        <button class="btn btn-ghost" @click="handleReset">重置</button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h3 class="panel-title">对比站点</h3>
        <span class="panel-note">已选 {{ selectedIds.length }} / {{ MAX_STATIONS }}</span>
      </div>
      <EmptyState
        v-if="stations.length === 0"
        icon="station"
        title="暂无站点数据"
        hint="站点设备配置完成后即可在此选择对比站点"
      />
      <div v-else class="station-picker">
        <button
          v-for="item in stations"
          :key="item.id"
          class="chip"
          :class="{ active: selectedIds.includes(item.id) }"
          @click="toggleStation(item.id)"
        >
          <span class="dot" :class="item.onlineFlag === 1 ? 'dot-online' : 'dot-offline'"></span>
          {{ item.name }}
          <em>{{ item.stationCode }}</em>
        </button>
      </div>
    </div>

    <div class="panel" style="margin-top: 16px">
      <div class="panel-head">
        <h3 class="panel-title">{{ elementLabel(filters.element) }}趋势对比</h3>
        <span class="panel-note">{{ hasData ? `${result?.times.length ?? 0} 个时刻` : '' }}</span>
      </div>
      <AppChart v-if="hasData" :option="chartOption" height="360px" />
      <EmptyState v-else :icon="emptyState.icon" :title="emptyState.title" :hint="emptyState.hint" />
    </div>

    <div class="panel" style="margin-top: 16px">
      <div class="panel-head">
        <h3 class="panel-title">统计对比</h3>
        <span class="panel-note">{{ ELEMENT_UNITS[filters.element] ?? '' }}</span>
      </div>
      <div v-if="hasData" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>站点</th>
              <th>最新</th>
              <th>均值</th>
              <th>最小</th>
              <th>最大</th>
              <th>有效样本</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in result?.series ?? []" :key="item.stationCode">
              <td>
                <span
                  class="legend-dot"
                  :style="{ background: SERIES_COLORS[index % SERIES_COLORS.length] }"
                ></span>
                {{ item.stationName }}
                <em class="cell-note">{{ item.stationCode }}</em>
              </td>
              <td>{{ formatElementValue(filters.element, item.latest) }}</td>
              <td>{{ formatElementValue(filters.element, item.avg) }}</td>
              <td>{{ formatElementValue(filters.element, item.min) }}</td>
              <td>{{ formatElementValue(filters.element, item.max) }}</td>
              <td>{{ item.count }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else :icon="emptyState.icon" :title="emptyState.title" :hint="emptyState.hint" />
    </div>
  </div>
</template>

<style scoped>
.panel-note {
  font-size: 12px;
  color: var(--text-dim);
  font-variant-numeric: tabular-nums;
}

.station-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border-radius: var(--radius-pill);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.chip:hover {
  color: var(--text);
  border-color: var(--border-light);
}

.chip.active {
  background: rgba(74, 126, 168, 0.18);
  border-color: var(--primary);
  color: var(--accent);
}

.chip em {
  font-style: normal;
  color: var(--text-dim);
  font-size: 11px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot-online {
  background: var(--success);
}

.dot-offline {
  background: var(--text-dim);
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 7px;
  vertical-align: middle;
}

.cell-note {
  font-style: normal;
  margin-left: 6px;
  color: var(--text-dim);
  font-size: 12px;
}
</style>
