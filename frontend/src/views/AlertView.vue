<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import AppChart from '@/components/AppChart.vue'
import DataPager from '@/components/DataPager.vue'
import { pageStations, type Station } from '@/api/monitor'
import {
  alertStat,
  pageAlerts,
  relieveAlert,
  mySubscribes,
  subscribeAlert,
  unsubscribeAlert,
  type AlertRecord,
  type AlertSubscribe
} from '@/api/alert'
import {
  ALERT_LEVEL_COLORS,
  ALERT_LEVEL_LABELS,
  ALERT_TYPE_LABELS,
  formatTime
} from '@/utils/format'
import { useFilters } from '@/utils/filters'
import { toastError, toastSuccess } from '@/utils/toast'

const stations = ref<Station[]>([])
const records = ref<AlertRecord[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)

const { filters, resetFilters } = useFilters<{ stationId?: number; level?: number; status?: number }>('alert', {
  stationId: undefined,
  level: undefined,
  status: 0
})
const statData = ref<{ total: number; byLevel: Record<string, number>; byStatus: Record<string, number> } | null>(null)

const subscribes = ref<AlertSubscribe[]>([])
const showSubscribe = ref(false)
/** 告警详情弹窗：表格单元格放不下完整文案，这里给出事实描述与 AI 建议全文 */
const detail = ref<AlertRecord | null>(null)
const subscribeForm = ref<{ stationId: number; alertType: number; channels: string[] }>({
  stationId: 0,
  alertType: 0,
  channels: ['web']
})

const CHANNEL_OPTIONS = [
  { value: 'web', label: '站内' },
  { value: 'sms', label: '短信' },
  { value: 'email', label: '邮件' },
  { value: 'wechat', label: '微信' }
]

const statOption = computed<EChartsOption>(() => {
  const byLevel = statData.value?.byLevel ?? {}
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    series: [
      {
        type: 'pie',
        radius: ['46%', '72%'],
        center: ['50%', '52%'],
        label: { color: '#98a0ab', fontSize: 12 },
        itemStyle: { borderColor: '#17191f', borderWidth: 2 },
        data: [1, 2, 3, 4].map((level) => ({
          name: ALERT_LEVEL_LABELS[level],
          value: byLevel[String(level)] ?? 0,
          itemStyle: { color: ALERT_LEVEL_COLORS[level] }
        }))
      }
    ]
  }
})

const levelCards = computed(() => {
  const total = statData.value?.total ?? 0
  return [1, 2, 3, 4].map((level) => {
    const count = statData.value?.byLevel?.[String(level)] ?? 0
    return {
      level,
      label: ALERT_LEVEL_LABELS[level],
      color: ALERT_LEVEL_COLORS[level],
      count,
      ratio: total > 0 ? Math.round((count / total) * 100) : 0
    }
  })
})

async function loadStations(): Promise<void> {
  const page = await pageStations({ pageNum: 1, pageSize: 100 })
  stations.value = page.list
}

async function loadRecords(): Promise<void> {
  loading.value = true
  try {
    const page = await pageAlerts({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      ...filters.value
    })
    records.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadStat(): Promise<void> {
  statData.value = await alertStat({})
}

async function loadSubscribes(): Promise<void> {
  subscribes.value = await mySubscribes()
}

async function handleRelieve(id: number): Promise<void> {
  await relieveAlert(id)
  toastSuccess('告警已解除')
  await Promise.all([loadRecords(), loadStat()])
}

async function submitSubscribe(): Promise<void> {
  if (subscribeForm.value.channels.length === 0) {
    toastError('请至少选择一个接收渠道')
    return
  }
  await subscribeAlert(subscribeForm.value)
  toastSuccess('订阅已保存')
  await loadSubscribes()
}

async function removeSubscribe(item: AlertSubscribe): Promise<void> {
  await unsubscribeAlert(item.stationId, item.alertType)
  toastSuccess('已取消订阅')
  await loadSubscribes()
}

function stationName(id: number): string {
  if (id === 0) return '全部站点'
  return stations.value.find((item) => item.id === id)?.name ?? `站点 ${id}`
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadRecords()
}

/** 重置筛选条件与页码后重新查询 */
function handleReset(): void {
  resetFilters()
  resetAndSearch()
}

onMounted(async () => {
  await loadStations()
  await Promise.all([loadRecords(), loadStat(), loadSubscribes()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">灾害告警</h1>
        <p class="page-subtitle">
          阈值触发、等级升级与解除记录，支持订阅推送 · 累计告警
          <strong style="color: var(--text)">{{ statData?.total ?? 0 }}</strong> 条
        </p>
      </div>
      <div class="row">
        <button class="btn" @click="showSubscribe = true">我的订阅（{{ subscribes.length }}）</button>
        <button class="btn" @click="handleReset">重置</button>
        <button class="btn" :disabled="loading" @click="loadRecords">刷新</button>
      </div>
    </div>

    <div class="panel readout-strip" style="margin-bottom: 16px">
      <div v-for="card in levelCards" :key="card.level" class="readout">
        <div class="readout-label">{{ card.label }}预警</div>
        <div class="readout-value" :style="{ color: card.color }">{{ card.count }}</div>
        <div class="readout-range">占比 {{ card.ratio }}%</div>
      </div>
    </div>

    <div class="grid grid-2" style="margin-bottom: 16px">
      <div class="panel">
        <h3 class="panel-title" style="margin-bottom: 10px">告警等级分布</h3>
        <AppChart :option="statOption" height="240px" />
      </div>

      <div class="panel">
        <h3 class="panel-title" style="margin-bottom: 10px">筛选</h3>
        <div class="filters">
          <label class="field">
            <span class="field-label">站点</span>
            <select v-model.number="filters.stationId" class="select" @change="resetAndSearch">
              <option :value="undefined">全部站点</option>
              <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">等级</span>
            <select v-model.number="filters.level" class="select" @change="resetAndSearch">
              <option :value="undefined">全部等级</option>
              <option v-for="level in [1, 2, 3, 4]" :key="level" :value="level">
                {{ ALERT_LEVEL_LABELS[level] }}
              </option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">状态</span>
            <select v-model.number="filters.status" class="select" @change="resetAndSearch">
              <option :value="undefined">全部状态</option>
              <option :value="0">进行中</option>
              <option :value="1">已解除</option>
              <option :value="2">已升级</option>
            </select>
          </label>
          <button class="btn btn-primary" style="align-self: end" @click="resetAndSearch">查询</button>
        </div>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>站点</th>
            <th>等级</th>
            <th>触发值</th>
            <th>告警内容</th>
            <th>告警时间</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in records" :key="item.id">
            <td>{{ stationName(item.stationId) }}</td>
            <td>
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
            </td>
            <td>{{ item.obsValue }}</td>
            <td style="max-width: 360px">
              <div class="alert-fact" :title="item.content">{{ item.content }}</div>
              <div v-if="item.aiContent" class="alert-ai">
                <span class="ai-tag">AI</span>
                <span class="ai-text" :title="item.aiContent">{{ item.aiContent }}</span>
              </div>
            </td>
            <td>{{ formatTime(item.alertTime, true) }}</td>
            <td>
              <span :class="item.status === 0 ? 'tag tag-warn' : 'tag tag-muted'">
                {{ item.status === 0 ? '进行中' : item.status === 1 ? '已解除' : '已升级' }}
              </span>
            </td>
            <td>
              <div class="row" style="gap: 6px; flex-wrap: nowrap">
                <button class="btn btn-sm" @click="detail = item">详情</button>
                <button v-if="item.status === 0" class="btn btn-sm" @click="handleRelieve(item.id)">
                  解除
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="records.length === 0">
            <td colspan="7" class="table-empty">暂无告警记录</td>
          </tr>
        </tbody>
      </table>
    </div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadRecords"
      @update:pageSize="resetAndSearch"
    />

    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="modal">
        <h3 class="modal-title">告警详情</h3>

        <div class="detail-meta">
          <div>
            <span class="field-label">站点</span>
            <div>{{ stationName(detail.stationId) }}</div>
          </div>
          <div>
            <span class="field-label">等级</span>
            <div :style="{ color: ALERT_LEVEL_COLORS[detail.level] }">
              {{ ALERT_LEVEL_LABELS[detail.level] }}
            </div>
          </div>
          <div>
            <span class="field-label">触发值</span>
            <div>{{ detail.obsValue }}</div>
          </div>
          <div>
            <span class="field-label">告警时间</span>
            <div>{{ formatTime(detail.alertTime, true) }}</div>
          </div>
          <div>
            <span class="field-label">状态</span>
            <div>{{ detail.status === 0 ? '进行中' : detail.status === 1 ? '已解除' : '已升级' }}</div>
          </div>
          <div v-if="detail.relieveTime">
            <span class="field-label">解除时间</span>
            <div>{{ formatTime(detail.relieveTime, true) }}</div>
          </div>
        </div>

        <div class="field detail-block">
          <span class="field-label">告警内容（规则判定）</span>
          <p class="detail-text">{{ detail.content }}</p>
        </div>

        <div class="field detail-block">
          <span class="field-label">AI 处置建议</span>
          <p v-if="detail.aiContent" class="detail-text">{{ detail.aiContent }}</p>
          <p v-else class="detail-text detail-empty">
            本条告警没有 AI 建议：可能生成时大模型不可用，或该告警产生于启用本功能之前。
          </p>
        </div>

        <div class="modal-actions">
          <button class="btn" @click="detail = null">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="showSubscribe" class="modal-mask" @click.self="showSubscribe = false">
      <div class="modal">
        <h3 class="modal-title">我的告警订阅</h3>

        <div class="form-grid">
          <label class="field">
            <span class="field-label">站点</span>
            <select v-model.number="subscribeForm.stationId" class="select">
              <option :value="0">全部站点</option>
              <option v-for="item in stations" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">告警类型</span>
            <select v-model.number="subscribeForm.alertType" class="select">
              <option :value="0">全部类型</option>
              <option v-for="(label, type) in ALERT_TYPE_LABELS" :key="type" :value="Number(type)">
                {{ label }}
              </option>
            </select>
          </label>
          <div class="field">
            <span class="field-label">接收渠道</span>
            <div class="row">
              <label v-for="channel in CHANNEL_OPTIONS" :key="channel.value" class="row" style="gap: 4px">
                <input
                  v-model="subscribeForm.channels"
                  type="checkbox"
                  :value="channel.value"
                />
                {{ channel.label }}
              </label>
            </div>
          </div>
        </div>

        <button class="btn btn-primary" style="margin-top: 14px" @click="submitSubscribe">保存订阅</button>

        <table class="table" style="margin-top: 18px">
          <thead>
            <tr>
              <th>站点</th>
              <th>类型</th>
              <th>渠道</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in subscribes" :key="item.id">
              <td>{{ stationName(item.stationId) }}</td>
              <td>{{ item.alertType === 0 ? '全部' : ALERT_TYPE_LABELS[item.alertType] }}</td>
              <td>{{ item.channels }}</td>
              <td>
                <button class="btn btn-sm btn-danger" @click="removeSubscribe(item)">取消</button>
              </td>
            </tr>
            <tr v-if="subscribes.length === 0">
              <td colspan="4" class="table-empty">尚未订阅任何告警</td>
            </tr>
          </tbody>
        </table>

        <div class="modal-actions">
          <button class="btn" @click="showSubscribe = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
  align-items: end;
}

/* 事实描述单行省略，AI 建议放宽到两行：单元格里能多读到一些，完整文案走详情弹窗 */
.alert-fact {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-ai {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
}

.ai-tag {
  flex-shrink: 0;
  margin-top: 2px;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 10px;
  background: rgba(74, 126, 168, 0.18);
  border: 1px solid rgba(74, 126, 168, 0.35);
  color: var(--accent);
}

.ai-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: normal;
  line-height: 1.5;
}

/* ===== 告警详情弹窗 ===== */
.detail-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.detail-block {
  margin-top: 16px;
}

.detail-text {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
  white-space: pre-wrap;
}

.detail-empty {
  color: var(--text-dim);
}
</style>