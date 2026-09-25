<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  pageConfigs,
  pageOperationLogs,
  updateConfig,
  type OperationLog,
  type SysConfig
} from '@/api/system'
import { formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const tab = ref<'config' | 'log'>('config')

const configs = ref<SysConfig[]>([])
const configTotal = ref(0)
const configPageNum = ref(1)
const configPageSize = ref(10)
const configKeyword = ref('')
const configLoading = ref(false)

const logs = ref<OperationLog[]>([])
const logTotal = ref(0)
const logPageNum = ref(1)
const logPageSize = ref(20)
const logModule = ref('')
const logLoading = ref(false)

const editing = ref<SysConfig | null>(null)
const editForm = ref({ configValue: '', remark: '' })

async function loadConfigs(): Promise<void> {
  configLoading.value = true
  try {
    const page = await pageConfigs({
      pageNum: configPageNum.value,
      pageSize: configPageSize.value,
      keyword: configKeyword.value
    })
    configs.value = page.list
    configTotal.value = page.total
  } finally {
    configLoading.value = false
  }
}

async function loadLogs(): Promise<void> {
  logLoading.value = true
  try {
    const page = await pageOperationLogs({
      pageNum: logPageNum.value,
      pageSize: logPageSize.value,
      module: logModule.value || undefined
    })
    logs.value = page.list
    logTotal.value = page.total
  } finally {
    logLoading.value = false
  }
}

function openEdit(item: SysConfig): void {
  editing.value = item
  editForm.value = { configValue: item.configValue, remark: item.remark ?? '' }
}

async function submitConfig(): Promise<void> {
  if (!editing.value) return
  if (!editForm.value.configValue.trim()) {
    toastError('参数值不能为空')
    return
  }
  await updateConfig(editing.value.id, editForm.value)
  toastSuccess('参数已更新，缓存已失效')
  editing.value = null
  await loadConfigs()
}

async function switchTab(next: 'config' | 'log'): Promise<void> {
  tab.value = next
  if (next === 'config') {
    await loadConfigs()
  } else {
    await loadLogs()
  }
}

function resetConfigSearch(): void {
  configPageNum.value = 1
  loadConfigs()
}

function resetLogSearch(): void {
  logPageNum.value = 1
  loadLogs()
}

onMounted(async () => {
  await Promise.all([loadConfigs(), loadLogs()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">系统管理</h1>
        <p class="page-subtitle">系统参数配置（质控阈值等）与操作日志审计</p>
      </div>
      <div class="row">
        <button class="btn" :class="{ 'btn-primary': tab === 'config' }" @click="switchTab('config')">系统参数</button>
        <button class="btn" :class="{ 'btn-primary': tab === 'log' }" @click="switchTab('log')">操作日志</button>
      </div>
    </div>

    <template v-if="tab === 'config'">
      <div class="row" style="margin-bottom: 14px">
        <input
          v-model="configKeyword"
          class="input"
          style="width: 220px"
          placeholder="参数键/说明，如 qc.temp"
          @keyup.enter="resetConfigSearch"
        />
        <button class="btn" @click="resetConfigSearch">搜索</button>
        <button class="btn" :disabled="configLoading" @click="loadConfigs">刷新</button>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>参数键</th>
              <th>参数值</th>
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in configs" :key="item.id">
              <td>{{ item.configKey }}</td>
              <td>{{ item.configValue }}</td>
              <td>{{ item.remark || '--' }}</td>
              <td>
                <button class="btn btn-sm" @click="openEdit(item)">修改</button>
              </td>
            </tr>
            <tr v-if="configs.length === 0">
              <td colspan="4" class="table-empty"><EmptyState compact icon="settings" title="暂无系统参数" /></td>
            </tr>
          </tbody>
        </table>
      </div>

      <DataPager
        v-model:pageNum="configPageNum"
        v-model:pageSize="configPageSize"
        :total="configTotal"
        @update:pageNum="loadConfigs"
        @update:pageSize="resetConfigSearch"
      />
    </template>

    <template v-else>
      <div class="row" style="margin-bottom: 14px">
        <input v-model="logModule" class="input" style="width: 200px" placeholder="模块，如 用户管理" @keyup.enter="resetLogSearch" />
        <button class="btn" @click="resetLogSearch">搜索</button>
        <button class="btn" :disabled="logLoading" @click="loadLogs">刷新</button>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>时间</th>
              <th>模块</th>
              <th>操作</th>
              <th>参数</th>
              <th>来源 IP</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in logs" :key="item.id">
              <td>{{ formatTime(item.createTime, true) }}</td>
              <td>{{ item.module }}</td>
              <td>{{ item.operation }}</td>
              <td :title="item.params" style="max-width: 260px; overflow: hidden; text-overflow: ellipsis">
                {{ item.params || '--' }}
              </td>
              <td>{{ item.ip || '--' }}</td>
              <td>
                <span :class="item.result === 1 ? 'tag tag-online' : 'tag tag-offline'">
                  {{ item.result === 1 ? '成功' : '失败' }}
                </span>
              </td>
            </tr>
            <tr v-if="logs.length === 0">
              <td colspan="6" class="table-empty"><EmptyState compact icon="doc" title="暂无操作日志" /></td>
            </tr>
          </tbody>
        </table>
      </div>

      <DataPager
        v-model:pageNum="logPageNum"
        v-model:pageSize="logPageSize"
        :total="logTotal"
        @update:pageNum="loadLogs"
        @update:pageSize="resetLogSearch"
      />
    </template>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal">
        <h3 class="modal-title">修改系统参数</h3>
        <div class="form-grid">
          <div class="field">
            <span class="field-label">参数键</span>
            <input class="input" :value="editing.configKey" disabled />
          </div>
          <label class="field">
            <span class="field-label">参数值</span>
            <input v-model="editForm.configValue" class="input" />
          </label>
          <label class="field">
            <span class="field-label">说明</span>
            <input v-model="editForm.remark" class="input" />
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="editing = null">取消</button>
          <button class="btn btn-primary" @click="submitConfig">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>