<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  createDevice,
  createMaintenance,
  createStation,
  deleteDevice,
  deleteStation,
  listDevices,
  pageMaintenance,
  pageStations,
  updateStation,
  type Device,
  type MaintenanceRecord,
  type Station
} from '@/api/monitor'
import { formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const DEVICE_TYPE_LABELS: Record<number, string> = {
  1: '风速风向',
  2: '雨量',
  3: '温湿压',
  4: '辐射',
  5: '蒸发',
  6: '能见度',
  7: '采集器'
}

const STATION_TYPE_LABELS: Record<number, string> = { 1: '校园', 2: '农业', 3: '区域' }
const MAINT_TYPE_LABELS: Record<number, string> = { 1: '检定', 2: '维修', 3: '更换', 4: '巡检' }

const stations = ref<Station[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  stationCode: '',
  name: '',
  province: '',
  city: '',
  district: '',
  longitude: 119.19666,
  latitude: 26.03195,
  altitude: 50,
  stationType: 1,
  status: 1,
  commissionDate: ''
})

const detailStation = ref<Station | null>(null)
const detailTab = ref<'device' | 'maintenance'>('device')
const devices = ref<Device[]>([])
const maintenance = ref<MaintenanceRecord[]>([])
const deviceForm = ref({ deviceCode: '', deviceType: 3, model: '', manufacturer: '', status: 1 })
const maintForm = ref({ type: 4, content: '', operator: '', maintDate: new Date().toISOString().slice(0, 10) })

async function loadStations(): Promise<void> {
  loading.value = true
  try {
    const page = await pageStations({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
    stations.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.value = {
    stationCode: '',
    name: '',
    province: '',
    city: '',
    district: '',
    longitude: 119.19666,
    latitude: 26.03195,
    altitude: 50,
    stationType: 1,
    status: 1,
    commissionDate: ''
  }
  showForm.value = true
}

function openEdit(item: Station): void {
  editingId.value = item.id
  form.value = {
    stationCode: item.stationCode,
    name: item.name,
    province: item.province ?? '',
    city: item.city ?? '',
    district: item.district ?? '',
    longitude: item.longitude,
    latitude: item.latitude,
    altitude: item.altitude ?? 0,
    stationType: item.stationType ?? 1,
    status: item.status,
    commissionDate: item.commissionDate ?? ''
  }
  showForm.value = true
}

async function submitStation(): Promise<void> {
  if (!form.value.stationCode.trim() || !form.value.name.trim()) {
    toastError('站点编码与名称不能为空')
    return
  }
  const payload = { ...form.value, commissionDate: form.value.commissionDate || undefined }
  if (editingId.value) {
    await updateStation(editingId.value, payload)
    toastSuccess('站点已更新')
  } else {
    await createStation(payload)
    toastSuccess('站点已新增')
  }
  showForm.value = false
  await loadStations()
}

async function handleDelete(item: Station): Promise<void> {
  if (!window.confirm(`确认删除站点「${item.name}」？`)) return
  await deleteStation(item.id)
  toastSuccess('站点已删除')
  await loadStations()
}

async function openDetail(item: Station, tab: 'device' | 'maintenance'): Promise<void> {
  detailStation.value = item
  detailTab.value = tab
  if (tab === 'device') {
    devices.value = await listDevices(item.id)
  } else {
    const page = await pageMaintenance({ pageNum: 1, pageSize: 50, stationId: item.id })
    maintenance.value = page.list
  }
}

async function switchTab(tab: 'device' | 'maintenance'): Promise<void> {
  if (!detailStation.value) return
  detailTab.value = tab
  await openDetail(detailStation.value, tab)
}

async function submitDevice(): Promise<void> {
  if (!detailStation.value || !deviceForm.value.deviceCode.trim()) {
    toastError('设备编码不能为空')
    return
  }
  await createDevice({ ...deviceForm.value, stationId: detailStation.value.id })
  toastSuccess('设备已新增')
  deviceForm.value = { deviceCode: '', deviceType: 3, model: '', manufacturer: '', status: 1 }
  await openDetail(detailStation.value, 'device')
}

async function removeDevice(item: Device): Promise<void> {
  if (!window.confirm(`确认删除设备「${item.deviceCode}」？`)) return
  await deleteDevice(item.id)
  toastSuccess('设备已删除')
  if (detailStation.value) await openDetail(detailStation.value, 'device')
}

async function submitMaintenance(): Promise<void> {
  if (!detailStation.value || !maintForm.value.content.trim()) {
    toastError('运维内容不能为空')
    return
  }
  await createMaintenance({ ...maintForm.value, stationId: detailStation.value.id })
  toastSuccess('运维记录已新增')
  maintForm.value = { type: 4, content: '', operator: '', maintDate: new Date().toISOString().slice(0, 10) }
  await openDetail(detailStation.value, 'maintenance')
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadStations()
}

onMounted(loadStations)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">站点设备</h1>
        <p class="page-subtitle">站点档案、观测设备与运维记录维护</p>
      </div>
      <div class="row">
        <input v-model="keyword" class="input" style="width: 190px" placeholder="站点名称/编码" @keyup.enter="resetAndSearch" />
        <button class="btn" @click="resetAndSearch">搜索</button>
        <button class="btn btn-primary" @click="openCreate">新增站点</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>编码</th>
            <th>名称</th>
            <th>类型</th>
            <th>坐标</th>
            <th>状态</th>
            <th>在线</th>
            <th>最后上报</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in stations" :key="item.id">
            <td>{{ item.stationCode }}</td>
            <td>{{ item.name }}</td>
            <td>{{ STATION_TYPE_LABELS[item.stationType ?? 1] }}</td>
            <td>{{ item.longitude }}, {{ item.latitude }}</td>
            <td>
              <span :class="item.status === 1 ? 'tag tag-info' : 'tag tag-muted'">
                {{ item.status === 1 ? '正常' : item.status === 2 ? '维护中' : '停用' }}
              </span>
            </td>
            <td>
              <span :class="item.onlineFlag === 1 ? 'tag tag-online' : 'tag tag-offline'">
                {{ item.onlineFlag === 1 ? '在线' : '离线' }}
              </span>
            </td>
            <td>{{ formatTime(item.lastReportTime) }}</td>
            <td>
              <button class="btn btn-sm" @click="openDetail(item, 'device')">设备</button>
              <button class="btn btn-sm" style="margin: 0 6px" @click="openDetail(item, 'maintenance')">运维</button>
              <button class="btn btn-sm" @click="openEdit(item)">编辑</button>
              <button class="btn btn-sm btn-danger" style="margin-left: 6px" @click="handleDelete(item)">删除</button>
            </td>
          </tr>
          <tr v-if="stations.length === 0">
            <td colspan="8" class="table-empty"><EmptyState compact icon="station" title="暂无站点数据" /></td>
          </tr>
        </tbody>
      </table>
    </div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadStations"
      @update:pageSize="resetAndSearch"
    />

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <h3 class="modal-title">{{ editingId ? '编辑站点' : '新增站点' }}</h3>
        <div class="form-grid">
          <label class="field">
            <span class="field-label">站点编码（MQTT 上报标识）</span>
            <input v-model="form.stationCode" class="input" :disabled="!!editingId" placeholder="如 CAMPUS01" />
          </label>
          <label class="field">
            <span class="field-label">站点名称</span>
            <input v-model="form.name" class="input" placeholder="如 校园气象站一号" />
          </label>
          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">经度</span>
              <input v-model.number="form.longitude" type="number" step="0.000001" class="input" />
            </label>
            <label class="field">
              <span class="field-label">纬度</span>
              <input v-model.number="form.latitude" type="number" step="0.000001" class="input" />
            </label>
          </div>
          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">类型</span>
              <select v-model.number="form.stationType" class="select">
                <option v-for="(label, key) in STATION_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">状态</span>
              <select v-model.number="form.status" class="select">
                <option :value="1">正常</option>
                <option :value="2">维护中</option>
                <option :value="0">停用</option>
              </select>
            </label>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" @click="submitStation">保存</button>
        </div>
      </div>
    </div>

    <div v-if="detailStation" class="modal-mask" @click.self="detailStation = null">
      <div class="modal" style="max-width: 760px">
        <h3 class="modal-title">{{ detailStation.name }} · 设备与运维</h3>
        <div class="row" style="margin-bottom: 14px">
          <button class="btn btn-sm" :class="{ 'btn-primary': detailTab === 'device' }" @click="switchTab('device')">
            设备清单
          </button>
          <button class="btn btn-sm" :class="{ 'btn-primary': detailTab === 'maintenance' }" @click="switchTab('maintenance')">
            运维记录
          </button>
        </div>

        <template v-if="detailTab === 'device'">
          <table class="table">
            <thead>
              <tr>
                <th>设备编码</th>
                <th>类型</th>
                <th>型号</th>
                <th>厂商</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="device in devices" :key="device.id">
                <td>{{ device.deviceCode }}</td>
                <td>{{ DEVICE_TYPE_LABELS[device.deviceType] ?? device.deviceType }}</td>
                <td>{{ device.model || '--' }}</td>
                <td>{{ device.manufacturer || '--' }}</td>
                <td>{{ device.status === 1 ? '正常' : device.status === 2 ? '故障' : '停用' }}</td>
                <td>
                  <button class="btn btn-sm btn-danger" @click="removeDevice(device)">删除</button>
                </td>
              </tr>
              <tr v-if="devices.length === 0">
                <td colspan="6" class="table-empty"><EmptyState compact icon="inbox" title="该站点暂无设备" /></td>
              </tr>
            </tbody>
          </table>

          <div class="form-grid" style="margin-top: 16px; border-top: 1px solid var(--border); padding-top: 16px">
            <div class="grid grid-2" style="gap: 14px">
              <label class="field">
                <span class="field-label">设备编码</span>
                <input v-model="deviceForm.deviceCode" class="input" placeholder="如 DEV-TEMP-01" />
              </label>
              <label class="field">
                <span class="field-label">设备类型</span>
                <select v-model.number="deviceForm.deviceType" class="select">
                  <option v-for="(label, key) in DEVICE_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
                </select>
              </label>
            </div>
            <button class="btn btn-primary" style="align-self: start" @click="submitDevice">新增设备</button>
          </div>
        </template>

        <template v-else>
          <table class="table">
            <thead>
              <tr>
                <th>日期</th>
                <th>类型</th>
                <th>内容</th>
                <th>操作人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in maintenance" :key="record.id">
                <td>{{ record.maintDate }}</td>
                <td>{{ MAINT_TYPE_LABELS[record.type] ?? record.type }}</td>
                <td>{{ record.content }}</td>
                <td>{{ record.operator }}</td>
              </tr>
              <tr v-if="maintenance.length === 0">
                <td colspan="4" class="table-empty"><EmptyState compact icon="doc" title="暂无运维记录" /></td>
              </tr>
            </tbody>
          </table>

          <div class="form-grid" style="margin-top: 16px; border-top: 1px solid var(--border); padding-top: 16px">
            <div class="grid grid-2" style="gap: 14px">
              <label class="field">
                <span class="field-label">类型</span>
                <select v-model.number="maintForm.type" class="select">
                  <option v-for="(label, key) in MAINT_TYPE_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
                </select>
              </label>
              <label class="field">
                <span class="field-label">日期</span>
                <input v-model="maintForm.maintDate" type="date" class="input" />
              </label>
            </div>
            <label class="field">
              <span class="field-label">内容</span>
              <input v-model="maintForm.content" class="input" placeholder="如：雨量计清洗校准" />
            </label>
            <label class="field">
              <span class="field-label">操作人</span>
              <input v-model="maintForm.operator" class="input" placeholder="操作人姓名" />
            </label>
            <button class="btn btn-primary" style="align-self: start" @click="submitMaintenance">新增运维记录</button>
          </div>
        </template>

        <div class="modal-actions">
          <button class="btn" @click="detailStation = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>