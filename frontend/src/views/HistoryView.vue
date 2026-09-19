<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import AppChart from '@/components/AppChart.vue'
import { pageStations, type Station } from '@/api/monitor'
import { queryHistory, submitExport, exportTaskStatus, downloadExport, type HistoryResp } from '@/api/data'
import { ELEMENT_UNITS, elementLabel, formatElementValue, round } from '@/utils/format'
import { useFilters } from '@/utils/filters'
import { toastError, toastSuccess } from '@/utils/toast'

const ALL_ELEMENTS = ['temp', 'humi', 'pres', 'wind_speed', 'rain', 'rad', 'vis']

const stations = ref<Station[]>([])
const { filters, resetFilters } = useFilters<{
  stationId: number | null
  granularity: string
  startTime: string
  endTime: string
}>('history', {
  stationId: null,
  granularity: 'hour',
  startTime: toInputValue(Date.now() - 3 * 86400_000),
  endTime: toInputValue(Date.now())
})
const selectedElements = ref<string[]>(['temp', 'rain'])
const loading = ref(false)
const result = ref<HistoryResp | null>(null)
const exportStatus = ref('')

function toInputValue(timestamp: number): string {
  const date = new Date(timestamp)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toApiTime(value: string): string {
  return value.replace('T', ' ') + (value.length === 16 ? ':00' : '')
}

/** 每要素独立成图，避免不同量纲混绘 */
const charts = computed(() => {
  if (!result.value) return []
  return Object.entries(result.value.series)
    .filter(([element]) => selectedElements.value.includes(element))
    .map(([element, points]) => ({
      element,
      label: elementLabel(element),
      unit: ELEMENT_UNITS[element] ?? '',
      option: buildOption(element, points)
    }))
})

/** 各要素汇总（均值/极值），由曲线点位在前端计算，省去额外请求 */
const summaries = computed(() => {
  if (!result.value) return []
  return Object.entries(result.value.series)
    .filter(([element]) => selectedElements.value.includes(element))
    .map(([element, points]) => {
      const values = points.map((point) => point.value).filter((value): value is number => value !== null)
      if (values.length === 0) {
        return { element, label: elementLabel(element), count: 0, avg: null, max: null, min: null }
      }
      return {
        element,
        label: elementLabel(element),
        count: values.length,
        avg: round(values.reduce((sum, value) => sum + value, 0) / values.length, 2),
        max: round(Math.max(...values), 2),
        min: round(Math.min(...values), 2)
      }
    })
})

function buildOption(element: string, points: { time: string; value: number | null }[]): EChartsOption {
  const isRain = element === 'rain'
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 52, right: 24, top: 20, bottom: 46 },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 8, borderColor: '#292e36' }],
    xAxis: {
      type: 'category',
      boundaryGap: isRain,
      data: points.map((point) => point.time),
      axisLine: { lineStyle: { color: '#292e36' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: ELEMENT_UNITS[element] ?? '',
      nameTextStyle: { color: '#98a0ab' },
      splitLine: { lineStyle: { color: '#21252c' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    series: [
      {
        name: elementLabel(element),
        type: isRain ? 'bar' : 'line',
        smooth: !isRain,
        showSymbol: false,
        barMaxWidth: 14,
        data: points.map((point) => point.value),
        lineStyle: { width: 2, color: '#6f9dc4' },
        itemStyle: { color: '#4a7ea8', borderRadius: [3, 3, 0, 0] },
        areaStyle: isRain
          ? undefined
          : {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(111,157,196,0.28)' },
                  { offset: 1, color: 'rgba(111,157,196,0.02)' }
                ]
              }
            }
      }
    ]
  }
}

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
  if (!filters.value.stationId && page.list.length > 0) {
    filters.value.stationId = page.list[0].id
  }
}

async function search(): Promise<void> {
  if (!filters.value.stationId) {
    toastError('请先选择站点')
    return
  }
  if (selectedElements.value.length === 0) {
    toastError('请至少选择一个要素')
    return
  }
  loading.value = true
  try {
    result.value = await queryHistory({
      stationId: filters.value.stationId,
      elements: selectedElements.value.join(','),
      startTime: toApiTime(filters.value.startTime),
      endTime: toApiTime(filters.value.endTime),
      granularity: filters.value.granularity
    })
  } finally {
    loading.value = false
  }
}

/** 重置筛选条件后重新查询：站点回落到默认站点，时间范围回到默认区间 */
async function handleReset(): Promise<void> {
  resetFilters()
  await loadStations()
  search()
}

/** 异步导出：提交任务后轮询状态，完成后自动下载 */
async function handleExport(format: 'csv' | 'txt'): Promise<void> {
  if (!filters.value.stationId) {
    toastError('请先选择站点')
    return
  }
  exportStatus.value = '正在提交导出任务…'
  try {
    const taskId = await submitExport({
      stationId: filters.value.stationId,
      elements: selectedElements.value.join(','),
      startTime: toApiTime(filters.value.startTime),
      endTime: toApiTime(filters.value.endTime),
      format
    })
    for (let attempt = 0; attempt < 20; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      const task = await exportTaskStatus(taskId)
      if (task.status === 'SUCCESS') {
        exportStatus.value = ''
        await downloadExport(taskId)
        toastSuccess('导出完成，文件已开始下载')
        return
      }
      if (task.status === 'FAILED') {
        exportStatus.value = ''
        toastError(task.message || '导出失败')
        return
      }
      exportStatus.value = `导出中…（${attempt + 1}s）`
    }
    exportStatus.value = ''
    toastError('导出超时，请稍后在报表页重试')
  } catch {
    exportStatus.value = ''
  }
}

function toggleElement(element: string): void {
  const index = selectedElements.value.indexOf(element)
  if (index >= 0) {
    selectedElements.value.splice(index, 1)
  } else {
    selectedElements.value.push(element)
  }
}

onMounted(async () => {
  await loadStations()
  await search()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">历史数据</h1>
        <p class="page-subtitle">时序数据查询、统计与导出（数据源 InfluxDB）</p>
      </div>
      <div class="row">
        <button class="btn" :disabled="!!exportStatus" @click="handleExport('csv')">导出 CSV</button>
        <button class="btn" :disabled="!!exportStatus" @click="handleExport('txt')">导出 TXT</button>
        <span v-if="exportStatus" style="font-size: 12px; color: var(--text-muted)">{{ exportStatus }}</span>
      </div>
    </div>

    <div class="panel" style="margin-bottom: 16px">
      <div class="filters">
        <label class="field">
          <span class="field-label">站点</span>
          <select v-model.number="filters.stationId" class="select">
            <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>

        <label class="field">
          <span class="field-label">开始时间</span>
          <input v-model="filters.startTime" type="datetime-local" class="input" />
        </label>

        <label class="field">
          <span class="field-label">结束时间</span>
          <input v-model="filters.endTime" type="datetime-local" class="input" />
        </label>

        <label class="field">
          <span class="field-label">聚合粒度</span>
          <select v-model="filters.granularity" class="select">
            <option value="min">原始（分钟）</option>
            <option value="hour">小时</option>
            <option value="day">日</option>
          </select>
        </label>

        <button class="btn" @click="handleReset">重置</button>
        <button class="btn btn-primary" :disabled="loading" @click="search">
          {{ loading ? '查询中…' : '查询' }}
        </button>
      </div>

      <div class="elements">
        <span class="field-label">要素</span>
        <button
          v-for="element in ALL_ELEMENTS"
          :key="element"
          class="chip"
          :class="{ active: selectedElements.includes(element) }"
          @click="toggleElement(element)"
        >
          {{ elementLabel(element) }}
        </button>
      </div>
    </div>

    <div v-if="summaries.length" class="grid grid-4" style="margin-bottom: 16px">
      <div v-for="item in summaries" :key="item.element" class="panel metric">
        <div class="metric-label">{{ item.label }}（{{ item.count }} 个样本）</div>
        <div class="metric-row">
          <span>均 {{ formatElementValue(item.element, item.avg) }}</span>
          <span>高 {{ formatElementValue(item.element, item.max) }}</span>
          <span>低 {{ formatElementValue(item.element, item.min) }}</span>
        </div>
      </div>
    </div>

    <div v-if="charts.length" class="grid grid-2">
      <div v-for="chart in charts" :key="chart.element" class="panel">
        <div class="panel-head">
          <h3 class="panel-title">{{ chart.label }} 曲线</h3>
          <span class="tag tag-muted">{{ chart.unit }}</span>
        </div>
        <AppChart :option="chart.option" height="260px" />
      </div>
    </div>

    <div v-else-if="result" class="panel state">该时段与粒度下无数据，请调整查询条件</div>
  </div>
</template>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
  align-items: end;
}

.elements {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.chip {
  padding: 5px 14px;
  border-radius: 20px;
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

.metric-row {
  display: flex;
  gap: 14px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
  flex-wrap: wrap;
}
</style>