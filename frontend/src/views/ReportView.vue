<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import { pageStations, type Station } from '@/api/monitor'
import { downloadReport, generateReport, pageReports, type ReportFile } from '@/api/content'
import { formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const REPORT_TYPE_LABELS: Record<number, string> = {
  1: '日报',
  2: '月报',
  3: '年报',
  4: '极值统计',
  5: '气候对比'
}

const stations = ref<Station[]>([])
const reports = ref<ReportFile[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const generating = ref(false)

const filters = ref<{ stationId?: number; reportType?: number; period?: string }>({})

const showGenerate = ref(false)
const form = ref<{ stationId: number | null; reportType: number; periodStart: string; periodEnd: string; element: string }>({
  stationId: null,
  reportType: 1,
  periodStart: new Date().toISOString().slice(0, 10),
  periodEnd: new Date().toISOString().slice(0, 10),
  element: 'temp'
})

const needsElement = () => form.value.reportType === 4 || form.value.reportType === 5

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
  if (!form.value.stationId && page.list.length > 0) {
    form.value.stationId = page.list[0].id
  }
}

async function loadReports(): Promise<void> {
  loading.value = true
  try {
    const page = await pageReports({ pageNum: pageNum.value, pageSize: pageSize.value, ...filters.value })
    reports.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function submitGenerate(): Promise<void> {
  if (!form.value.stationId) {
    toastError('请选择站点')
    return
  }
  generating.value = true
  try {
    await generateReport({
      stationId: form.value.stationId,
      reportType: form.value.reportType,
      periodStart: form.value.periodStart,
      periodEnd: form.value.periodEnd,
      element: needsElement() ? form.value.element : undefined
    })
    toastSuccess('报表生成完成')
    showGenerate.value = false
    pageNum.value = 1
    await loadReports()
  } finally {
    generating.value = false
  }
}

async function handleDownload(item: ReportFile): Promise<void> {
  await downloadReport(item.id)
  toastSuccess('下载已开始')
}

function stationName(id: number): string {
  return id === 0 ? '多站点汇总' : stations.value.find((item) => item.id === id)?.name ?? `站点 ${id}`
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadReports()
}

onMounted(async () => {
  await loadStations()
  await loadReports()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">统计报表</h1>
        <p class="page-subtitle">日报 / 月报 / 年报 / 极值 / 气候对比，生成后以 CSV 归档</p>
      </div>
      <div class="row">
        <select v-model.number="filters.stationId" class="select" style="width: 160px" @change="resetAndSearch">
          <option :value="undefined">全部站点</option>
          <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-model.number="filters.reportType" class="select" style="width: 140px" @change="resetAndSearch">
          <option :value="undefined">全部类型</option>
          <option v-for="(label, key) in REPORT_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
        </select>
        <input v-model="filters.period" class="input" style="width: 130px" placeholder="yyyy-MM" @change="resetAndSearch" />
        <button class="btn" :disabled="loading" @click="loadReports">刷新</button>
        <button class="btn btn-primary" @click="showGenerate = true">生成报表</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>站点</th>
            <th>类型</th>
            <th>统计周期</th>
            <th>生成时间</th>
            <th>文件</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in reports" :key="item.id">
            <td>{{ stationName(item.stationId) }}</td>
            <td>{{ REPORT_TYPE_LABELS[item.reportType] ?? item.reportType }}</td>
            <td>{{ item.periodStart }} ~ {{ item.periodEnd }}</td>
            <td>{{ formatTime(item.createTime, true) }}</td>
            <td style="max-width: 280px; overflow: hidden; text-overflow: ellipsis">{{ item.filePath }}</td>
            <td>
              <button class="btn btn-sm" @click="handleDownload(item)">下载</button>
            </td>
          </tr>
          <tr v-if="reports.length === 0">
            <td colspan="6" class="table-empty">暂无报表，点击「生成报表」创建</td>
          </tr>
        </tbody>
      </table>
    </div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadReports"
      @update:pageSize="resetAndSearch"
    />

    <div v-if="showGenerate" class="modal-mask" @click.self="showGenerate = false">
      <div class="modal">
        <h3 class="modal-title">生成统计报表</h3>
        <div class="form-grid">
          <label class="field">
            <span class="field-label">站点</span>
            <select v-model.number="form.stationId" class="select">
              <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">报表类型</span>
            <select v-model.number="form.reportType" class="select">
              <option v-for="(label, key) in REPORT_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">统计起始日</span>
            <input v-model="form.periodStart" type="date" class="input" />
          </label>
          <label v-if="form.reportType === 4" class="field">
            <span class="field-label">统计结束日（极值统计必填）</span>
            <input v-model="form.periodEnd" type="date" class="input" />
          </label>
          <label v-if="needsElement()" class="field">
            <span class="field-label">要素</span>
            <select v-model="form.element" class="select">
              <option value="temp">气温</option>
              <option value="rain">降水</option>
              <option value="wind_speed">风速</option>
              <option value="humi">湿度</option>
            </select>
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showGenerate = false">取消</button>
          <button class="btn btn-primary" :disabled="generating" @click="submitGenerate">
            {{ generating ? '生成中…' : '生成' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>