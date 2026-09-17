<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Activity, ArrowRight, Bell, ChevronRight, Clock3, Droplets, Info, Radio, RefreshCw, Waves, Wind } from '@lucide/vue'
import TrendChart from '../components/TrendChart.vue'
import LiveVideo from '../components/LiveVideo.vue'
import { alarmTypeMeta } from '../domain/alarmMeta'
import { deviceDisplayName, deviceSecondaryLabel } from '../domain/device'
import { platform } from '../services/platform/store'
import { formatDate, formatNumber, metricMeta } from '../utils/format'
import type { Measurement, Metric } from '../domain/models'

const router = useRouter()
const selectedId = ref(platform.state.devices[0]?.id ?? '')
const selected = computed(() => platform.state.devices.find(d => d.id === selectedId.value))
const healthyDevices = computed(() => platform.state.devices.filter(d => d.status === 1 && d.online === true))
const measuredDevices = computed(() => platform.state.devices.filter(d => platform.latestForDevice(d.id)))
const waitingDevices = computed(() => platform.state.devices.filter(d => d.status === 1 && !platform.latestForDevice(d.id)))
const current = computed(() => platform.latestForDevice(selectedId.value))
const openAlarms = computed(() => platform.state.alarms.filter(a => a.status === 'OPEN').sort((a, b) => b.createdAt.localeCompare(a.createdAt)))
const alarmInitializing = computed(() => !platform.state.ready && platform.state.connection !== 'error')
const alarmUnavailable = computed(() => Boolean((platform.state.alarmError && !platform.state.alarms.length)
  || (!platform.state.ready && platform.state.connection === 'error')))
const recentAlarms = computed(() => openAlarms.value.slice(0, 3))
const metric = ref<Metric>('waterLevel')
const metrics: Metric[] = ['waterLevel', 'velocity', 'discharge']
const metricIcons = { waterLevel: Droplets, velocity: Wind, discharge: Waves }
const refreshing = ref(false)
const realtimeLabel = computed(() => ({ stopped: '实时连接未建立', connecting: '正在连接', connected: '实时更新', polling: '自动更新' }[platform.state.realtime]))
const handledCount = computed(() => platform.state.alarms.filter(alarm => alarm.status !== 'OPEN').length)
const historyRows = ref<Measurement[]>([])
const historyLoading = ref(false)
const historyError = ref('')
let historyRequest = 0
let historyTimer: ReturnType<typeof setTimeout> | undefined

async function loadHistory() {
  const request = ++historyRequest
  const deviceId = selectedId.value
  historyError.value = ''
  if (!deviceId) { historyRows.value = []; historyLoading.value = false; return }
  historyLoading.value = true
  const end = new Date()
  try {
    const rows = await platform.queryHistory({ deviceId, start: new Date(end.getTime() - 86400000), end })
    if (request === historyRequest) historyRows.value = rows
  } catch (error) {
    if (request === historyRequest) {
      historyRows.value = []
      historyError.value = error instanceof Error ? error.message : '历史数据读取失败，请重试。'
    }
  } finally { if (request === historyRequest) historyLoading.value = false }
}
function sparkline(key: Metric): string {
  const samples = historyRows.value.slice(-16)
  const values = samples.map(row => row[key]).filter((value): value is number => value !== null && Number.isFinite(value))
  if (values.length < 2) return ''
  const min = Math.min(...values)
  const span = Math.max(...values) - min
  let interrupted = true
  return samples.map((row, index) => {
    const value = row[key]
    if (value === null || !Number.isFinite(value)) { interrupted = true; return '' }
    const command = interrupted ? 'M' : 'L'
    interrupted = false
    return `${command}${(index / (samples.length - 1) * 64).toFixed(1)} ${(span ? 21 - (value - min) / span * 18 : 12).toFixed(1)}`
  }).join(' ')
}
watch(() => platform.state.devices.map(d => d.id), ids => { if (!ids.includes(selectedId.value)) selectedId.value = ids[0] ?? '' }, { immediate: true })
watch(selectedId, () => { historyRows.value = []; void loadHistory() }, { immediate: true })
watch(() => current.value?.measuredAt, () => {
  if (historyTimer) clearTimeout(historyTimer)
  historyTimer = setTimeout(() => { void loadHistory() }, 400)
})

async function refresh() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await platform.refreshMeasurements()
    if (historyTimer) clearTimeout(historyTimer)
    await loadHistory()
    ElMessage.success('数据已刷新。')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '刷新未完成，请重试。') }
  finally { refreshing.value = false }
}
onBeforeUnmount(() => { ++historyRequest; if (historyTimer) clearTimeout(historyTimer) })
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading">
      <h1>监测总览</h1>
      <div class="dashboard-actions"><span class="updated-at"><Clock3 :size="13" />更新于 {{ formatDate(platform.state.lastUpdated) }}</span><span class="realtime-state" :class="{ connected: platform.state.realtime === 'connected' }"><i class="status-dot" />{{ realtimeLabel }}</span><el-button :loading="refreshing || platform.state.loading" @click="refresh()"><RefreshCw :size="14" />刷新数据</el-button></div>
    </div>

    <section class="overview-cards" aria-label="平台运行概况">
      <button class="overview-card blue" @click="router.push('/devices')"><span class="overview-icon"><Radio :size="26" /></span><span class="overview-copy"><strong>设备运行</strong></span><span class="overview-numbers"><span>近期采样正常<b>{{ platform.state.ready ? healthyDevices.length : '—' }}<i>台</i></b></span><span>设备总数<b>{{ platform.state.ready ? platform.state.devices.length : '—' }}<i>台</i></b></span></span></button>
      <button class="overview-card cyan" @click="router.push('/history')"><span class="overview-icon"><Activity :size="26" /></span><span class="overview-copy"><strong>水情采样</strong></span><span class="overview-numbers"><span>已有测量<b>{{ platform.state.ready ? measuredDevices.length : '—' }}<i>台</i></b></span><span>启用待采样<b>{{ platform.state.ready ? waitingDevices.length : '—' }}<i>台</i></b></span></span></button>
      <button class="overview-card amber" @click="router.push('/alarms?status=OPEN')"><span class="overview-icon"><Bell :size="26" /></span><span class="overview-copy"><strong>告警待办</strong></span><span class="overview-numbers"><span>待处理<b>{{ platform.state.ready && !alarmUnavailable ? openAlarms.length : '—' }}<i>条</i></b></span><span>已处理<b>{{ platform.state.ready && !alarmUnavailable ? handledCount : '—' }}<i>条</i></b></span></span></button>
    </section>

    <section class="device-cards" aria-label="设备最新测量">
      <div class="device-list-heading"><h2>监测设备</h2><span>{{ platform.state.ready ? platform.state.devices.length : '—' }} 个监测点</span></div>
      <button v-for="device in platform.state.devices" :key="device.id" class="device-card" :class="{ selected: selectedId === device.id }" :aria-pressed="selectedId === device.id" @click="selectedId = device.id">
        <div class="device-card-heading"><span class="device-ident"><span><strong>{{ deviceDisplayName(device) }}</strong><small v-if="deviceSecondaryLabel(device)">{{ deviceSecondaryLabel(device) }}</small></span></span><span class="connection-state" :class="{ offline: device.online !== true || !device.status }"><i class="status-dot" />{{ device.status === 0 ? '已停用' : device.online === true ? '近期采样正常' : device.online === false ? '近期无成功采样' : '尚无采样状态' }}</span></div>
        <div class="card-measurements"><div v-for="key in metrics" :key="key"><label>{{ metricMeta[key].label }} <span>{{ metricMeta[key].unit }}</span></label><div class="metric-value"><strong>{{ formatNumber(platform.latestForDevice(device.id)?.[key]) }}</strong></div></div></div>
        <div class="device-card-footer"><span><Clock3 :size="11" />{{ platform.latestForDevice(device.id) ? formatDate(platform.latestForDevice(device.id)!.measuredAt) : '暂无测量数据' }}</span></div>
      </button>
      <div v-if="!platform.state.devices.length" class="panel no-devices"><Radio :size="30" /><p>{{ platform.state.loading ? '正在加载设备' : platform.state.ready ? '暂无监测设备' : '设备列表加载失败' }}</p><el-button v-if="platform.state.ready" type="primary" @click="router.push('/devices')">添加设备</el-button><el-button v-else :loading="platform.state.loading" @click="refresh()">重新加载</el-button></div>
    </section>

    <div class="monitoring-grid">
      <LiveVideo :device="selected" />

      <section class="panel latest-panel">
        <div class="panel-header"><div><h2>当前水情</h2><p>{{ selected?.serial ?? '未选择设备' }}</p></div></div>
        <div class="current-measurements"><div v-for="key in metrics" :key="key" class="current-metric"><span class="metric-icon" :style="{ color: metricMeta[key].color, background: `${metricMeta[key].color}0b` }"><component :is="metricIcons[key]" :size="21" :stroke-width="1.6" /></span><div><span>{{ metricMeta[key].label }}</span><div class="metric-value"><strong>{{ formatNumber(current?.[key]) }}<small>{{ metricMeta[key].unit }}</small></strong></div></div><span class="mini-trend" :style="{ color: metricMeta[key].color }"><svg viewBox="0 0 64 24" aria-hidden="true"><path :d="sparkline(key)" fill="none" stroke="currentColor" stroke-width="1.6" /></svg></span></div></div>
        <div class="measurement-update"><span><Clock3 :size="12" />测量于 {{ formatDate(current?.measuredAt) }}</span><span>{{ realtimeLabel }}</span></div>
        <div v-if="selected?.runtimeError" class="current-info"><Info :size="13" /><span>{{ selected.runtimeError }}</span></div>
      </section>
    </div>

    <div class="bottom-grid">
      <section class="panel chart-panel">
        <div class="panel-header"><h2>水情变化趋势</h2><RouterLink to="/history" class="text-link">历史数据<ArrowRight :size="13" /></RouterLink></div>
        <div class="chart-meta"><div class="metric-tabs" role="tablist" aria-label="水情指标"><button v-for="key in metrics" :key="key" role="tab" :aria-selected="metric === key" :class="{ active: metric === key }" @click="metric = key">{{ metricMeta[key].label }}</button></div><span>单位：{{ metricMeta[metric].unit }}</span></div>
        <div class="chart-container" :aria-busy="historyLoading">
          <div v-if="historyLoading" class="chart-feedback" role="status"><RefreshCw :size="22" /><span>正在加载历史测量</span></div>
          <div v-else-if="historyError" class="chart-feedback" role="alert"><Info :size="22" /><span>{{ historyError }}</span><el-button size="small" @click="loadHistory()">重新加载</el-button></div>
          <TrendChart v-else :rows="selected ? historyRows : []" :metric="metric" :height="226" />
        </div>
      </section>
      <section class="panel recent-alarm-panel"><div class="panel-header"><h2>待处理告警 <span class="inline-count">{{ platform.state.ready && !alarmUnavailable ? openAlarms.length : '—' }}</span></h2><RouterLink to="/alarms?status=OPEN" class="text-link">全部<ArrowRight :size="13" /></RouterLink></div><div class="recent-alarms"><button v-for="alarm in recentAlarms" :key="alarm.id" class="recent-alarm" @click="router.push({ path: '/alarms', query: { status: 'OPEN', id: alarm.id } })"><span class="alarm-type-icon" :style="{ color: alarmTypeMeta[alarm.type].color, background: `${alarmTypeMeta[alarm.type].color}12` }"><Bell :size="17" /></span><div><strong>{{ alarmTypeMeta[alarm.type].dashboardLabel }}<span>{{ formatDate(alarm.createdAt).slice(5, 16) }}</span></strong><p>{{ alarm.deviceName }}</p><small>{{ alarm.detail }}</small></div><ChevronRight :size="14" class="alarm-chevron" /></button><div v-if="!recentAlarms.length" class="all-clear"><Bell :size="25" /><strong>{{ alarmInitializing ? '正在加载告警数据' : alarmUnavailable ? '告警数据加载失败' : '暂无待处理告警' }}</strong></div></div></section>
    </div>
  </div>
</template>

<style scoped>
.dashboard-page { display: grid; grid-template-columns: 258px minmax(0,1fr); gap: 18px; align-items: start; }
.page-heading { grid-column: 1 / -1; margin-bottom: 0; }
.dashboard-actions { display: flex; align-items: center; gap: 14px; }
.updated-at { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #64748b; }
.realtime-state { display: flex; align-items: center; gap: 6px; color: #64748b; font-size: 11px; white-space: nowrap; }.realtime-state .status-dot { background: #a2aec0; }.realtime-state.connected { color: #4a9d86; }.realtime-state.connected .status-dot { background: #57af96; }
.chart-feedback { min-height: 240px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #64748b; font-size: 12px; text-align: center; }
.overview-cards { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 16px; }
.overview-card { display: grid; grid-template-columns: 49px minmax(0,1fr); column-gap: 13px; align-items: center; background: white; border: 1px solid #e9edf4; border-radius: 7px; padding: 20px 20px 17px; text-align: left; color: #3b4a62; min-width: 0; }
button.overview-card { transition: box-shadow .18s,transform .18s; }
button.overview-card:hover { box-shadow: 0 4px 15px #30558510; transform: translateY(-2px); }
.overview-icon { display: grid; place-items: center; width: 49px; height: 49px; border-radius: 50%; color: white; }
.blue .overview-icon { background: linear-gradient(135deg,#8bc9fe,#4b8aea); box-shadow: 0 4px 10px #5b9bee25; }
.cyan .overview-icon { background: linear-gradient(135deg,#76d9d8,#32aab8); box-shadow: 0 4px 10px #42b3c025; }
.amber .overview-icon { background: linear-gradient(135deg,#ffc75e,#fa9b45); box-shadow: 0 4px 10px #efac4725; }
.overview-copy { min-width: 0; }
.overview-copy>strong { font-size: 14px; font-weight: 600; }
.overview-numbers { grid-column: 1/-1; display: grid; grid-template-columns: 1fr 1fr; margin-top: 22px; }
.overview-numbers>span { color: #64748b; font-size: 11px; }
.overview-numbers>span+span { border-left: 1px solid #e8edf5; padding-left: 20px; }
.overview-numbers b { display: block; font-size: 23px; line-height: 1; color: #364862; font-weight: 550; margin-top: 8px; font-variant-numeric: tabular-nums; }
.overview-numbers i { font-size: 11px; font-style: normal; color: #64748b; font-weight: 400; margin-left: 6px; }
.device-cards { grid-column: 1; grid-row: 3; display: grid; grid-template-columns: 1fr; gap: 12px; align-content: start; background: #fff; border: 1px solid #e9edf4; border-radius: 7px; padding: 18px 13px; align-self: stretch; }
.device-list-heading { display: flex; align-items: center; justify-content: space-between; padding: 0 4px 4px; }
.device-list-heading h2 { font-size: 14px; font-weight: 600; color: #41506b; }.device-list-heading>span { font-size: 11px; color: #64748b; }
.device-card { min-width: 0; display: block; text-align: left; background: #fff; border: 1px solid #e8edf5; border-radius: 6px; padding: 13px 12px 0; position: relative; color: inherit; transition: border-color .18s; }
.device-card.selected { border-color: #9bbcf2; background: #f8fbff; box-shadow: 0 0 0 1px #4d83e804; }.device-card:hover { border-color: #a5c2f1; }
.device-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 3px; }.device-ident { min-width: 0; }
.device-ident strong { display: block; color: #475a76; font-size: 11px; font-weight: 550; line-height: 1.4; }.device-ident small { display: block; font-size: 11px; color: #64748b; margin-top: 4px; }
.connection-state { display: flex; align-items: center; gap: 4px; color: #64a891; font-size: 11px; white-space: nowrap; margin-top: 2px; }.connection-state .status-dot { width: 4px; height: 4px; }.connection-state.offline { color: #64748b; }.offline .status-dot { background: #b4bfd0; }
.card-measurements { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); padding: 16px 0 12px; }.card-measurements>div { border-left: 1px solid #e8eef8; padding-left: 11px; }.card-measurements>div:first-child { padding-left: 0; border: 0; }
.card-measurements label { display: block; font-size: 11px; color: #64748b; }.card-measurements label span { font-size: 11px; color: #64748b; margin-left: 2px; }
.card-measurements strong { display: block; margin-top: 6px; font-size: 19px; line-height: 1; color: #4b6182; font-weight: 550; font-variant-numeric: tabular-nums; }.selected .card-measurements>div:first-child strong { color: #4d83e8; }.metric-value { display: flex; align-items: flex-end; gap: 5px; }
.device-card-footer { display: flex; align-items: center; min-height: 31px; border-top: 1px solid #eaf0f8; color: #64748b; font-size: 11px; }.device-card-footer>span { display: flex; align-items: center; gap: 4px; }
.no-devices { padding: 25px 5px; display: grid; justify-items: center; gap: 12px; color: #64748b; font-size: 12px; }
.monitoring-grid { grid-column: 2; grid-row: 3; display: grid; grid-template-columns: minmax(0,1.5fr) minmax(240px,1fr); gap: 18px; align-items: stretch; }
.latest-panel .panel-header { padding-bottom: 6px; }
.current-measurements { padding: 0 19px; }.current-metric { display: flex; align-items: center; gap: 11px; padding: 13px 0; border-bottom: 1px solid #edf1f7; }.metric-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 8px; flex-shrink: 0; }.current-metric>div>span { color: #64748b; font-size: 11px; }.current-metric strong { display: block; margin-top: 4px; color: #445975; font-size: 22px; font-weight: 550; line-height: 1.1; font-variant-numeric: tabular-nums; }.current-metric small { font-size: 11px; font-weight: 400; color: #64748b; margin-left: 7px; }.mini-trend { width: 47px; margin-left: auto; opacity: .7; }.mini-trend svg { display: block; width: 100%; }
.measurement-update { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 18px; font-size: 11px; color: #64748b; }.measurement-update>span { display: flex; align-items: center; gap: 4px; }.current-info { background: #fafbfd; padding: 10px 18px; border-top: 1px solid #f1f4f8; display: flex; align-items: center; gap: 5px; color: #64748b; font-size: 11px; }
.bottom-grid { display: contents; }.chart-panel { grid-column: 1 / -1; grid-row: 4; align-self: stretch; }.text-link { display: flex; align-items: center; gap: 5px; color: #7299d2; font-size: 11px; }.text-link:hover { color: #4d83e8; }
.chart-meta { display: flex; align-items: center; justify-content: space-between; padding: 0 20px; }.chart-meta>span { color: #64748b; font-size: 11px; }.metric-tabs { display: flex; gap: 4px; }.metric-tabs button { padding: 6px 14px; border-radius: 16px; background: #f4f7fc; color: #64748b; border: 0; font-size: 11px; }.metric-tabs button.active { color: white; background: #5b8bea; box-shadow: 0 2px 5px #4d83e81c; }.chart-container { padding: 17px 20px 13px; }.chart-container :deep(.trend-chart-wrap) { min-height: 240px; }
.recent-alarm-panel { grid-column: 1/-1; grid-row: 5; }.recent-alarm-panel .panel-header { padding-bottom: 12px; }.inline-count { display: inline-grid; place-items: center; background: #fff1e9; color: #df9b64; width: 20px; height: 20px; font-size: 11px; border-radius: 4px; margin-left: 5px; }
.recent-alarms { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); padding: 0 20px 18px; gap: 25px; }.recent-alarm { display: flex; align-items: flex-start; gap: 10px; background: transparent; border: 0; text-align: left; padding: 9px 0; min-width: 0; color: #4f617d; }.recent-alarm:hover { background: #f8faff; }.alarm-type-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0; }.recent-alarm>div { flex: 1; min-width: 0; }.recent-alarm strong { display: flex; align-items: center; justify-content: space-between; font-weight: 500; font-size: 12px; gap: 7px; }.recent-alarm strong>span { font-size: 11px; color: #64748b; font-weight: 400; }.recent-alarm p { font-size: 11px; color: #64748b; margin-top: 5px; }.recent-alarm small { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 11px; color: #64748b; margin-top: 6px; }.alarm-chevron { color: #adbacd; margin-top: 10px; }.all-clear { grid-column: 1/-1; min-height: 85px; display: flex; align-items: center; justify-content: center; gap: 13px; color: #64748b; }.all-clear strong { font-size: 13px; font-weight: 500; }
@media (min-width: 1650px) { .dashboard-page { grid-template-columns: 280px minmax(0,1fr); }.monitoring-grid { grid-template-columns: minmax(0,1.7fr) minmax(280px,1fr); }.current-metric { padding: 16px 0; }.current-metric strong { font-size: 25px; } }
@media (max-width: 1350px) { .dashboard-page { grid-template-columns: 224px minmax(0,1fr); gap: 15px; }.monitoring-grid { grid-template-columns: minmax(0,1.35fr) minmax(220px,1fr); gap: 15px; }.overview-card { padding: 17px 18px; }.device-card-heading { flex-wrap: wrap; gap: 6px; }.device-card-footer { font-size: 11px; }.mini-trend { width: 33px; }.recent-alarms { gap: 16px; } }
@media (max-width: 1100px) { .dashboard-page { grid-template-columns: 200px minmax(0,1fr); gap: 14px; }.overview-cards { gap: 12px; }.overview-card { padding: 17px 13px; grid-template-columns: 40px minmax(0,1fr); gap: 10px; }.overview-icon { width: 40px; height: 40px; }.overview-numbers>span+span { padding-left: 13px; }.monitoring-grid { grid-template-columns: 1fr; }.latest-panel { display: none; }.device-cards { padding: 16px 10px; }.device-card { padding: 12px 9px 0; }.card-measurements strong { font-size: 17px; }.card-measurements>div { padding-left: 8px; }.updated-at { display: none; }.recent-alarm strong { font-size: 11px; }.recent-alarms { grid-template-columns: 1fr; gap: 0; }.recent-alarm { padding: 13px 0; border-bottom: 1px solid #eff3f8; }.recent-alarm:last-child { border: 0; }.recent-alarm strong>span { margin-left: auto; } }
@media (max-width: 850px) { .dashboard-page { grid-template-columns: 215px minmax(0,1fr); } }
@media (max-width: 680px) { .dashboard-page { grid-template-columns: 1fr; gap: 14px; }.page-heading,.overview-cards,.device-cards,.monitoring-grid,.chart-panel,.recent-alarm-panel { grid-column: 1; grid-row: auto; }.overview-cards { grid-template-columns: 1fr; gap: 10px; }.overview-card { grid-template-columns: 43px 1fr 1.2fr; align-items: center; padding: 16px; gap: 13px; }.overview-icon { width: 43px; height: 43px; }.overview-numbers { grid-column: auto; margin-top: 0; }.overview-numbers b { font-size: 19px; }.overview-numbers>span { font-size: 11px; }.device-cards { grid-template-columns: repeat(2,minmax(0,1fr)); padding: 16px 12px; gap: 10px; }.device-list-heading { grid-column: 1/-1; }.card-measurements strong { font-size: 18px; }.device-card-footer { font-size: 11px; }.device-ident strong { font-size: 11px; }.connection-state { font-size: 11px; }.monitoring-grid { display: block; }.chart-container { padding: 13px 10px; }.recent-alarms { padding: 0 17px 12px; }.dashboard-actions { gap: 10px; }.page-heading { gap: 12px; flex-wrap: nowrap; }.dashboard-actions .el-button { padding: 8px 10px; font-size: 11px; }.all-clear { flex-wrap: wrap; gap: 10px; } }
@media (prefers-reduced-motion: reduce) { button.overview-card:hover { transform: none; } }
.dashboard-actions .el-button+.el-button { margin-left: 0; }
@media (max-width: 680px) { .page-heading { flex-wrap: wrap; }.dashboard-actions { width: 100%; justify-content: flex-end; } }
</style>
