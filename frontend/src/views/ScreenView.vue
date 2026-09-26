<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { DigitalFlop, ScrollBoard } from '@kjgl77/datav-vue3'
import '@kjgl77/datav-vue3/dist/style.css'
import AppChart from '@/components/AppChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { AXIS_COLORS, SERIES_COLORS, sparseSymbolStyle } from '@/utils/chart'
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

/** 读数用中性色：与后台 .readout-value 同色。颜色留给告警等级编码，不在这里做装饰 */
const READOUT_COLOR = '#dfe3e9'

/** 大屏为固定画布（1080 高），面板高度按可用空间算好，避免 flex 撑不开导致图表组件测到 0 高度 */
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
  const series = Object.entries(curves.value).map(([stationCode, points], index) => ({
    name: stations.value.find((item) => item.stationCode === stationCode)?.name ?? stationCode,
    type: 'line' as const,
    smooth: true,
    ...sparseSymbolStyle(points.length),
    data: points.map((point) => [point.ts, point.elements?.temp ?? null]),
    lineStyle: { width: 2, color: SERIES_COLORS[index % SERIES_COLORS.length] },
    itemStyle: { color: SERIES_COLORS[index % SERIES_COLORS.length] }
  }))
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: AXIS_COLORS.label }, top: 0, icon: 'roundRect' },
    grid: { left: 52, right: 24, top: 40, bottom: 40 },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: AXIS_COLORS.line } },
      axisLabel: { color: AXIS_COLORS.label, fontSize: 11 },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: '℃',
      nameTextStyle: { color: AXIS_COLORS.label },
      splitLine: { lineStyle: { color: AXIS_COLORS.split } },
      axisLabel: { color: AXIS_COLORS.label, fontSize: 11 }
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
    axisLine: { lineStyle: { color: AXIS_COLORS.line } },
    axisLabel: { color: AXIS_COLORS.label, fontSize: 11, interval: 0, rotate: 12 }
  },
  yAxis: {
    type: 'value',
    name: 'mm',
    nameTextStyle: { color: AXIS_COLORS.label },
    splitLine: { lineStyle: { color: AXIS_COLORS.split } },
    axisLabel: { color: AXIS_COLORS.label, fontSize: 11 }
  },
  series: [
    {
      type: 'bar',
      barMaxWidth: 28,
      data: rainRanking.value.map((item) => item.value),
      // 纯色柱：渐变填充只增加装饰，不承载任何信息
      itemStyle: { color: '#4a7ea8', borderRadius: [3, 3, 0, 0] }
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
      label: { color: AXIS_COLORS.label, fontSize: 12, formatter: '{b}\n{c}' },
      itemStyle: { borderColor: '#17191f', borderWidth: 2 },
      data: [1, 2, 3, 4].map((level) => ({
        name: ALERT_LEVEL_LABELS[level],
        value: levelCount.value[String(level)] ?? 0,
        itemStyle: { color: ALERT_LEVEL_COLORS[level] }
      }))
    }
  ]
}))

function flopConfig(value: number | null, unit: string) {
  return {
    number: [value ?? 0],
    content: `{nt}${unit}`,
    toFixed: 1,
    style: {
      fontSize: 34,
      fill: READOUT_COLOR,
      // 读数走等宽字体；℃ 等符号等宽字体缺失，回退到中文字体承接
      fontFamily: "'Cascadia Mono', 'JetBrains Mono', 'Noto Sans SC', monospace"
    }
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
  // 榜单配色对齐系统：表头用钢蓝淡染，斑马纹用中性提亮，不再用青色霓虹
  headerBGC: 'rgba(74,126,168,0.18)',
  oddRowBGC: 'rgba(255,255,255,0.03)',
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
          <h1>校园智能气象服务系统</h1>
          <p>实时监测大屏 · {{ clock }}</p>
          <button class="exit-btn" @click="router.push('/dashboard')">退出大屏</button>
        </header>

        <main class="screen-body">
          <section class="col col-left">
            <section class="box" :style="{ height: PANEL_HEIGHT.metrics + 'px' }">
              <div class="box-head">全网实时指标</div>
              <div class="metrics">
                <div class="metric">
                  <span class="metric-name">气温</span>
                  <DigitalFlop :config="flopConfig(averages.temp, '℃')" />
                </div>
                <div class="metric">
                  <span class="metric-name">湿度</span>
                  <DigitalFlop :config="flopConfig(averages.humi, '%')" />
                </div>
                <div class="metric">
                  <span class="metric-name">风速</span>
                  <DigitalFlop :config="flopConfig(averages.wind_speed, 'm/s')" />
                </div>
                <div class="metric">
                  <span class="metric-name">雨强</span>
                  <DigitalFlop :config="flopConfig(averages.rain, 'mm/h')" />
                </div>
              </div>
            </section>

            <section class="box" :style="{ height: PANEL_HEIGHT.stations + 'px' }">
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
            </section>
          </section>

          <section class="col col-center">
            <section class="box" :style="{ height: PANEL_HEIGHT.trend + 'px' }">
              <div class="box-head">多站点气温趋势（近 24h · 小时均值）</div>
              <div class="chart-area">
                <AppChart v-if="Object.keys(curves).length" :option="trendOption" height="100%" />
              </div>
            </section>

            <section class="box" :style="{ height: PANEL_HEIGHT.rain + 'px' }">
              <div class="box-head">各站点累计降水（近 24h）</div>
              <div class="chart-area">
                <AppChart v-if="rainRanking.length" :option="rainOption" height="100%" />
              </div>
            </section>
          </section>

          <section class="col col-right">
            <section class="box" :style="{ height: PANEL_HEIGHT.alerts + 'px' }">
              <div class="box-head">
                进行中告警
                <span class="box-tag warn">{{ alertingCount }} 个站点告警中</span>
              </div>
              <div class="chart-area">
                <ScrollBoard v-if="alerts.length" :config="alertBoardConfig" class="board" />
                <div v-else class="board-empty"><EmptyState compact icon="check" title="当前无进行中告警" /></div>
              </div>
            </section>

            <section class="box" :style="{ height: PANEL_HEIGHT.levels + 'px' }">
              <div class="box-head">告警等级分布</div>
              <div class="chart-area">
                <AppChart :option="levelOption" height="100%" />
              </div>
            </section>
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
  background: var(--bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.screen-canvas {
  width: 1920px;
  height: 1080px;
  flex-shrink: 0;
}

/* 大屏沿用后台的仪器面板语言，只把尺寸放大：同一套中性底、钢蓝强调色、小圆角。
   DataV 的发光边框、对称装饰条与背景光晕此前只制造噪声，不承载任何信息，已移除。 */
.screen {
  width: 1920px;
  height: 1080px;
  display: flex;
  flex-direction: column;
  padding: 14px 22px 20px;
  background: var(--bg);
  color: var(--text);
  font-family: var(--font-sans);
  /* 大屏数字最大、刷新最频繁，全局启用等宽数字，避免每秒刷新时数字宽度跳动 */
  font-variant-numeric: tabular-nums;
  overflow: hidden;
}

/* 高 82 + 下边距 14 = 96，与 padding 14/20 合计让 .screen-body 恰好为 950px，
   与 PANEL_HEIGHT 三列总和（340+594、467+467、534+400 各加 16 间距）严格相等 */
.screen-header {
  position: relative;
  height: 82px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid var(--border);
  margin-bottom: 14px;
}

.screen-header h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--text);
}

.screen-header p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

.exit-btn {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  padding: 6px 14px;
  font-size: 12px;
  color: var(--text-muted);
  background: var(--bg-elevated);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-control);
  cursor: pointer;
  transition: all 0.15s ease;
}

.exit-btn:hover {
  color: var(--text);
  border-color: var(--primary);
}

.screen-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
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

/* 面板与后台 .panel 同款：1px 边框 + 小圆角 + 面板底，只是尺寸更大。
   早期用 DataV BorderBox1 承载发光边角，必须再套一层 .box-inner 才能撑出内容高度，现一并简化。 */
.box {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-surface);
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
  color: var(--text);
  margin-bottom: 10px;
}

.box-tag {
  font-size: 12px;
  color: var(--accent);
  padding: 1px 10px;
  border-radius: var(--radius-pill);
  background: rgba(74, 126, 168, 0.18);
  border: 1px solid rgba(74, 126, 168, 0.32);
}

.box-tag.warn {
  color: #e0b45c;
  background: rgba(217, 154, 43, 0.14);
  border-color: rgba(217, 154, 43, 0.32);
}

/* 指标区用分隔线而非四张独立卡片：边框与底色少一层，读数本身才是主体 */
.metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  flex: 1;
  min-height: 0;
}

.metric {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 8px 0 8px 16px;
  border-left: 1px solid var(--border);
}

/* 竖线是列分隔而非卡片边框，左列不画 */
.metric:nth-child(odd) {
  border-left: none;
  padding-left: 0;
}

.metric:nth-child(n + 3) {
  border-top: 1px solid var(--border);
}

.metric-name {
  font-size: 12px;
  color: var(--text-muted);
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
  border-bottom: 1px solid var(--border);
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
  color: var(--text-dim);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-dim);
  flex-shrink: 0;
}

/* 在线态不发光：状态用颜色编码即可，光晕只是装饰 */
.dot.on {
  background: var(--success);
}

.board {
  width: 100%;
  height: 100%;
}

.board-empty {
  flex: 1;
  display: grid;
  place-items: center;
  color: var(--text-dim);
  font-size: 13px;
}
</style>