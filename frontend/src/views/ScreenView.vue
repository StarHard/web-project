<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { BorderBox1, Decoration5, DigitalFlop, ScrollBoard } from '@kjgl77/datav-vue3'
import '@kjgl77/datav-vue3/dist/style.css'
import AppChart from '@/components/AppChart.vue'
import { pageStations, realtimeCurve, stationMap, type Station, type StationMapPoint } from '@/api/monitor'
import { alertStat, pageAlerts, type AlertRecord } from '@/api/alert'
import {
  ALERT_LEVEL_COLORS,
  ALERT_LEVEL_LABELS,
  elementLabel,
  formatTime,
  round
} from '@/utils/format'

const router = useRouter()

const stations = ref<Station[]>([])
const mapPoints = ref<StationMapPoint[]>([])
const alerts = ref<AlertRecord[]>([])
const levelCount = ref<Record<string, number>>({})
/** 站点编码 → 近 24 小时观测序列 */
const curves = ref<Record<string, Array<{ ts: string; elements: Record<string, number> }>>>({})
const clock = ref(formatTime(new Date(), true))

/** 大屏基准分辨率：按屏幕尺寸等比缩放，保证投影/展厅下比例不失真 */
const CANVAS_WIDTH = 1920
const CANVAS_HEIGHT = 1080
const scale = ref(1)

function updateScale(): void {
  scale.value = Math.min(window.innerWidth / CANVAS_WIDTH, window.innerHeight / CANVAS_HEIGHT)
}

/** DataV 边框配色常量，避免内联数组在每次渲染时重建 */
const COLOR_CYAN = ['#22d3ee', '#3b82f6']
const COLOR_WARN = ['#f59e0b', '#ef4444']

/** 大屏为固定画布（1080 高），面板高度按可用空间算好，避免 flex 撑不开导致 DataV 组件测到 0 高度 */
const PANEL_HEIGHT = {
  metrics: 340,
  stations: 594,
  trend: 467,
  rain: 467,
  alerts: 534,
  levels: 400
} as const

const REFRESH_MS = 60_000

/** 全网平均要素值 */
const averages = computed(() => {
  const bucket: Record<string, number[]> = {}
  Object.values(curves.value).forEach((points) => {
    const last = points[points.length - 1]
    if (!last) return
    Object.entries(last.elements ?? {}).forEach(([element, value]) => {
      if (typeof value === 'number') {
        ;(bucket[element] ??= []).push(value)
      }
    })
  })
  const result: Record<string, number | null> = {}
  Object.entries(bucket).forEach(([element, values]) => {
    result[element] = values.length ? round(values.reduce((a, b) => a + b, 0) / values.length, 1) : null
  })
  return result
})

/** 各站点近 24h 累计降水 */
const rainRanking = computed(() =>
  stations.value
    .map((station) => {
      const points = curves.value[station.stationCode] ?? []
      const total = points.reduce((sum, point) => sum + (point.elements?.rain ?? 0), 0)
      return { name: station.name, value: round(total, 1) ?? 0 }
    })
    .sort((a, b) => b.value - a.value)
)

const onlineCount = computed(() => mapPoints.value.filter((item) => item.onlineFlag === 1).length)
const alertingCount = computed(() => mapPoints.value.filter((item) => item.alertLevel > 0).length)

const trendOption = computed<EChartsOption>(() => {
  const palette = ['#22d3ee', '#a78bfa', '#f59e0b', '#34d399']
  const series = Object.entries(curves.value).map(([stationCode, points], index) => ({
    name: stations.value.find((item) => item.stationCode === stationCode)?.name ?? stationCode,
    type: 'line' as const,
    smooth: true,
    showSymbol: false,
    data: points.map((point) => [point.ts, point.elements?.temp ?? null]),
    lineStyle: { width: 2, color: palette[index % palette.length] },
    itemStyle: { color: palette[index % palette.length] }
  }))
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: '#8ba0bf' }, top: 0, icon: 'roundRect' },
    grid: { left: 52, right: 24, top: 40, bottom: 40 },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: '#1f2f4a' } },
      axisLabel: { color: '#8ba0bf', fontSize: 11 },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: '℃',
      nameTextStyle: { color: '#8ba0bf' },
      splitLine: { lineStyle: { color: '#152238' } },
      axisLabel: { color: '#8ba0bf', fontSize: 11 }
    },
    series
  }
})

const rainOption = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: { trigger: 'axis' },
  grid: { left: 52, right: 24, top: 24, bottom: 46 },
  xAxis: {
    type: 'category',
    data: rainRanking.value.map((item) => item.name),
    axisLine: { lineStyle: { color: '#1f2f4a' } },
    axisLabel: { color: '#8ba0bf', fontSize: 11, interval: 0, rotate: 12 }
  },
  yAxis: {
    type: 'value',
    name: 'mm',
    nameTextStyle: { color: '#8ba0bf' },
    splitLine: { lineStyle: { color: '#152238' } },
    axisLabel: { color: '#8ba0bf', fontSize: 11 }
  },
  series: [
    {
      type: 'bar',
      barMaxWidth: 28,
      data: rainRanking.value.map((item) => item.value),
      itemStyle: {
        borderRadius: [4, 4, 0, 0],
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(34,211,238,0.9)' },
            { offset: 1, color: 'rgba(34,211,238,0.15)' }
          ]
        }
      }
    }
  ]
}))

const levelOption = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: { trigger: 'item' },
  series: [
    {
      type: 'pie',
      radius: ['48%', '70%'],
      center: ['50%', '52%'],
      label: { color: '#8ba0bf', fontSize: 12, formatter: '{b}\n{c}' },
      itemStyle: { borderColor: '#06101f', borderWidth: 2 },
      data: [1, 2, 3, 4].map((level) => ({
        name: ALERT_LEVEL_LABELS[level],
        value: levelCount.value[String(level)] ?? 0,
        itemStyle: { color: ALERT_LEVEL_COLORS[level] }
      }))
    }
  ]
}))

function flopConfig(value: number | null, unit: string, color: string) {
  return {
    number: [value ?? 0],
    content: `{nt}${unit}`,
    toFixed: 1,
    style: { fontSize: 34, fill: color, fontFamily: 'Inter, PingFang SC, sans-serif' }
  }
}

/**
 * 大屏榜单列宽有限，把告警内容压缩为「类型 + 要素 + 触发值 > 阈值」。
 * 原始内容形如「大风蓝色预警: CAMPUS01站 wind_speed=10.3 超过阈值 10.0」，
 * 其中站点与等级已由榜单其它列承载，重复展示只会挤掉真正有效的信息。
 */
function alertSummary(content: string): string {
  const hit = /([a-z_]+)=([\d.-]+)\s*超过阈值\s*([\d.-]+)/.exec(content)
  if (!hit) {
    return content.length > 18 ? `${content.slice(0, 18)}…` : content
  }
  const type = /^(.{2,4}?)预警/.exec(content)?.[1] ?? ''
  return `${type} ${elementLabel(hit[1])} ${hit[2]} > ${hit[3]}`.trim()
}

const alertBoardConfig = computed(() => ({
  header: ['站点', '等级', '告警内容', '时间'],
  data: alerts.value.map((item) => [
    stationName(item.stationId),
    ALERT_LEVEL_LABELS[item.level] ?? '--',
    alertSummary(item.content),
    // 榜单列宽有限，仅保留 HH:mm —— 大屏关注的是当日进行中的告警
    formatTime(item.alertTime).slice(-5)
  ]),
  rowNum: 6,
  headerBGC: 'rgba(34,211,238,0.12)',
  oddRowBGC: 'rgba(255,255,255,0.02)',
  evenRowBGC: 'transparent',
  headerHeight: 38,
  columnWidth: [120, 50, 195, 60],
  align: ['left', 'center', 'left', 'center'],
  waitTime: 3000
}))

function stationName(id: number): string {
  return stations.value.find((item) => item.id === id)?.name ?? `站点 ${id}`
}

async function loadData(): Promise<void> {
  const [stationPage, points, alertPage, stat] = await Promise.all([
    pageStations({ pageNum: 1, pageSize: 100 }),
    stationMap(),
    pageAlerts({ pageNum: 1, pageSize: 20, status: 0 }),
    alertStat({})
  ])
  stations.value = stationPage.list
  mapPoints.value = points
  alerts.value = alertPage.list
  levelCount.value = stat.byLevel ?? {}

  // 并发拉取各站点近 24h 观测，用于趋势与降水对比
  const entries = await Promise.all(
    stationPage.list.map(async (station) => {
      try {
        const curve = await realtimeCurve(station.stationCode, 24, '1h')
        return [station.stationCode, curve] as const
      } catch {
        return [station.stationCode, []] as const
      }
    })
  )
  // 服务端按小时聚合，趋势平滑、降水累计口径正确（小时均值 × 1h 即该小时降水量）
  curves.value = Object.fromEntries(entries)
}

let refreshTimer: number | undefined
let clockTimer: number | undefined

onMounted(async () => {
  updateScale()
  window.addEventListener('resize', updateScale)
  await loadData()
  refreshTimer = window.setInterval(loadData, REFRESH_MS)
  clockTimer = window.setInterval(() => {
    clock.value = formatTime(new Date(), true)
  }, 1000)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateScale)
  window.clearInterval(refreshTimer)
  window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="screen-viewport">
    <div class="screen-canvas" :style="{ transform: `scale(${scale})` }">
      <div class="screen">
      <header class="screen-header">
        <Decoration5 :color="['#22d3ee', '#3b82f6']" :dur="3" class="header-deco" />
        <div class="header-center">
          <h1>校园智能气象服务系统</h1>
          <p>实时监测大屏 · {{ clock }}</p>
        </div>
        <Decoration5 :color="['#3b82f6', '#22d3ee']" :dur="3" class="header-deco" />
        <button class="exit-btn" @click="router.push('/dashboard')">退出大屏</button>
      </header>

      <main class="screen-body">
        <section class="col col-left">
          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.metrics + 'px' }" :color="COLOR_CYAN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">全网实时指标</div>
              <div class="metrics">
                <div class="metric">
                  <span class="metric-name">气温</span>
                  <DigitalFlop :config="flopConfig(averages.temp, '℃', '#22d3ee')" />
                </div>
                <div class="metric">
                  <span class="metric-name">湿度</span>
                  <DigitalFlop :config="flopConfig(averages.humi, '%', '#a78bfa')" />
                </div>
                <div class="metric">
                  <span class="metric-name">风速</span>
                  <DigitalFlop :config="flopConfig(averages.wind_speed, 'm/s', '#34d399')" />
                </div>
                <div class="metric">
                  <span class="metric-name">雨强</span>
                  <DigitalFlop :config="flopConfig(averages.rain, 'mm/h', '#f59e0b')" />
                </div>
              </div>
            </div>
          </BorderBox1>

          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.stations + 'px' }" :color="COLOR_CYAN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">
                站点状态
                <span class="box-tag">在线 {{ onlineCount }}/{{ mapPoints.length }}</span>
              </div>
              <ul class="station-list">
                <li v-for="point in mapPoints" :key="point.id">
                  <span class="dot" :class="{ on: point.onlineFlag === 1 }"></span>
                  <span class="station-name">{{ point.name }}</span>
                  <span v-if="point.alertLevel > 0" :style="{ color: ALERT_LEVEL_COLORS[point.alertLevel], fontSize: '12px' }">
                    {{ ALERT_LEVEL_LABELS[point.alertLevel] }}
                  </span>
                  <span v-else class="station-idle">{{ point.stationCode }}</span>
                </li>
              </ul>
            </div>
          </BorderBox1>
        </section>

        <section class="col col-center">
          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.trend + 'px' }" :color="COLOR_CYAN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">多站点气温趋势（近 24h · 小时均值）</div>
              <div class="chart-area">
                <AppChart v-if="Object.keys(curves).length" :option="trendOption" height="100%" />
              </div>
            </div>
          </BorderBox1>

          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.rain + 'px' }" :color="COLOR_CYAN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">各站点累计降水（近 24h）</div>
              <div class="chart-area">
                <AppChart v-if="rainRanking.length" :option="rainOption" height="100%" />
              </div>
            </div>
          </BorderBox1>
        </section>

        <section class="col col-right">
          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.alerts + 'px' }" :color="COLOR_WARN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">
                进行中告警
                <span class="box-tag warn">{{ alertingCount }} 个站点告警中</span>
              </div>
              <div class="chart-area">
                <ScrollBoard v-if="alerts.length" :config="alertBoardConfig" class="board" />
                <div v-else class="board-empty">当前无进行中告警</div>
              </div>
            </div>
          </BorderBox1>

          <BorderBox1 class="box" :style="{ height: PANEL_HEIGHT.levels + 'px' }" :color="COLOR_CYAN" background-color="rgba(8,20,38,0.6)">
            <div class="box-inner">
              <div class="box-head">告警等级分布</div>
              <div class="chart-area">
                <AppChart :option="levelOption" height="100%" />
              </div>
            </div>
          </BorderBox1>
        </section>
      </main>
    </div>
    </div>
  </div>
</template>

<style scoped>
.screen-viewport {
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: #06101f;
  display: flex;
  align-items: center;
  justify-content: center;
}

.screen-canvas {
  width: 1920px;
  height: 1080px;
  flex-shrink: 0;
}

.screen {
  width: 1920px;
  height: 1080px;
  display: flex;
  flex-direction: column;
  padding: 14px 20px 20px;
  background: radial-gradient(1200px 700px at 50% -10%, rgba(59, 130, 246, 0.16), transparent), #06101f;
  color: #e8eefb;
  font-family: 'Inter', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow: hidden;
}

.screen-header {
  position: relative;
  height: 90px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-deco {
  width: 320px;
  height: 42px;
}

.header-center {
  text-align: center;
  padding: 0 32px;
}

.header-center h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 4px;
  color: #e8eefb;
}

.header-center p {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8ba0bf;
  letter-spacing: 2px;
}

.exit-btn {
  position: absolute;
  right: 0;
  top: 12px;
  padding: 6px 14px;
  font-size: 12px;
  color: #8ba0bf;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(34, 211, 238, 0.3);
  border-radius: 6px;
  cursor: pointer;
}

.exit-btn:hover {
  color: #22d3ee;
  border-color: #22d3ee;
}

.screen-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  padding-top: 6px;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.col-left {
  width: 400px;
  flex-shrink: 0;
}

.col-center {
  flex: 1;
  min-width: 0;
}

.col-right {
  width: 460px;
  flex-shrink: 0;
}

.box {
  min-height: 0;
}

/* 面板内层承载 padding 与 flex 布局：直接作用在 DataV 根元素上会让其内容区测不到高度 */
.box-inner {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 42px 16px 14px;
  overflow: hidden;
}

/* 图表 / 榜单区域占满剩余高度 */
.chart-area {
  flex: 1;
  min-height: 0;
  position: relative;
}

.box-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  letter-spacing: 1px;
  color: #cfe0f7;
  margin-bottom: 10px;
}

.box-tag {
  font-size: 12px;
  color: #7dd3fc;
  padding: 1px 10px;
  border-radius: 20px;
  background: rgba(34, 211, 238, 0.12);
}

.box-tag.warn {
  color: #fbbf24;
  background: rgba(245, 158, 11, 0.14);
}

.metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 8px;
  border: 1px solid rgba(34, 211, 238, 0.12);
}

.metric-name {
  font-size: 12px;
  color: #8ba0bf;
}

.station-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.station-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 4px;
  border-bottom: 1px solid rgba(34, 211, 238, 0.08);
  font-size: 13px;
}

.station-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.station-idle {
  font-size: 12px;
  color: #5f7a9c;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #64748b;
  flex-shrink: 0;
}

.dot.on {
  background: #22c55e;
  box-shadow: 0 0 8px rgba(34, 197, 94, 0.8);
}

.board {
  width: 100%;
  height: 100%;
}

.board-empty {
  flex: 1;
  display: grid;
  place-items: center;
  color: #5f7a9c;
  font-size: 13px;
}
</style>