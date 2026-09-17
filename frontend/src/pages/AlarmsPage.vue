<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { AlertTriangle, BellRing, CameraOff, Check, CheckCheck, CircleCheck, Filter, ImageIcon, RotateCcw, ShieldCheck, X } from '@lucide/vue'
import { alarmStatusMeta as statusMeta, alarmTypeMeta as typeMeta, implementedAlarmTypes } from '../domain/alarmMeta'
import { deviceDisplayName } from '../domain/device'
import { platform } from '../services/platform/store'
import { useAlarmSnapshot } from '../composables/useAlarmSnapshot'
import { formatDate } from '../utils/format'
import type { AlarmStatus, AlarmType } from '../domain/models'

const route = useRoute()
const router = useRouter()
const deviceFilter = ref('')
const typeFilter = ref<AlarmType | ''>('')
const statusFilter = ref<AlarmStatus | ''>('')
const currentPage = ref(1)
const pageSize = ref(10)
const pendingAlarmId = ref<string | null>(null)
const pendingStatus = ref<'CONFIRMED' | 'IGNORED' | null>(null)
const alarmInitializing = computed(() => !platform.state.ready && platform.state.connection !== 'error')
const alarmUnavailable = computed(() => Boolean((platform.state.alarmError && !platform.state.alarms.length)
  || (!platform.state.ready && platform.state.connection === 'error')))
const alarmCountsUnavailable = computed(() => alarmInitializing.value || alarmUnavailable.value)
const summaries = computed(() => [
  { status: 'OPEN' as const, label: '待处理告警', count: alarmCountsUnavailable.value ? '—' : platform.state.alarms.filter(alarm => alarm.status === 'OPEN').length, icon: BellRing, className: 'open' },
  { status: 'CONFIRMED' as const, label: '已确认告警', count: alarmCountsUnavailable.value ? '—' : platform.state.alarms.filter(alarm => alarm.status === 'CONFIRMED').length, icon: ShieldCheck, className: 'confirmed' },
  { status: 'IGNORED' as const, label: '已忽略告警', count: alarmCountsUnavailable.value ? '—' : platform.state.alarms.filter(alarm => alarm.status === 'IGNORED').length, icon: CheckCheck, className: 'ignored' },
])
const deviceOptions = computed(() => platform.state.devices.map(device => ({ id: device.id, name: deviceDisplayName(device) })))
const filteredAlarms = computed(() => platform.state.alarms
  .filter(alarm => (!deviceFilter.value || alarm.deviceId === deviceFilter.value)
    && (!typeFilter.value || alarm.type === typeFilter.value)
    && (!statusFilter.value || alarm.status === statusFilter.value))
  .slice()
  .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()))
const pageAlarms = computed(() => filteredAlarms.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
const hasFilters = computed(() => Boolean(deviceFilter.value || typeFilter.value || statusFilter.value))
const selectedAlarmRecord = computed(() => platform.state.alarms.find(alarm => alarm.id === route.query.id) ?? null)
const snapshot = useAlarmSnapshot()
const snapshotDisplayError = ref(false)
const snapshotKey = computed(() => {
  const alarm = selectedAlarmRecord.value
  return alarm?.snapshotUrl ? `${alarm.id}\u0000${alarm.snapshotUrl}` : ''
})
const selectedAlarm = computed(() => selectedAlarmRecord.value)
const hasSnapshot = computed(() => Boolean(selectedAlarmRecord.value?.snapshotUrl))
const detailVisible = computed({
  get: () => selectedAlarm.value !== null,
  set: (visible: boolean) => { if (!visible) closeDetail() },
})

watch(() => route.query.status, value => {
  statusFilter.value = value === 'OPEN' || value === 'CONFIRMED' || value === 'IGNORED' ? value : ''
}, { immediate: true })
watch([deviceFilter, typeFilter, statusFilter, pageSize], () => { currentPage.value = 1 })
watch(() => filteredAlarms.value.length, length => {
  currentPage.value = Math.min(currentPage.value, Math.max(1, Math.ceil(length / pageSize.value)))
})
watch(snapshotKey, () => {
  snapshotDisplayError.value = false
  const alarm = selectedAlarmRecord.value
  if (alarm?.snapshotUrl) void snapshot.load(alarm.id).catch(() => {})
  else snapshot.clear()
}, { immediate: true })
onBeforeUnmount(snapshot.dispose)

function reloadSnapshot() {
  const alarm = selectedAlarmRecord.value
  if (alarm?.snapshotUrl) {
    snapshotDisplayError.value = false
    void snapshot.load(alarm.id).catch(() => {})
  }
}

function changeStatus(status: AlarmStatus | '') {
  statusFilter.value = status
  const query = { ...route.query }
  if (status) query.status = status
  else delete query.status
  void router.replace({ query })
}
function resetFilters() {
  deviceFilter.value = ''
  typeFilter.value = ''
  changeStatus('')
}
function reloadAlarms() {
  void platform.refreshMeasurements().catch(error => ElMessage.error(error instanceof Error ? error.message : '告警数据加载失败'))
}
function openDetail(id: string) {
  void router.replace({ query: { ...route.query, id } })
}
function closeDetail() {
  const query = { ...route.query }
  delete query.id
  void router.replace({ query })
}
async function handleAlarm(status: 'CONFIRMED' | 'IGNORED') {
  const alarm = selectedAlarm.value
  if (!alarm || alarm.status !== 'OPEN' || pendingAlarmId.value) return
  pendingAlarmId.value = alarm.id
  pendingStatus.value = status
  try {
    await platform.handleAlarm(alarm.id, status)
    ElMessage.success(status === 'CONFIRMED' ? '告警已确认' : '告警已忽略')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '处理失败，请稍后重试')
  } finally {
    pendingAlarmId.value = null
    pendingStatus.value = null
  }
}
</script>

<template>
  <div class="page-stack alarms-page">
    <div class="page-heading">
      <h1>告警中心</h1>
    </div>

    <section class="alarm-summary" aria-label="告警状态统计">
      <button v-for="summary in summaries" :key="summary.status" type="button"
        class="summary-card" :class="[summary.className, { selected: statusFilter === summary.status }]"
        :aria-pressed="statusFilter === summary.status"
        @click="changeStatus(statusFilter === summary.status ? '' : summary.status)">
        <div class="summary-top"><span>{{ summary.label }}</span><span class="summary-icon"><component :is="summary.icon" :size="19" :stroke-width="1.7" /></span></div>
        <div class="summary-value mono">{{ summary.count }}<span>条</span></div>
      </button>
    </section>

    <section class="panel alarm-list" aria-label="告警记录">
      <div class="panel-header">
        <div class="list-title"><h2>告警记录</h2><span class="record-count mono">{{ alarmCountsUnavailable ? '—' : filteredAlarms.length }}</span></div>
      </div>
      <div class="toolbar alarm-toolbar">
        <span class="filter-label"><Filter :size="16" />筛选</span>
        <el-select v-model="deviceFilter" placeholder="全部设备" clearable aria-label="筛选告警设备" class="device-filter">
          <el-option v-for="device in deviceOptions" :key="device.id" :label="device.name" :value="device.id" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable aria-label="筛选告警类型">
          <el-option v-for="type in implementedAlarmTypes" :key="type" :label="typeMeta[type].listLabel" :value="type" />
        </el-select>
        <el-select :model-value="statusFilter" placeholder="全部状态" clearable aria-label="筛选告警状态" @update:model-value="changeStatus">
          <el-option v-for="(meta, status) in statusMeta" :key="status" :label="meta.label" :value="status" />
        </el-select>
        <el-button :disabled="!hasFilters" @click="resetFilters"><RotateCcw :size="14" />重置</el-button>
      </div>

      <div v-loading="alarmInitializing" class="alarm-table-wrap" :aria-busy="alarmInitializing">
        <el-table :data="pageAlarms" class="alarm-table" row-key="id" style="width: 100%">
          <el-table-column label="告警类型" min-width="165">
            <template #default="{ row }"><span class="alarm-type" :class="typeMeta[row.type as AlarmType].className"><AlertTriangle :size="14" />{{ typeMeta[row.type as AlarmType].listLabel }}</span></template>
          </el-table-column>
          <el-table-column label="关联设备" min-width="185">
            <template #default="{ row }"><div class="device-cell"><span>{{ row.deviceName }}</span></div></template>
          </el-table-column>
          <el-table-column label="触发说明" min-width="260" show-overflow-tooltip prop="detail" />
          <el-table-column label="发生时间" min-width="190">
            <template #default="{ row }"><span class="table-date mono">{{ formatDate(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }"><span class="alarm-status" :class="statusMeta[row.status as AlarmStatus].className"><i></i>{{ statusMeta[row.status as AlarmStatus].label }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right" align="right">
            <template #default="{ row }"><el-button link type="primary" :aria-label="`查看 ${row.deviceName} 告警详情`" @click="openDetail(row.id)">查看详情</el-button></template>
          </el-table-column>
          <template #empty>
            <div class="table-empty"><RotateCcw v-if="alarmInitializing" class="loading-icon" :size="36" :stroke-width="1.3" /><AlertTriangle v-else-if="alarmUnavailable" :size="36" :stroke-width="1.3" /><CircleCheck v-else :size="36" :stroke-width="1.3" /><strong>{{ alarmInitializing ? '正在加载告警记录' : alarmUnavailable ? '告警数据加载失败' : hasFilters ? '没有符合条件的告警' : '暂无告警记录' }}</strong><el-button v-if="alarmUnavailable" plain :loading="platform.state.loading" @click="reloadAlarms">重新加载</el-button><el-button v-else-if="hasFilters" plain @click="resetFilters">清除筛选</el-button></div>
          </template>
        </el-table>
      </div>
      <div class="table-footer">
        <span class="muted">共 <span class="mono">{{ alarmCountsUnavailable ? '—' : filteredAlarms.length }}</span> 条记录</span>
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="filteredAlarms.length" :page-sizes="[10, 20, 50]" layout="prev, pager, next, sizes" background hide-on-single-page />
      </div>
    </section>

    <el-dialog v-model="detailVisible" title="告警详情" width="780px" style="max-width: calc(100vw - 32px)" class="alarm-detail-dialog" :close-on-click-modal="!pendingAlarmId" :close-on-press-escape="!pendingAlarmId">
      <template v-if="selectedAlarm">
        <div class="detail-heading">
          <div><span class="alarm-type" :class="typeMeta[selectedAlarm.type].className"><AlertTriangle :size="15" />{{ typeMeta[selectedAlarm.type].listLabel }}</span><h3>{{ selectedAlarm.deviceName }}</h3></div>
          <span class="alarm-status" :class="statusMeta[selectedAlarm.status].className"><i></i>{{ statusMeta[selectedAlarm.status].label }}</span>
        </div>
        <div class="snapshot-box">
          <div v-if="!hasSnapshot" class="snapshot-empty"><CameraOff :size="32" :stroke-width="1.4" /><span>此条告警暂无截图</span></div>
          <div v-else-if="snapshot.loading.value" class="snapshot-empty" role="status"><ImageIcon :size="32" :stroke-width="1.4" /><span>正在加载告警截图…</span></div>
          <div v-else-if="snapshot.error.value" class="snapshot-empty" role="alert"><CameraOff :size="32" :stroke-width="1.4" /><span>{{ snapshot.error.value }}</span><el-button size="small" @click="reloadSnapshot">重新加载</el-button></div>
          <el-image v-else-if="snapshot.url.value" :key="snapshotKey" :src="snapshot.url.value" fit="cover" :alt="`${selectedAlarm.deviceName} 告警截图`" @load="snapshotDisplayError = false" @error="snapshotDisplayError = true">
            <template #error><div class="snapshot-empty" role="alert"><CameraOff :size="32" :stroke-width="1.4" /><span>告警截图暂时无法加载</span><el-button size="small" @click="reloadSnapshot">重新加载</el-button></div></template>
          </el-image>
          <div v-else class="snapshot-empty" role="alert"><CameraOff :size="32" :stroke-width="1.4" /><span>告警截图暂时无法加载</span><el-button size="small" @click="reloadSnapshot">重新加载</el-button></div>
          <span v-if="snapshot.url.value && !snapshotDisplayError" class="snapshot-caption"><ImageIcon :size="13" />告警发生时截图</span>
        </div>
        <div class="trigger-detail"><h4>触发说明</h4><p>{{ selectedAlarm.detail || '暂无补充说明' }}</p></div>
        <dl class="event-dates"><div><dt>发生时间</dt><dd class="mono">{{ formatDate(selectedAlarm.createdAt) }}</dd></div><div><dt>处理时间</dt><dd class="mono">{{ selectedAlarm.status === 'OPEN' ? '尚未处理' : formatDate(selectedAlarm.handledAt) }}</dd></div></dl>
      </template>
      <template #footer>
        <div class="detail-footer">
          <div class="detail-actions">
            <el-button @click="closeDetail">关闭</el-button>
            <template v-if="selectedAlarm?.status === 'OPEN'">
              <el-button :loading="pendingAlarmId === selectedAlarm.id && pendingStatus === 'IGNORED'" :disabled="Boolean(pendingAlarmId)" @click="handleAlarm('IGNORED')"><X v-if="pendingStatus !== 'IGNORED'" :size="15" />忽略告警</el-button>
              <el-button type="primary" :loading="pendingAlarmId === selectedAlarm.id && pendingStatus === 'CONFIRMED'" :disabled="Boolean(pendingAlarmId)" @click="handleAlarm('CONFIRMED')"><Check v-if="pendingStatus !== 'CONFIRMED'" :size="16" />确认告警</el-button>
            </template>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.alarms-page { color: #303d55; }
.alarm-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.summary-card { padding: 16px 20px; text-align: left; font: inherit; color: #303d55; background: #fff; border: 1px solid #e5e9f0; border-radius: 8px; cursor: pointer; transition: border-color .18s, box-shadow .18s; }
.summary-card:hover { border-color: #a3bff1; box-shadow: 0 3px 12px #303d5509; }
.summary-card:focus-visible { outline: 2px solid #4d83e8; outline-offset: 3px; }
.summary-card.selected { border-color: #4d83e8; box-shadow: 0 0 0 1px #4d83e81f; background: #fff; }
.summary-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.summary-top { color: #617087; font-size: 13px; }
.summary-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 8px; background: #edf3ff; color: #4d83e8; }
.open .summary-icon { background: #fff0df; color: #e49a3a; }
.confirmed .summary-icon { background: #edf3ff; color: #4d83e8; }
.ignored .summary-icon { background: #f2edfc; color: #9770d4; }
.summary-value { display: flex; align-items: baseline; gap: 8px; margin: 10px 0 0; font-size: 33px; font-weight: 600; letter-spacing: -1px; }
.summary-value > span { font-family: inherit; font-size: 12px; font-weight: 400; color: #7c889b; letter-spacing: 0; }
.alarm-list { overflow: hidden; border-radius: 8px; }
.list-title { display: flex; align-items: center; gap: 10px; }
.list-title h2 { margin: 0; font-size: 16px; }
.record-count { display: inline-flex; padding: 2px 7px; color: #4d83e8; background: #edf3ff; border-radius: 4px; font-size: 12px; }
.alarm-toolbar { padding: 14px 20px; gap: 12px; border-top: 1px solid #edf0f5; }
.alarm-toolbar .el-select { width: 166px; }
.alarm-toolbar .device-filter { width: 212px; }
.alarm-toolbar .el-button { margin-left: auto; }
.alarm-toolbar .el-button :deep(span), .detail-actions .el-button :deep(span) { display: inline-flex; align-items: center; gap: 5px; }
.filter-label { display: flex; align-items: center; gap: 7px; margin-right: 5px; color: #78869b; font-size: 12px; }
.alarm-table-wrap { overflow: auto; }
.alarm-table { min-width: 1010px; }
.alarm-table :deep(th.el-table__cell) { background: #f6f8fc; color: #6d7b91; font-size: 12px; font-weight: 500; height: 42px; border-bottom-color: #e9edf4; }
.alarm-table :deep(td.el-table__cell) { height: 62px; border-bottom-color: #edf0f5; color: #526078; font-size: 13px; }
.alarm-table :deep(.cell) { padding-left: 20px; padding-right: 20px; }
.alarm-table :deep(.el-table__inner-wrapper::before) { display: none; }
.alarm-type { display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: 4px; font-size: 12px; white-space: nowrap; line-height: 1.4; }
.alarm-type.debris { color: #bf8025; background: #fff5e5; }
.alarm-type.occlusion { color: #8b69bd; background: #f3edfc; }
.alarm-type.channel-full { color: #cc6260; background: #fff0ee; }
.alarm-type.collapse { color: #7b879a; background: #f0f2f6; }
.device-cell { display: flex; flex-direction: column; gap: 4px; color: #303d55; font-weight: 500; }
.table-date { font-size: 12px; color: #748299; white-space: nowrap; }
.alarm-status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; white-space: nowrap; }
.alarm-status i { display: block; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.alarm-status.open { color: #c48d37; }
.alarm-status.confirmed { color: #4d83e8; }
.alarm-status.ignored { color: #8a95a7; }
.table-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 14px; padding: 14px 20px; border-top: 1px solid #edf0f5; font-size: 12px; }
.table-footer :deep(.el-pagination) { --el-pagination-button-height: 28px; --el-pagination-button-width: 28px; }
.table-footer :deep(.el-pagination__sizes) { margin-left: 12px; margin-right: 0; }
.table-empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 42px 20px; color: #91a4c1; line-height: 1.5; }
.table-empty strong { margin-top: 5px; color: #52647f; font-size: 14px; font-weight: 500; }
.table-empty > span { font-size: 12px; }
.table-empty .el-button { margin-top: 5px; }
.table-empty .loading-icon { animation: alarm-loading-spin 1.2s linear infinite; }
@keyframes alarm-loading-spin { to { transform: rotate(360deg); } }
.detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.detail-heading h3 { margin: 9px 0 0; color: #303d55; font-size: 18px; font-weight: 600; }
.snapshot-box { position: relative; height: 220px; border-radius: 8px; overflow: hidden; background: #f3f5f9; }
.snapshot-box .el-image { display: block; width: 100%; height: 100%; }
.snapshot-empty { display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 12px; width: 100%; height: 100%; min-height: 180px; color: #8798b0; font-size: 13px; }
.snapshot-caption { position: absolute; left: 12px; bottom: 12px; display: flex; align-items: center; gap: 6px; padding: 5px 8px; border-radius: 4px; color: #fff; background: #253652cc; font-size: 12px; }
.trigger-detail h4 { margin: 0; color: #465773; font-size: 13px; font-weight: 500; }
.trigger-detail { padding: 14px 0; }
.trigger-detail p { margin: 7px 0 0; color: #68778e; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.event-dates { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px 24px; margin: 0; padding: 13px 0 0; border-top: 1px solid #e9edf4; font-size: 12px; }
.event-dates > div { display: flex; gap: 12px; }
.event-dates dt { color: #8290a4; }
.event-dates dd { margin: 0; color: #62728a; }
.detail-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.detail-actions { display: flex; gap: 8px; }
.detail-actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 1100px) {
  .alarm-toolbar { flex-wrap: wrap; }
  .filter-label { display: none; }
  .alarm-toolbar .device-filter { width: 190px; }
  .alarm-toolbar .el-select { flex: 1; min-width: 145px; }
  .summary-card { padding: 16px; }
}
@media (max-width: 680px) {
  .alarm-summary { gap: 9px; }
  .summary-card { padding: 12px; border-radius: 8px; }
  .summary-top { font-size: 12px; }
  .summary-icon { display: none; }
  .summary-value { margin: 10px 0 0; font-size: 29px; gap: 4px; }
  .alarm-toolbar { padding: 14px; gap: 9px; }
  .alarm-toolbar .el-select { flex-basis: calc(50% - 9px); min-width: 120px; }
  .alarm-toolbar .el-button { flex: 1; margin-left: 0; }
  .table-footer { padding: 14px; }
  .snapshot-box { height: 190px; }
  .detail-actions { margin-left: auto; gap: 5px; }
  .detail-actions .el-button { padding: 8px 11px; }
}
</style>
