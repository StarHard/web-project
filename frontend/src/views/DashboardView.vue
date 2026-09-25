<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import AppChart from '@/components/AppChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { realtimeCurve, pageStations, type ObsPoint, type Station } from '@/api/monitor'
import { pageAlerts, type AlertRecord } from '@/api/alert'
import {
  ALERT_LEVEL_COLORS,
  ALERT_LEVEL_LABELS,
  ELEMENT_UNITS,
  elementLabel,
  formatRelative,
  formatTime,
  round
} from '@/utils/format'

const stations = ref<Station[]>([])
const currentStation = ref<string>('')
const hours = ref(24)
const curveData = ref<ObsPoint[]>([])
const alerts = ref<AlertRecord[]>([])
const loading = ref(false)
const chartElement = ref('temp')
const autoRefresh = ref(true)

const ELEMENT_OPTIONS = ['temp', 'humi', 'pres', 'wind_speed', 'rain', 'rad']

const currentStationInfo = computed(() =>
  stations.value.find((item) => item.stationCode === currentStation.value)
)

/** 最新一条观测的要素快照 */
const latestElements = computed<Record<string, number>>(() => {
  if (curveData.value.length === 0) return {}
  return curveData.value[curveData.value.length - 1].elements ?? {}
})

const metrics = computed(() =>
  ['temp', 'humi', 'pres', 'wind_speed'].map((element) => {
    const values = curveData.value
      .map((point) => point.elements?.[element])
      .filter((value): value is number => typeof value === 'number')
    return {
      element,
      label: elementLabel(element),
      unit: ELEMENT_UNITS[element] ?? '',
      value: round(latestElements.value[element] ?? null, 1),
      min: values.length ? round(Math.min(...values), 1) : null,
      max: values.length ? round(Math.max(...values), 1) : null
    }
  })
)

const observedAt = computed(() => {
  const last = curveData.value[curveData.value.length - 1]
  return last ? formatTime(last.ts, true) : '--'
})

const curveOption = computed<EChartsOption>(() => {
  const points = curveData.value
  const times = points.map((point) => formatTime(point.ts))
  const values = points.map((point) => round(point.elements?.[chartElement.value] ?? null, 2))

  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', valueFormatter: (value) => `${value} ${ELEMENT_UNITS[chartElement.value] ?? ''}` },
    grid: { left: 48, right: 24, top: 22, bottom: 34 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: times,
      axisLine: { lineStyle: { color: '#292e36' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: ELEMENT_UNITS[chartElement.value] ?? '',
      nameTextStyle: { color: '#98a0ab' },
      splitLine: { lineStyle: { color: '#21252c' } },
      axisLabel: { color: '#98a0ab', fontSize: 11 }
    },
    series: [
      {
        name: elementLabel(chartElement.value),
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: values,
        lineStyle: { width: 2, color: '#6f9dc4' },
        areaStyle: {
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
})

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
  if (!currentStation.value && page.list.length > 0) {
    const online = page.list.find((item) => item.onlineFlag === 1)
    currentStation.value = (online ?? page.list[0]).stationCode
  }
}

async function loadData(): Promise<void> {
  if (!currentStation.value) return
  loading.value = true
  try {
    const [curve, alertPage] = await Promise.all([
      realtimeCurve(currentStation.value, hours.value),
      pageAlerts({ pageNum: 1, pageSize: 6, status: 0 })
    ])
    curveData.value = curve
    alerts.value = alertPage.list
  } finally {
    loading.value = false
  }
}

let timer: number | undefined

function setupTimer(): void {
  window.clearInterval(timer)
  if (autoRefresh.value) {
    timer = window.setInterval(loadData, 30_000)
  }
}

onMounted(async () => {
  await loadStations()
  await loadData()
  setupTimer()
})

onBeforeUnmount(() => window.clearInterval(timer))

watch(currentStation, loadData)
watch(hours, loadData)
watch(autoRefresh, setupTimer)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">实时监测</h1>
        <p class="page-subtitle">
          <span v-if="currentStationInfo">{{ currentStationInfo.name }} · </span>
          {{ currentStationInfo?.onlineFlag === 1 ? '在线' : '离线' }}
          <span v-if="currentStationInfo"> · 最后上报 {{ formatRelative(currentStationInfo.lastReportTime) }}</span>
          · 数据更新于 {{ observedAt }}
        </p>
      </div>
      <div class="row">
        <select v-model="currentStation" class="select" style="width: 190px">
          <option v-for="item in stations" :key="item.stationCode" :value="item.stationCode">
            {{ item.name }}（{{ item.stationCode }}）
          </option>
        </select>
        <select v-model.number="hours" class="select" style="width: 120px">
          <option :value="6">近 6 小时</option>
          <option :value="24">近 24 小时</option>
          <option :value="72">近 72 小时</option>
        </select>
        <button class="btn" :disabled="loading" @click="loadData">{{ loading ? '加载中…' : '刷新' }}</button>
        <label class="row" style="font-size: 13px; color: var(--text-muted)">
          <input v-model="autoRefresh" type="checkbox" />
          自动刷新
        </label>
      </div>
    </div>

    <div class="panel readout-strip">
      <div v-for="metric in metrics" :key="metric.element" class="readout">
        <div class="readout-label">
          {{ metric.label }}<span class="readout-unit">{{ metric.unit }}</span>
        </div>
        <div class="readout-value">{{ metric.value ?? '--' }}</div>
        <div class="readout-range">近 {{ hours }}h {{ metric.min ?? '--' }} ~ {{ metric.max ?? '--' }}</div>
      </div>
    </div>

    <div class="grid grid-2" style="margin-top: 16px">
      <div class="panel" style="grid-column: span 1">
        <div class="panel-head">
          <h3 class="panel-title">要素趋势</h3>
          <select v-model="chartElement" class="select" style="width: 130px">
            <option v-for="element in ELEMENT_OPTIONS" :key="element" :value="element">
              {{ elementLabel(element) }}
            </option>
          </select>
        </div>
        <AppChart v-if="curveData.length" :option="curveOption" height="330px" />
        <EmptyState
          v-else
          icon="inbox"
          title="暂无观测数据"
          hint="近 24 小时窗口内没有收到任何观测记录，站点上报后会自动出现在这里"
        />
      </div>

      <div class="panel">
        <div class="panel-head">
          <h3 class="panel-title">进行中告警</h3>
          <router-link to="/alert" class="btn btn-ghost btn-sm">查看全部</router-link>
        </div>
        <EmptyState
          v-if="alerts.length === 0"
          variant="positive"
          icon="check"
          title="当前无进行中告警"
          hint="有站点触发告警时会在此实时出现，无需刷新"
        />
        <ul v-else class="alert-list">
          <li v-for="item in alerts" :key="item.id">
            <span
              class="tag"
              :style="{
                color: ALERT_LEVEL_COLORS[item.level],
                background: `${ALERT_LEVEL_COLORS[item.level]}1f`,
                borderColor: `${ALERT_LEVEL_COLORS[item.level]}66`
              }"
            >
              {{ ALERT_LEVEL_LABELS[item.level] }}
            </span>
            <div class="alert-body">
              <div class="alert-content">{{ item.content }}</div>
              <div class="alert-time">{{ formatTime(item.alertTime, true) }}</div>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.alert-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.alert-list li {
  display: flex;
  gap: 12px;
  padding: 11px 2px;
  border-bottom: 1px solid var(--border);
}

.alert-list li:last-child {
  border-bottom: none;
}

.alert-body {
  min-width: 0;
}

.alert-content {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-time {
  font-size: 12px;
  color: var(--text-muted);
}
</style>