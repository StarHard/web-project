<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import AppChart from '@/components/AppChart.vue'
import { pageStations, type Station } from '@/api/monitor'
import {
  backtestForecast,
  compareModels,
  generateForecast,
  queryForecast,
  reviseForecast,
  verification,
  type CompareResp,
  type ForecastResp,
  type VerificationResp
} from '@/api/forecast'
import { useUserStore } from '@/stores/user'
import { elementLabel, formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const userStore = useUserStore()

const stations = ref<Station[]>([])
const currentStation = ref('')
const range = ref(24)
const loading = ref(false)
const forecast = ref<ForecastResp | null>(null)
const compare = ref<CompareResp | null>(null)
const verifyResult = ref<VerificationResp | null>(null)
const busy = ref('')

const revising = ref<{ time: string; element: string; origin: number } | null>(null)
const revisedValue = ref<number | null>(null)
const reviseReason = ref('')

const canOrder = computed(() => userStore.isAdmin || userStore.hasRole('FORECASTER'))

const forecastOption = computed<EChartsOption>(() => {
  const points = forecast.value?.points ?? []
  const times = points.map((point) => formatTime(point.time))
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { data: ['气温', '降水'], textStyle: { color: '#8ba0bf' }, top: 0 },
    grid: { left: 52, right: 52, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: times,
      axisLine: { lineStyle: { color: '#1f2f4a' } },
      axisLabel: { color: '#8ba0bf', fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: '℃',
        nameTextStyle: { color: '#8ba0bf' },
        splitLine: { lineStyle: { color: '#16233a' } },
        axisLabel: { color: '#8ba0bf', fontSize: 11 }
      },
      {
        type: 'value',
        name: 'mm',
        nameTextStyle: { color: '#8ba0bf' },
        splitLine: { show: false },
        axisLabel: { color: '#8ba0bf', fontSize: 11 }
      }
    ],
    series: [
      {
        name: '气温',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: points.map((point) => point.elements.temp ?? null),
        lineStyle: { width: 2, color: '#22d3ee' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(34,211,238,0.28)' },
              { offset: 1, color: 'rgba(34,211,238,0.02)' }
            ]
          }
        }
      },
      {
        name: '降水',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 10,
        data: points.map((point) => point.elements.rain ?? null),
        itemStyle: { color: 'rgba(59,130,246,0.75)', borderRadius: [3, 3, 0, 0] }
      }
    ]
  }
})

const compareOption = computed<EChartsOption>(() => {
  if (!compare.value) return {}
  const modelNames = Object.keys(compare.value.models)
  const palette = ['#22d3ee', '#f59e0b', '#a78bfa', '#34d399']
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { data: modelNames, textStyle: { color: '#8ba0bf' }, top: 0 },
    grid: { left: 52, right: 24, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: (compare.value.models[modelNames[0]] ?? []).map((point) => formatTime(point.time)),
      axisLine: { lineStyle: { color: '#1f2f4a' } },
      axisLabel: { color: '#8ba0bf', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#16233a' } },
      axisLabel: { color: '#8ba0bf', fontSize: 11 }
    },
    series: modelNames.map((model, index) => ({
      name: model,
      type: 'line' as const,
      smooth: true,
      showSymbol: false,
      data: (compare.value?.models[model] ?? []).map((point) => point.value),
      lineStyle: { width: 2, color: palette[index % palette.length] },
      itemStyle: { color: palette[index % palette.length] }
    }))
  }
})

const tablePoints = computed(() => (forecast.value?.points ?? []).slice(0, 12))

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
  if (!currentStation.value && page.list.length > 0) {
    currentStation.value = page.list[0].stationCode
  }
}

async function loadForecast(): Promise<void> {
  if (!currentStation.value) return
  loading.value = true
  try {
    forecast.value = await queryForecast(currentStation.value, 'stat', range.value)
  } finally {
    loading.value = false
  }
}

async function handleGenerate(): Promise<void> {
  busy.value = '生成中…'
  try {
    const result = await generateForecast(currentStation.value)
    toastSuccess(`预报生成完成：成功 ${result.success}/${result.total}`)
    await loadForecast()
  } finally {
    busy.value = ''
  }
}

async function handleBacktest(): Promise<void> {
  busy.value = '回算中…'
  try {
    const result = await backtestForecast(currentStation.value, 3)
    toastSuccess(`回算完成：${result.issues} 次发布（${result.days} 天）`)
  } finally {
    busy.value = ''
  }
}

async function handleCompare(): Promise<void> {
  busy.value = '对比加载中…'
  try {
    compare.value = await compareModels(currentStation.value, 'temp', range.value)
    if (Object.keys(compare.value.models).length === 0) {
      toastError('暂无可对比的模型数据')
    }
  } finally {
    busy.value = ''
  }
}

async function handleVerify(): Promise<void> {
  busy.value = '检验计算中…'
  try {
    verifyResult.value = await verification(currentStation.value, 'stat', 7)
  } finally {
    busy.value = ''
  }
}

function openRevise(pointTime: string, value: number): void {
  revising.value = { time: pointTime, element: 'temp', origin: value }
  revisedValue.value = value
  reviseReason.value = ''
}

async function submitRevise(): Promise<void> {
  if (!revising.value || revisedValue.value === null) {
    toastError('请填写订正值')
    return
  }
  const epochSecond = Math.floor(new Date(revising.value.time.replace(/-/g, '/')).getTime() / 1000)
  await reviseForecast(epochSecond, {
    stationCode: currentStation.value,
    element: revising.value.element,
    originValue: revising.value.origin,
    revisedValue: revisedValue.value,
    reason: reviseReason.value
  })
  toastSuccess('订正已提交，将以 manual 模型重新发布')
  revising.value = null
  await loadForecast()
}

onMounted(async () => {
  await loadStations()
  await loadForecast()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">精细预报</h1>
        <p class="page-subtitle">
          0–72h 逐小时预报产品
          <span v-if="forecast?.issueTime"> · 发布于 {{ forecast.issueTime }}</span>
        </p>
      </div>
      <div class="row">
        <select v-model="currentStation" class="select" style="width: 180px" @change="loadForecast">
          <option v-for="item in stations" :key="item.stationCode" :value="item.stationCode">
            {{ item.name }}
          </option>
        </select>
        <select v-model.number="range" class="select" style="width: 110px" @change="loadForecast">
          <option :value="24">24 小时</option>
          <option :value="48">48 小时</option>
          <option :value="72">72 小时</option>
        </select>
        <button class="btn" :disabled="!!busy" @click="handleCompare">多模型对比</button>
        <button class="btn" :disabled="!!busy" @click="handleVerify">准确率检验</button>
        <button class="btn" :disabled="!!busy" @click="handleGenerate">生成预报</button>
        <button class="btn" :disabled="!!busy" @click="handleBacktest">回算</button>
      </div>
    </div>

    <div class="panel" style="margin-bottom: 16px">
      <div class="panel-head">
        <h3 class="panel-title">预报曲线（气温 / 降水）</h3>
        <span v-if="busy" class="tag tag-info">{{ busy }}</span>
      </div>
      <AppChart v-if="forecast?.points.length" :option="forecastOption" height="340px" />
      <div v-else class="state">暂无预报数据，可点击「生成预报」或先做「回算」产出样本</div>
    </div>

    <div class="grid grid-2">
      <div class="panel">
        <h3 class="panel-title" style="margin-bottom: 12px">多模型对比</h3>
        <template v-if="compare && Object.keys(compare.models).length">
          <AppChart :option="compareOption" height="300px" />
          <div class="row" style="margin-top: 10px">
            <span v-for="(points, model) in compare.models" :key="model" class="tag tag-muted">
              {{ model }}：{{ points.length }} 点
            </span>
          </div>
        </template>
        <div v-else class="state">点击右上角「多模型对比」加载</div>
      </div>

      <div class="panel">
        <h3 class="panel-title" style="margin-bottom: 12px">准确率检验（近 7 天）</h3>
        <template v-if="verifyResult">
          <div class="verify-meta">样本 {{ verifyResult.forecastSamples }} 条 · 计算于 {{ verifyResult.generatedAt }}</div>
          <table class="table" style="margin-top: 10px">
            <thead>
              <tr>
                <th>要素</th>
                <th>样本</th>
                <th>MAE</th>
                <th>RMSE</th>
                <th>Bias</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in verifyResult.elements" :key="item.element">
                <td>{{ elementLabel(item.element) }}</td>
                <td>{{ item.samples }}</td>
                <td>{{ item.mae }}</td>
                <td>{{ item.rmse }}</td>
                <td>{{ item.bias }}</td>
              </tr>
              <tr v-if="verifyResult.rain?.samples">
                <td>降水（TS/POD/FAR）</td>
                <td>{{ verifyResult.rain.samples }}</td>
                <td>{{ verifyResult.rain.ts ?? '--' }}</td>
                <td>{{ verifyResult.rain.pod ?? '--' }}</td>
                <td>{{ verifyResult.rain.far ?? '--' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
        <div v-else class="state">点击右上角「准确率检验」计算评分</div>
      </div>
    </div>

    <div class="panel" style="margin-top: 16px">
      <div class="panel-head">
        <h3 class="panel-title">预报明细</h3>
        <span v-if="canOrder" class="tag tag-info">具备订正权限</span>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>目标时刻</th>
              <th>气温(℃)</th>
              <th>降水(mm)</th>
              <th>风速(m/s)</th>
              <th>湿度(%)</th>
              <th v-if="canOrder">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="point in tablePoints" :key="point.time">
              <td>{{ point.time }}</td>
              <td>{{ point.elements.temp?.toFixed(1) ?? '--' }}</td>
              <td>{{ point.elements.rain?.toFixed(1) ?? '--' }}</td>
              <td>{{ point.elements.wind_speed?.toFixed(1) ?? '--' }}</td>
              <td>{{ point.elements.humi?.toFixed(0) ?? '--' }}</td>
              <td v-if="canOrder">
                <button
                  class="btn btn-sm"
                  :disabled="point.elements.temp === undefined"
                  @click="openRevise(point.time, point.elements.temp)"
                >
                  订正
                </button>
              </td>
            </tr>
            <tr v-if="tablePoints.length === 0">
              <td :colspan="canOrder ? 6 : 5" class="table-empty">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="revising" class="modal-mask" @click.self="revising = null">
      <div class="modal">
        <h3 class="modal-title">预报订正</h3>
        <div class="form-grid">
          <div class="field">
            <span class="field-label">目标时刻</span>
            <input class="input" :value="revising.time" disabled />
          </div>
          <div class="field">
            <span class="field-label">原始预报值（℃）</span>
            <input class="input" :value="revising.origin" disabled />
          </div>
          <div class="field">
            <span class="field-label">订正值（℃）</span>
            <input v-model.number="revisedValue" type="number" step="0.1" class="input" />
          </div>
          <div class="field">
            <span class="field-label">订正依据</span>
            <textarea v-model="reviseReason" class="textarea" placeholder="如：模式对本地地形升温估计偏低"></textarea>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="revising = null">取消</button>
          <button class="btn btn-primary" @click="submitRevise">提交订正</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.verify-meta {
  font-size: 12px;
  color: var(--text-muted);
}
</style>