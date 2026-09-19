<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { stationMap, type StationMapPoint } from '@/api/monitor'
import { ALERT_LEVEL_COLORS, ALERT_LEVEL_LABELS } from '@/utils/format'

const AMAP_KEY = import.meta.env.VITE_AMAP_KEY

const points = ref<StationMapPoint[]>([])
const mapElement = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapNotice = ref('')

const stats = computed(() => ({
  total: points.value.length,
  online: points.value.filter((item) => item.onlineFlag === 1).length,
  alerting: points.value.filter((item) => item.alertLevel > 0).length
}))

function loadAmap(): Promise<any> {
  return new Promise((resolve, reject) => {
    const existing = (window as any).AMap
    if (existing) {
      resolve(existing)
      return
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}`
    script.async = true
    script.onload = () => resolve((window as any).AMap)
    script.onerror = () => reject(new Error('高德地图脚本加载失败'))
    document.head.appendChild(script)
  })
}

function markerContent(point: StationMapPoint): string {
  const color = point.alertLevel > 0 ? ALERT_LEVEL_COLORS[point.alertLevel] : point.onlineFlag === 1 ? '#2fa85c' : '#6c7480'
  const ring = point.alertLevel > 0 ? `box-shadow:0 0 0 6px ${color}33;` : ''
  return `<div style="display:flex;flex-direction:column;align-items:center;gap:2px;cursor:pointer">
      <div style="width:12px;height:12px;border-radius:50%;background:${color};border:2px solid #fff;${ring}"></div>
      <span style="font-size:11px;color:#dfe3e9;background:rgba(15,17,22,.85);padding:1px 6px;border-radius:4px;white-space:nowrap">${point.name}</span>
    </div>`
}

async function initMap(): Promise<void> {
  if (!AMAP_KEY) {
    mapNotice.value = '未配置 VITE_AMAP_KEY 环境变量，已降级为站点列表视图'
    return
  }
  try {
    const AMap = await loadAmap()
    const center = points.value[0]
    const map = new AMap.Map(mapElement.value, {
      zoom: 12,
      center: center ? [center.longitude, center.latitude] : [116.397, 39.909],
      mapStyle: 'amap://styles/dark'
    })
    points.value.forEach((point) => {
      const marker = new AMap.Marker({
        position: [point.longitude, point.latitude],
        content: markerContent(point),
        offset: new AMap.Pixel(-6, -6)
      })
      marker.setTitle(`${point.name}${point.alertLevel > 0 ? ` · ${ALERT_LEVEL_LABELS[point.alertLevel]}预警` : ''}`)
      map.add(marker)
    })
    if (points.value.length > 1) {
      map.setFitView()
    }
    mapReady.value = true
  } catch {
    mapNotice.value = '高德地图加载失败（请检查 Key 与网络），已降级为站点列表视图'
  }
}

onMounted(async () => {
  points.value = await stationMap()
  await initMap()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">站点地图</h1>
        <p class="page-subtitle">站点分布与告警角标（坐标为 GCJ-02）</p>
      </div>
      <div class="row">
        <span class="tag tag-muted">站点 {{ stats.total }}</span>
        <span class="tag tag-online">在线 {{ stats.online }}</span>
        <span class="tag tag-warn">告警中 {{ stats.alerting }}</span>
      </div>
    </div>

    <div class="panel" style="padding: 0; overflow: hidden">
      <div v-show="mapReady" ref="mapElement" class="map-canvas"></div>

      <div v-if="!mapReady" class="fallback">
        <p v-if="mapNotice" class="notice">{{ mapNotice }}</p>
        <div class="grid grid-4">
          <div v-for="point in points" :key="point.id" class="panel station-card">
            <div class="row" style="justify-content: space-between">
              <strong>{{ point.name }}</strong>
              <span :class="point.onlineFlag === 1 ? 'tag tag-online' : 'tag tag-offline'">
                {{ point.onlineFlag === 1 ? '在线' : '离线' }}
              </span>
            </div>
            <div class="station-meta">{{ point.stationCode }}</div>
            <div class="station-meta">经度 {{ point.longitude }} · 纬度 {{ point.latitude }}</div>
            <div v-if="point.alertLevel > 0" class="station-meta">
              <span
                class="tag"
                :style="{
                  color: ALERT_LEVEL_COLORS[point.alertLevel],
                  background: `${ALERT_LEVEL_COLORS[point.alertLevel]}1f`,
                  borderColor: `${ALERT_LEVEL_COLORS[point.alertLevel]}66`
                }"
              >
                {{ ALERT_LEVEL_LABELS[point.alertLevel] }}预警
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.map-canvas {
  height: 620px;
  width: 100%;
}

.fallback {
  padding: 20px;
}

.notice {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--warning);
}

.station-card {
  background: var(--bg-elevated);
}

.station-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>