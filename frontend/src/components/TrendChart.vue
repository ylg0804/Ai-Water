<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import type { ECharts } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { formatNumber, metricMeta } from '../utils/format'
import type { Measurement, Metric } from '../domain/models'

use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = withDefaults(defineProps<{ rows: Measurement[]; metric: Metric; height?: number }>(), { height: 280 })
const element = ref<HTMLDivElement>()
const description = computed(() => `${metricMeta[props.metric].label}变化趋势，${props.rows.length}条测量记录，单位${metricMeta[props.metric].unit}`)
let chart: ECharts | undefined
let observer: ResizeObserver | undefined
const tickDate = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const tickTime = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false })

function draw() {
  if (!chart) return
  const groups = new Map<string, Measurement[]>()
  for (const row of props.rows) {
    if (!groups.has(row.deviceId)) groups.set(row.deviceId, [])
    groups.get(row.deviceId)!.push(row)
  }
  const span = props.rows.length > 1 ? Date.parse(props.rows.at(-1)!.measuredAt) - Date.parse(props.rows[0].measuredAt) : 0
  const colors = [metricMeta[props.metric].color, '#5a7bd7', '#b38a40', '#a07eb7']
  chart.setOption({
    animationDuration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 450,
    color: colors,
    textStyle: { fontFamily: 'Segoe UI, Microsoft YaHei, sans-serif' },
    grid: { left: 13, right: 16, top: groups.size > 1 ? 35 : 16, bottom: 12, containLabel: true },
    legend: { show: groups.size > 1, top: 0, right: 10, icon: 'roundRect', itemWidth: 12, itemHeight: 3, textStyle: { fontSize: 10, color: '#64748b' } },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, valueFormatter: (value: number | null | undefined) => value == null || !Number.isFinite(value) ? '—' : `${formatNumber(value, 3)} ${metricMeta[props.metric].unit}`, axisPointer: { lineStyle: { color: '#b9cdce', type: 'dashed' } } },
    xAxis: { type: 'time', boundaryGap: false, axisLine: { lineStyle: { color: '#e9edef' } }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { fontSize: 10, color: '#64748b', margin: 16, hideOverlap: true, formatter: (value: number) => (span > 86400000 ? tickDate : tickTime).format(new Date(value)) }, splitNumber: 6 },
    yAxis: { type: 'value', scale: true, splitNumber: 4, axisLabel: { fontSize: 10, color: '#64748b', margin: 13, formatter: (value: number) => value.toFixed(2) }, splitLine: { lineStyle: { color: '#edf1f3', type: 'dashed' } } },
    series: [...groups.entries()].map(([id, rows], index) => ({
      id, name: rows[0].deviceName, type: 'line', smooth: .3, showSymbol: rows.length < 2, symbolSize: 6,
      lineStyle: { width: 2 }, emphasis: { focus: 'series' },
      areaStyle: groups.size === 1 ? { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: `${colors[index % colors.length]}24` }, { offset: 1, color: `${colors[index % colors.length]}01` }] } } : undefined,
      data: [...rows].sort((a, b) => a.measuredAt.localeCompare(b.measuredAt)).map(row => [Date.parse(row.measuredAt), row[props.metric]]),
    })),
  }, true)
}
onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  observer = new ResizeObserver(() => { chart?.resize() })
  observer.observe(element.value)
  draw()
})
watch(() => [props.rows, props.metric], draw, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <div class="trend-chart-wrap" :style="{ height: `${height}px` }">
    <div ref="element" class="trend-chart" role="img" :aria-label="description" />
    <div v-if="!rows.length" class="chart-empty"><span>暂无测量记录</span><small>选择其他设备或调整时间范围</small></div>
  </div>
</template>

<style scoped>
.trend-chart-wrap { position: relative; width: 100%; min-width: 0; }
.trend-chart { width: 100%; height: 100%; }
.chart-empty { position: absolute; inset: 0; background: #fff; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 9px; color: #64748b; font-size: 13px; }
.chart-empty small { color: #64748b; font-size: 11px; }
</style>
