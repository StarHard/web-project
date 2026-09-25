<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import { pageStations, type Station } from '@/api/monitor'
import {
  createAlertRule,
  deleteAlertRule,
  pageAlertRules,
  toggleAlertRule,
  updateAlertRule,
  type AlertRule
} from '@/api/alert'
import { ALERT_LEVEL_LABELS, ALERT_TYPE_LABELS, elementLabel } from '@/utils/format'
import { useFilters } from '@/utils/filters'
import { toastError, toastSuccess } from '@/utils/toast'

const CONDITION_LABELS: Record<number, string> = {
  1: '大于阈值',
  2: '小于阈值',
  3: '持续超限'
}

const ELEMENTS = ['rain', 'wind_speed', 'temp', 'humi', 'pres', 'vis']

const stations = ref<Station[]>([])
const rules = ref<AlertRule[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const { filters, resetFilters } = useFilters<{ stationId?: number; alertType?: number; status?: number }>(
  'alert-rule-admin',
  {
    stationId: undefined,
    alertType: undefined,
    status: undefined
  }
)

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  stationId: 0,
  alertType: 1,
  element: 'rain',
  condition: 1,
  threshold: 0,
  durationMin: 60,
  level: 1,
  upgradeLevel: undefined as number | undefined,
  channels: ['web'] as string[]
})

const CHANNEL_OPTIONS = [
  { value: 'web', label: '站内' },
  { value: 'sms', label: '短信' },
  { value: 'email', label: '邮件' },
  { value: 'wechat', label: '微信' }
]

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
}

async function loadRules(): Promise<void> {
  loading.value = true
  try {
    const page = await pageAlertRules({ pageNum: pageNum.value, pageSize: pageSize.value, ...filters.value })
    rules.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function stationName(id: number): string {
  return id === 0 ? '全局规则' : stations.value.find((item) => item.id === id)?.name ?? `站点 ${id}`
}

function openCreate(): void {
  editingId.value = null
  form.value = {
    stationId: 0,
    alertType: 1,
    element: 'rain',
    condition: 1,
    threshold: 0,
    durationMin: 60,
    level: 1,
    upgradeLevel: undefined,
    channels: ['web']
  }
  showForm.value = true
}

function openEdit(item: AlertRule): void {
  editingId.value = item.id
  form.value = {
    stationId: item.stationId,
    alertType: item.alertType,
    element: item.element,
    condition: item.condition,
    threshold: item.threshold,
    durationMin: item.durationMin ?? 60,
    level: item.level,
    upgradeLevel: item.upgradeLevel,
    channels: item.channels ? item.channels.split(',') : ['web']
  }
  showForm.value = true
}

async function submitRule(): Promise<void> {
  if (form.value.channels.length === 0) {
    toastError('请至少选择一个推送渠道')
    return
  }
  if (form.value.condition === 3 && !form.value.durationMin) {
    toastError('持续判定条件须配置持续时长')
    return
  }
  const payload = { ...form.value, durationMin: form.value.condition === 3 ? form.value.durationMin : undefined }
  if (editingId.value) {
    await updateAlertRule(editingId.value, payload)
    toastSuccess('规则已更新')
  } else {
    await createAlertRule(payload)
    toastSuccess('规则已新增')
  }
  showForm.value = false
  await loadRules()
}

async function handleToggle(item: AlertRule): Promise<void> {
  await toggleAlertRule(item.id, item.status !== 1)
  toastSuccess(item.status === 1 ? '规则已停用' : '规则已启用')
  await loadRules()
}

async function handleDelete(item: AlertRule): Promise<void> {
  if (!window.confirm('确认删除该告警规则？')) return
  await deleteAlertRule(item.id)
  toastSuccess('规则已删除')
  await loadRules()
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadRules()
}

/** 重置筛选条件与页码后重新查询 */
function handleReset(): void {
  resetFilters()
  resetAndSearch()
}

onMounted(async () => {
  await loadStations()
  await loadRules()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">告警规则</h1>
        <p class="page-subtitle">阈值条件、持续判定、等级升级与推送渠道配置</p>
      </div>
      <div class="row">
        <select v-model.number="filters.stationId" class="select" style="width: 150px" @change="resetAndSearch">
          <option :value="undefined">全部站点</option>
          <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-model.number="filters.status" class="select" style="width: 120px" @change="resetAndSearch">
          <option :value="undefined">全部状态</option>
          <option :value="1">启用</option>
          <option :value="0">停用</option>
        </select>
        <button class="btn" @click="handleReset">重置</button>
        <button class="btn" :disabled="loading" @click="loadRules">刷新</button>
        <button class="btn btn-primary" @click="openCreate">新增规则</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>适用站点</th>
            <th>类型</th>
            <th>要素</th>
            <th>判定条件</th>
            <th>阈值</th>
            <th>持续</th>
            <th>等级</th>
            <th>渠道</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rules" :key="item.id">
            <td>{{ stationName(item.stationId) }}</td>
            <td>{{ ALERT_TYPE_LABELS[item.alertType] ?? item.alertType }}</td>
            <td>{{ elementLabel(item.element) }}</td>
            <td>{{ CONDITION_LABELS[item.condition] }}</td>
            <td>{{ item.threshold }}</td>
            <td>{{ item.condition === 3 ? `${item.durationMin} 分钟` : '--' }}</td>
            <td>
              {{ ALERT_LEVEL_LABELS[item.level] }}
              <span v-if="item.upgradeLevel" style="color: var(--warning)">→{{ ALERT_LEVEL_LABELS[item.upgradeLevel] }}</span>
            </td>
            <td>{{ item.channels }}</td>
            <td>
              <span :class="item.status === 1 ? 'tag tag-online' : 'tag tag-muted'">
                {{ item.status === 1 ? '启用' : '停用' }}
              </span>
            </td>
            <td>
              <button class="btn btn-sm" @click="openEdit(item)">编辑</button>
              <button class="btn btn-sm" style="margin: 0 6px" @click="handleToggle(item)">
                {{ item.status === 1 ? '停用' : '启用' }}
              </button>
              <button class="btn btn-sm btn-danger" @click="handleDelete(item)">删除</button>
            </td>
          </tr>
          <tr v-if="rules.length === 0">
            <td colspan="10" class="table-empty"><EmptyState compact icon="shield" title="暂无告警规则" /></td>
          </tr>
        </tbody>
      </table>
    </div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadRules"
      @update:pageSize="resetAndSearch"
    />

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <h3 class="modal-title">{{ editingId ? '编辑告警规则' : '新增告警规则' }}</h3>
        <div class="form-grid">
          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">适用站点</span>
              <select v-model.number="form.stationId" class="select">
                <option :value="0">全局规则</option>
                <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">告警类型</span>
              <select v-model.number="form.alertType" class="select">
                <option v-for="(label, key) in ALERT_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
              </select>
            </label>
          </div>

          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">判定要素</span>
              <select v-model="form.element" class="select">
                <option v-for="element in ELEMENTS" :key="element" :value="element">{{ elementLabel(element) }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">判定条件</span>
              <select v-model.number="form.condition" class="select">
                <option v-for="(label, key) in CONDITION_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
              </select>
            </label>
          </div>

          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">阈值</span>
              <input v-model.number="form.threshold" type="number" step="0.1" class="input" />
            </label>
            <label v-if="form.condition === 3" class="field">
              <span class="field-label">持续时长（分钟）</span>
              <input v-model.number="form.durationMin" type="number" class="input" />
            </label>
          </div>

          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">初始等级</span>
              <select v-model.number="form.level" class="select">
                <option v-for="level in [1, 2, 3, 4]" :key="level" :value="level">{{ ALERT_LEVEL_LABELS[level] }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">升级等级（可选）</span>
              <select v-model.number="form.upgradeLevel" class="select">
                <option :value="undefined">不升级</option>
                <option v-for="level in [1, 2, 3, 4]" :key="level" :value="level">{{ ALERT_LEVEL_LABELS[level] }}</option>
              </select>
            </label>
          </div>

          <div class="field">
            <span class="field-label">推送渠道</span>
            <div class="row">
              <label v-for="channel in CHANNEL_OPTIONS" :key="channel.value" class="row" style="gap: 4px">
                <input v-model="form.channels" type="checkbox" :value="channel.value" />
                {{ channel.label }}
              </label>
            </div>
          </div>
        </div>

        <div class="modal-actions">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" @click="submitRule">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>