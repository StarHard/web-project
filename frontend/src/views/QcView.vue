<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import { pageStations, type Station } from '@/api/monitor'
import { pageQcTasks, reviewQcTask, type QcTask } from '@/api/qc'
import { QC_STATUS_LABELS, elementLabel, formatTime } from '@/utils/format'
import { useFilters } from '@/utils/filters'
import { toastError, toastSuccess } from '@/utils/toast'

const stations = ref<Station[]>([])
const tasks = ref<QcTask[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const busyId = ref<number | null>(null)

const { filters, resetFilters } = useFilters<{ stationId?: number; status?: number }>('qc', {
  stationId: undefined,
  status: 0
})

const revising = ref<QcTask | null>(null)
const revisedValue = ref<number | null>(null)

const QC_TYPE_LABELS: Record<number, string> = {
  1: '极值检查',
  2: '时间一致性',
  3: '空间一致性'
}

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
}

async function loadTasks(): Promise<void> {
  loading.value = true
  try {
    const page = await pageQcTasks({ pageNum: pageNum.value, pageSize: pageSize.value, ...filters.value })
    tasks.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function stationName(id: number): string {
  return stations.value.find((item) => item.id === id)?.name ?? `站点 ${id}`
}

async function handleReview(task: QcTask, action: 'confirm' | 'void'): Promise<void> {
  busyId.value = task.id
  try {
    await reviewQcTask(task.id, action)
    toastSuccess(action === 'confirm' ? '已确认有效，数据将回写为质控通过' : '已作废')
    await loadTasks()
  } finally {
    busyId.value = null
  }
}

function openRevise(task: QcTask): void {
  revising.value = task
  revisedValue.value = task.obsValue
}

async function submitRevise(): Promise<void> {
  if (!revising.value || revisedValue.value === null) {
    toastError('请填写修正值')
    return
  }
  await reviewQcTask(revising.value.id, 'revise', revisedValue.value)
  toastSuccess('修正已提交，数据将回写为人工修正')
  revising.value = null
  await loadTasks()
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadTasks()
}

/** 重置筛选条件与页码后重新查询 */
function handleReset(): void {
  resetFilters()
  resetAndSearch()
}

/** 空态文案跟随筛选条件，避免在「已修正」等状态下仍提示「暂无待审核任务」 */
const emptyText = computed(() => {
  const status = filters.value.status
  return status === undefined ? '暂无质控任务' : `暂无${QC_STATUS_LABELS[status] ?? ''}的质控任务`
})

onMounted(async () => {
  await loadStations()
  await loadTasks()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">质控审核</h1>
        <p class="page-subtitle">可疑数据人工复核，审核结论回写时序库形成质控闭环</p>
      </div>
      <div class="row">
        <select v-model.number="filters.stationId" class="select" style="width: 170px" @change="resetAndSearch">
          <option :value="undefined">全部站点</option>
          <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-model.number="filters.status" class="select" style="width: 150px" @change="resetAndSearch">
          <option :value="undefined">全部状态</option>
          <option :value="0">待审核</option>
          <option :value="1">确认有效</option>
          <option :value="2">已修正</option>
          <option :value="3">已作废</option>
        </select>
        <button class="btn" @click="handleReset">重置</button>
        <button class="btn" :disabled="loading" @click="loadTasks">刷新</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>站点</th>
            <th>要素</th>
            <th>观测时间</th>
            <th>原始值</th>
            <th>检验类型</th>
            <th>检验详情</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in tasks" :key="item.id">
            <td>{{ stationName(item.stationId) }}</td>
            <td>{{ elementLabel(item.element) }}</td>
            <td>{{ formatTime(item.obsTime, true) }}</td>
            <td>{{ item.obsValue }}</td>
            <td>{{ QC_TYPE_LABELS[item.qcType] ?? item.qcType }}</td>
            <td :title="item.qcDetail" style="max-width: 280px; overflow: hidden; text-overflow: ellipsis">
              {{ item.qcDetail || '--' }}
            </td>
            <td>
              <span :class="item.status === 0 ? 'tag tag-warn' : item.status === 1 ? 'tag tag-online' : 'tag tag-muted'">
                {{ QC_STATUS_LABELS[item.status] }}
              </span>
            </td>
            <td>
              <template v-if="item.status === 0">
                <button class="btn btn-sm" :disabled="busyId === item.id" @click="handleReview(item, 'confirm')">
                  确认
                </button>
                <button class="btn btn-sm" style="margin: 0 6px" :disabled="busyId === item.id" @click="openRevise(item)">
                  修正
                </button>
                <button class="btn btn-sm btn-danger" :disabled="busyId === item.id" @click="handleReview(item, 'void')">
                  作废
                </button>
              </template>
              <span v-else style="color: var(--text-muted)">
                {{ item.reviewedValue !== undefined && item.reviewedValue !== null ? `修正为 ${item.reviewedValue}` : '已处理' }}
              </span>
            </td>
          </tr>
          <tr v-if="tasks.length === 0">
            <td colspan="8" class="table-empty">{{ emptyText }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadTasks"
      @update:pageSize="resetAndSearch"
    />

    <div v-if="revising" class="modal-mask" @click.self="revising = null">
      <div class="modal">
        <h3 class="modal-title">数据修正</h3>
        <div class="form-grid">
          <div class="field">
            <span class="field-label">站点 / 要素</span>
            <input class="input" :value="`${stationName(revising.stationId)} · ${elementLabel(revising.element)}`" disabled />
          </div>
          <div class="field">
            <span class="field-label">观测时间</span>
            <input class="input" :value="formatTime(revising.obsTime, true)" disabled />
          </div>
          <div class="field">
            <span class="field-label">原始值</span>
            <input class="input" :value="revising.obsValue" disabled />
          </div>
          <div class="field">
            <span class="field-label">修正值</span>
            <input v-model.number="revisedValue" type="number" step="0.1" class="input" />
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="revising = null">取消</button>
          <button class="btn btn-primary" @click="submitRevise">提交修正</button>
        </div>
      </div>
    </div>
  </div>
</template>