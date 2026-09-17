<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import { ArrowDownToLine, CalendarDays, Info, Search, SlidersHorizontal } from '@lucide/vue'
import TrendChart from '../components/TrendChart.vue'
import { useHistoryQuery } from '../composables/useHistoryQuery'
import { platform } from '../services/platform/store'
import { formatDate, formatNumber, metricMeta } from '../utils/format'

const history = useHistoryQuery({ devices: computed(() => platform.state.devices) })
const { deviceId, dateRange, applied, rows, metric, currentPage, pageSize, loading, exporting, error, deviceOptions, hasUnappliedChanges, pagedRows, reportedValues, metricAverage, emptyDescription, query, reset, exportRows, shortcuts } = history
const resultCount = computed(() => loading.value || (Boolean(error.value) && !rows.value.length) ? '—' : rows.value.length.toLocaleString('zh-CN'))
onBeforeUnmount(history.dispose)
</script>

<template>
  <div class="page-stack history-page">
    <div class="page-heading"><h1>历史数据</h1><el-button :disabled="!rows.length || loading" :loading="exporting" class="export-button" @click="exportRows"><ArrowDownToLine :size="16" />导出 CSV</el-button></div>
    <section class="panel filter-panel" aria-label="历史数据筛选">
      <div class="filter-title"><SlidersHorizontal :size="16" /><h2>查询条件</h2></div>
      <form class="history-filters" @submit.prevent="query">
        <div class="filter-field device-field"><label for="history-device">监测设备</label><el-select id="history-device" v-model="deviceId" placeholder="全部设备" filterable aria-label="监测设备"><el-option label="全部设备" value="" /><el-option v-for="device in deviceOptions" :key="device.id" :label="device.name" :value="device.id" /></el-select></div>
        <div class="filter-field date-field"><label for="history-date-range">观测时间</label><el-date-picker id="history-date-range" v-model="dateRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" format="YYYY-MM-DD HH:mm" :shortcuts="shortcuts" :editable="false" popper-class="history-date-popper" aria-label="观测时间范围" /></div>
        <div class="filter-actions"><el-button type="primary" native-type="submit" :loading="loading"><Search :size="16" />查询</el-button><el-button :disabled="loading" @click="reset">重置</el-button></div>
      </form>
      <p v-if="error" class="query-error" role="alert">{{ error }}</p><p v-else-if="hasUnappliedChanges" class="pending-filter"><Info :size="14" />筛选条件已更改，请重新查询。</p>
    </section>
    <section v-loading="loading" class="panel trend-panel" aria-label="历史趋势">
      <div class="panel-header trend-heading"><div><h2>水情趋势</h2><p class="muted applied-range"><CalendarDays :size="14" />{{ formatDate(applied.start).slice(0, 16) }}<span>—</span>{{ formatDate(applied.end).slice(0, 16) }}</p></div><div class="average-stat"><span class="muted">区间平均{{ metricMeta[metric].label }}</span><strong class="mono">{{ formatNumber(metricAverage) }}<small>{{ metricMeta[metric].unit }}</small></strong></div></div>
      <div class="trend-controls"><el-tabs v-model="metric" class="metric-tabs" aria-label="趋势指标"><el-tab-pane v-for="(meta, key) in metricMeta" :key="key" :label="meta.label" :name="key" /></el-tabs><span class="chart-unit">单位：{{ metricMeta[metric].unit }}</span></div>
      <TrendChart v-if="rows.length && reportedValues.length" :rows="rows" :metric="metric" :height="280" /><el-empty v-else :description="rows.length ? '该时间范围内暂无此指标的有效测量值。' : emptyDescription" :image-size="88" class="chart-empty" />
    </section>
    <section v-loading="loading" class="panel detail-panel" aria-label="历史数据明细">
      <div class="panel-header detail-heading"><div class="detail-title"><h2>观测明细</h2><span class="record-count">{{ resultCount }} 条记录</span></div></div>
      <div class="table-scroll"><el-table :data="pagedRows" row-key="id" class="history-table" :empty-text="emptyDescription"><el-table-column label="观测时间" min-width="192"><template #default="{ row }"><span class="mono table-time">{{ formatDate(row.measuredAt) }}</span></template></el-table-column><el-table-column prop="deviceName" label="监测设备" min-width="180" show-overflow-tooltip /><el-table-column label="水位 (m)" min-width="126" align="right"><template #default="{ row }"><span class="mono">{{ formatNumber(row.waterLevel) }}</span></template></el-table-column><el-table-column label="流速 (m/s)" min-width="126" align="right"><template #default="{ row }"><span class="mono">{{ formatNumber(row.velocity) }}</span></template></el-table-column><el-table-column label="流量 (m³/s)" min-width="140" align="right"><template #default="{ row }"><span class="mono">{{ formatNumber(row.discharge) }}</span></template></el-table-column></el-table></div>
      <div class="pagination-footer"><el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="rows.length" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" background :pager-count="5" @size-change="currentPage = 1" /></div>
    </section>
  </div>
</template>

<style scoped>
.history-page { --history-muted: #8490a4; color: #303d55; }.export-button, .filter-actions, .trend-controls, .pagination-footer, .panel-header, .history-filters, .filter-title { display: flex; align-items: center; gap: 10px; }.filter-panel, .detail-panel { padding: 18px 20px; }.filter-title { color: #4d83e8; margin-bottom: 18px; }.filter-title h2 { color: #303d55; margin: 0; }.history-filters { align-items: flex-end; }.filter-field { display: flex; flex-direction: column; gap: 8px; }.device-field { width: 235px; }.date-field { flex: 1; max-width: 495px; }.date-field :deep(.el-date-editor) { width: 100%; }.filter-actions { align-self: flex-end; min-height: 36px; }.filter-actions :deep(.el-button) { height: 36px; margin-left: 0; }.query-error { color: #c04a43; }.pending-filter, .applied-range { display: flex; align-items: center; gap: 7px; }.query-error, .pending-filter { margin: 15px 0 0; font-size: 12px; }.trend-panel { padding-bottom: 0; overflow: hidden; }.trend-heading, .detail-heading { justify-content: space-between; }.average-stat { text-align: right; }.average-stat strong { display: block; color: #4d83e8; font-size: 25px; }.average-stat small { margin-left: 5px; color: var(--history-muted); font-size: 12px; font-weight: 400; }.trend-controls { padding: 0 20px; }.metric-tabs { min-width: 244px; }.chart-unit, .muted { color: var(--history-muted); }.chart-empty { height: 280px; }.detail-title { display: flex; align-items: center; gap: 11px; }.record-count { background: #edf3ff; color: #4d83e8; border-radius: 4px; padding: 3px 7px; font-size: 12px; }.table-scroll { overflow-x: auto; }.history-table { min-width: 764px; }.pagination-footer { justify-content: flex-end; padding-top: 15px; font-size: 12px; }@media (max-width: 650px) { .history-filters, .pagination-footer { flex-wrap: wrap; }.filter-field, .device-field { width: 100%; }.filter-actions { width: 100%; justify-content: flex-end; }.average-stat { margin-left: auto; } }
</style>
