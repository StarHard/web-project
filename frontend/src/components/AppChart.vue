<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([
  LineChart,
  BarChart,
  GaugeChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  CanvasRenderer
])

const props = withDefaults(defineProps<{ option: EChartsOption; height?: string }>(), {
  height: '320px'
})

const container = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
/** 容器尺寸观察器：栅格列数变化、侧边栏收起等只改容器宽度，不触发 window.resize */
let observer: ResizeObserver | undefined

function render(): void {
  if (!container.value) return
  if (!chart.value) {
    chart.value = echarts.init(container.value, undefined, { renderer: 'canvas' })
  }
  chart.value.setOption(props.option, true)
}

function resize(): void {
  chart.value?.resize()
}

onMounted(() => {
  const host = container.value
  if (!host) return
  render()
  window.addEventListener('resize', resize)
  // ECharts 只在实例创建时按容器算一次尺寸；容器自身变窄（如要素增多使栅格列数变化）不会触发
  // window.resize，画布会冻结在旧尺寸并溢出到相邻面板。必须监听容器本身。
  observer = new ResizeObserver(resize)
  observer.observe(host)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  observer?.disconnect()
  chart.value?.dispose()
})

watch(() => props.option, render, { deep: true })

const style = computed(() => ({ height: props.height }))
</script>

<template>
  <div ref="container" :style="style" class="chart-host"></div>
</template>

<style scoped>
.chart-host {
  width: 100%;
}
</style>