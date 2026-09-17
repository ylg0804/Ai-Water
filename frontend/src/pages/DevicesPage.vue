<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Camera, CircleHelp, MapPin, Pencil, Plus, Radio, Search, ShieldCheck, Trash2 } from '@lucide/vue'
import { platform } from '../services/platform/store'
import type { Device, DeviceDraft } from '../domain/models'
import { DEVICE_INTEGER_MAX, deviceDisplayName, deviceSecondaryLabel, isStandardDefinitionOnly } from '../domain/device'
import { ALARM_FIELDS, PARAM_FIELDS, emptyParameterValues, parseParams, serializeParams, validateParams } from '../domain/deviceParameters'

const route = useRoute()
const search = ref(typeof route.query.search === 'string' ? route.query.search : '')
const enabledFilter = ref('all')
const connectionFilter = ref('all')
const page = ref(1)
watch(() => [route.query.search, route.query.request], ([value]) => {
  search.value = typeof value === 'string' ? value : ''
  enabledFilter.value = 'all'
  connectionFilter.value = 'all'
  page.value = 1
})
const pageSize = 8
const filteredDevices = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return platform.state.devices.filter((device) => {
    const matchesText = !query || [device.name, device.serial, device.location].some((value) => value.toLocaleLowerCase().includes(query))
    const matchesEnabled = enabledFilter.value === 'all' || String(device.status) === enabledFilter.value
    const matchesConnection = connectionFilter.value === 'all' || String(device.online) === connectionFilter.value
    return matchesText && matchesEnabled && matchesConnection
  })
})
const visibleDevices = computed(() => filteredDevices.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const enabledCount = computed(() => platform.state.devices.filter((device) => device.status === 1).length)
const onlineCount = computed(() => platform.state.devices.some((device) => device.online !== null)
  ? platform.state.devices.filter((device) => device.online === true).length
  : null)
const deviceUnavailable = computed(() => !platform.state.ready && platform.state.connection === 'error')
const filtersActive = computed(() => Boolean(search.value || enabledFilter.value !== 'all' || connectionFilter.value !== 'all'))
watch([search, enabledFilter, connectionFilter], () => { page.value = 1 })
watch(() => filteredDevices.value.length, (length) => { page.value = Math.min(page.value, Math.max(1, Math.ceil(length / pageSize))) })

function clearFilters() {
  search.value = ''
  enabledFilter.value = 'all'
  connectionFilter.value = 'all'
}
function reloadDevices() {
  void platform.refreshMeasurements().catch(error => ElMessage.error(error instanceof Error ? error.message : '设备列表加载失败'))
}

const dialogVisible = ref(false)
const editId = ref<string>()
const formRef = ref<FormInstance>()
const activeTab = ref('basic')
const saving = ref(false)
const parameterError = ref('')
const parameterValues = ref<Record<string, string>>({})
const extraParams = ref('')
const expandedSections = ref<string[]>([])
const formBody = ref<HTMLElement>()
const knownKeys = new Set([...PARAM_FIELDS, ...ALARM_FIELDS].map((field) => field.key))
const parameterGroups = [...new Set(PARAM_FIELDS.map((field) => field.group))].map((name) => ({
  name,
  fields: PARAM_FIELDS.filter((field) => field.group === name),
}))
const draft = reactive<DeviceDraft>({
  serial: '', name: '', location: '', channelNo: 1, hd: 0,
  inferIntervalSec: 60, status: 1, deviceOtherParams: '',
})
const standardDefinitionOnly = computed(() => isStandardDefinitionOnly(draft.serial))
watch(() => draft.serial, () => { if (standardDefinitionOnly.value) draft.hd = 0 })

const rules: FormRules<DeviceDraft> = {
  serial: [
    { required: true, whitespace: true, message: '请输入设备序列号', trigger: 'blur' },
    { max: 64, message: '序列号最多 64 个字符', trigger: 'blur' },
    { validator: (_rule, value: string, callback) => {
      const duplicate = platform.state.devices.some((device) => device.id !== editId.value && device.serial.toLocaleLowerCase() === value.trim().toLocaleLowerCase())
      callback(duplicate ? new Error('该序列号已存在，请勿重复添加') : undefined)
    }, trigger: 'blur' },
  ],
  name: [{ max: 128, message: '设备名称最多 128 个字符', trigger: 'blur' }],
  location: [{ max: 256, message: '安装位置最多 256 个字符', trigger: 'blur' }],
  channelNo: [{ required: true, type: 'integer', min: 1, max: DEVICE_INTEGER_MAX, message: '通道号超出有效范围', trigger: 'change' }],
  inferIntervalSec: [{ required: true, type: 'integer', min: 1, max: DEVICE_INTEGER_MAX, message: '运行周期超出有效范围', trigger: 'change' }],
}

async function openEditor(device?: Device) {
  let values: Record<string, string>
  try {
    values = device ? parseParams(device.deviceOtherParams) : emptyParameterValues()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '设备参数无法读取')
    return
  }
  editId.value = device?.id
  Object.assign(draft, {
    serial: device?.serial ?? '', name: device?.name ?? '', location: device?.location ?? '',
    channelNo: device?.channelNo ?? 1, hd: device?.hd ?? 0,
    inferIntervalSec: device?.inferIntervalSec ?? 60, status: device?.status ?? 1,
    deviceOtherParams: device?.deviceOtherParams ?? '',
  })
  parameterValues.value = {
    ...emptyParameterValues(),
    ...Object.fromEntries(Object.entries(values).filter(([key]) => knownKeys.has(key))),
  }
  extraParams.value = Object.entries(values).filter(([key]) => !knownKeys.has(key)).map(([key, value]) => `${key}=${value}`).join('\n')
  parameterError.value = ''
  activeTab.value = 'basic'
  expandedSections.value = []
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
  formBody.value?.scrollTo({ top: 0 })
}

function collectParams(): string {
  const values: Record<string, string> = Object.create(null)
  for (const field of PARAM_FIELDS) values[field.key] = parameterValues.value[field.key]?.trim() ?? ''
  for (const field of ALARM_FIELDS) {
    const value = parameterValues.value[field.key]?.trim()
    if (value) values[field.key] = value
  }
  const extras = parseParams(extraParams.value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).join(','))
  for (const [key, value] of Object.entries(extras)) {
    if (knownKeys.has(key)) throw new Error(`${key} 已有专用输入框，请勿在扩展参数中重复添加。`)
    values[key] = value
  }
  return serializeParams(values)
}

async function save() {
  if (saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    activeTab.value = 'basic'
    await nextTick()
    formBody.value?.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  parameterError.value = ''
  try {
    const params = collectParams()
    const error = validateParams(params)
    if (error) throw new Error(error)
    draft.deviceOtherParams = params
  } catch (error) {
    parameterError.value = error instanceof Error ? error.message : '请检查算法参数'
    activeTab.value = 'parameters'
    await nextTick()
    formBody.value?.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  saving.value = true
  try {
    await platform.saveDevice({ ...draft, serial: draft.serial.trim(), name: draft.name.trim(), location: draft.location.trim() }, editId.value)
    ElMessage.success(editId.value ? '设备配置已保存' : '设备已添加')
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

async function removeDevice(device: Device) {
  try {
    await ElMessageBox.confirm(`确定删除「${device.name || device.serial}」吗？`, '删除设备', {
      confirmButtonText: '删除设备', cancelButtonText: '取消', type: 'warning',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  try {
    await platform.deleteDevice(device.id)
    ElMessage.success('设备已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败，请重试')
  }
}

const samplingSeconds = computed(() => {
  const fps = Number(parameterValues.value.fps)
  const frameStep = Number(parameterValues.value.frame_step)
  return fps > 0 && frameStep > 0 && Number.isFinite(fps) && Number.isFinite(frameStep)
    ? `${Number((frameStep / fps).toPrecision(6))} 秒` : '—'
})
</script>

<template>
  <div class="page-stack devices-page">
    <header class="page-heading">
      <h1>设备管理</h1>
      <el-button type="primary" size="large" :icon="Plus" @click="openEditor()">添加设备</el-button>
    </header>

    <div class="device-stats">
      <section class="panel stat-card">
        <span class="stat-icon"><Camera :size="21" /></span>
        <div><span class="muted stat-label">全部设备</span><p class="stat-number">{{ platform.state.ready ? platform.state.devices.length : '—' }} <span>台</span></p></div>
      </section>
      <section class="panel stat-card">
        <span class="stat-icon enabled-icon"><ShieldCheck :size="21" /></span>
        <div><span class="muted stat-label">已启用</span><p class="stat-number">{{ platform.state.ready ? enabledCount : '—' }} <span>台</span></p></div>
      </section>
      <section class="panel stat-card">
        <span class="stat-icon connection-icon"><Radio :size="21" /></span>
        <div><span class="muted stat-label">近期采样正常</span><p class="stat-number">{{ platform.state.ready ? onlineCount ?? '—' : '—' }} <span>台</span></p></div>
      </section>
    </div>

    <section class="panel device-panel">
      <div class="panel-header">
        <div class="list-title"><h2>设备列表</h2><span class="count-badge">{{ platform.state.ready ? filteredDevices.length : '—' }}</span></div>
      </div>
      <div class="toolbar device-toolbar">
        <el-input v-model="search" class="search-field" placeholder="搜索设备名称、序列号或位置" :prefix-icon="Search" clearable aria-label="搜索设备" />
        <el-select v-model="enabledFilter" class="filter-select" aria-label="启用状态筛选">
          <el-option label="全部启用状态" value="all" /><el-option label="已启用" value="1" /><el-option label="已停用" value="0" />
        </el-select>
        <el-select v-model="connectionFilter" class="filter-select" aria-label="采样状态筛选">
          <el-option label="全部采样状态" value="all" /><el-option label="近期采样正常" value="true" /><el-option label="近期无成功采样" value="false" /><el-option label="尚无采样状态" value="null" />
        </el-select>
        <el-button v-if="filtersActive" text @click="clearFilters">重置筛选</el-button>
      </div>

      <el-table :data="visibleDevices" class="device-table" row-key="id" :header-cell-style="{ background: '#f7f9fa', color: '#687982', fontWeight: '500' }">
        <el-table-column label="设备" min-width="240">
          <template #default="{ row }: { row: Device }">
            <div class="device-cell"><span class="camera-icon"><Camera :size="19" /></span><div><span class="device-name">{{ deviceDisplayName(row) }}</span><span v-if="deviceSecondaryLabel(row)" class="mono serial">{{ deviceSecondaryLabel(row) }}</span></div></div>
          </template>
        </el-table-column>
        <el-table-column label="安装位置" min-width="195">
          <template #default="{ row }: { row: Device }"><span class="location"><MapPin :size="14" />{{ row.location || '未填写' }}</span></template>
        </el-table-column>
        <el-table-column label="视频配置" min-width="145">
          <template #default="{ row }: { row: Device }"><span>通道 {{ row.channelNo }}</span><span class="quality-label">{{ row.hd ? '高清' : '标清' }}</span></template>
        </el-table-column>
        <el-table-column label="运行周期" min-width="112">
          <template #default="{ row }: { row: Device }"><span class="mono">{{ row.inferIntervalSec }}</span><span class="muted"> 秒</span></template>
        </el-table-column>
        <el-table-column label="启用状态" width="110">
          <template #default="{ row }: { row: Device }"><el-tag :type="row.status ? 'success' : 'info'" effect="plain" size="small">{{ row.status ? '已启用' : '已停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="采样状态" min-width="155">
          <template #default="{ row }: { row: Device }"><span class="connection-status" :class="{ offline: !row.online }"><i class="device-status-dot" />{{ row.online === null ? '尚无采样状态' : row.online ? '近期采样正常' : '近期无成功采样' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }: { row: Device }">
            <el-button link type="primary" :icon="Pencil" :aria-label="`编辑 ${row.name || row.serial}`" @click="openEditor(row)">编辑</el-button>
            <el-button link type="danger" :icon="Trash2" :aria-label="`删除 ${row.name || row.serial}`" @click="removeDevice(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="deviceUnavailable ? '设备列表加载失败' : !platform.state.ready ? '正在加载设备' : filtersActive ? '没有符合筛选条件的设备' : '还没有添加监测设备'" :image-size="85">
            <el-button v-if="deviceUnavailable" :loading="platform.state.loading" @click="reloadDevices">重新加载</el-button>
            <el-button v-else-if="filtersActive" @click="clearFilters">重置筛选</el-button>
            <el-button v-else-if="platform.state.ready" type="primary" :icon="Plus" @click="openEditor()">添加第一台设备</el-button>
          </el-empty>
        </template>
      </el-table>
      <div class="table-footer">
        <span class="muted">共 {{ platform.state.ready ? filteredDevices.length : '—' }} 台设备</span>
        <el-pagination v-if="filteredDevices.length > pageSize" v-model:current-page="page" :page-size="pageSize" :total="filteredDevices.length" layout="prev, pager, next" small background />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editId ? '编辑设备' : '添加设备'" width="960px" class="device-dialog" style="max-width: calc(100vw - 28px)" :close-on-click-modal="false" destroy-on-close :close-on-press-escape="!saving" :show-close="!saving">
      <el-form ref="formRef" :model="draft" :rules="rules" label-position="top" :disabled="saving" @submit.prevent="save">
        <el-tabs v-model="activeTab" class="editor-tabs">
          <el-tab-pane label="基础信息" name="basic" />
          <el-tab-pane label="算法参数 · 21" name="parameters" />
        </el-tabs>
        <div ref="formBody" class="form-scroll">
          <div v-show="activeTab === 'basic'" class="basic-fields">
            <div class="form-grid">
              <el-form-item label="设备序列号" prop="serial"><el-input v-model="draft.serial" placeholder="请输入设备序列号" maxlength="64" /></el-form-item>
              <el-form-item label="设备名称（可选）" prop="name"><el-input v-model="draft.name" placeholder="未填写时显示设备序列号" maxlength="128" /></el-form-item>
              <el-form-item label="安装位置" prop="location" class="full-width"><el-input v-model="draft.location" placeholder="填写渠道、桩号或现场位置" maxlength="256" /></el-form-item>
              <el-form-item label="视频通道号" prop="channelNo"><el-input-number v-model="draft.channelNo" :min="1" :max="DEVICE_INTEGER_MAX" :step="1" :precision="0" controls-position="right" /></el-form-item>
              <el-form-item label="视频画质" prop="hd"><el-radio-group v-model="draft.hd"><el-radio-button :value="0">标清</el-radio-button><el-radio-button :value="1" :disabled="standardDefinitionOnly">高清</el-radio-button></el-radio-group><p v-if="standardDefinitionOnly" class="field-help">该设备仅支持标清码流</p></el-form-item>
              <el-form-item label="运行周期（秒）" prop="inferIntervalSec"><el-input-number v-model="draft.inferIntervalSec" :min="1" :max="DEVICE_INTEGER_MAX" :step="1" :precision="0" controls-position="right" /><p class="field-help">默认值：60 秒</p></el-form-item>
              <el-form-item label="启用设备" prop="status"><el-switch v-model="draft.status" :active-value="1" :inactive-value="0" /><span class="switch-label">{{ draft.status ? '已启用' : '已停用' }}</span></el-form-item>
            </div>
          </div>

          <div v-show="activeTab === 'parameters'" class="parameter-fields">
            <el-alert v-if="parameterError" :title="parameterError" type="error" show-icon :closable="false" class="parameter-error" />
            <div class="parameter-notice"><CircleHelp :size="17" /><span>21 项均为必填。ROI 使用 640×640 画布坐标；请按现场标定结果填写。小数按输入值保存。</span></div>
            <section v-for="group in parameterGroups" :key="group.name" class="parameter-group">
              <h3>{{ group.name }}<span v-if="group.name === '视频与采样'" class="sampling-hint">当前帧对时间间隔 {{ samplingSeconds }}</span></h3>
              <div class="form-grid">
                <div v-for="field in group.fields" :key="field.key" class="parameter-field">
                  <label :for="`param-${field.key}`">{{ field.label }} <span class="required-marker">*</span><code>{{ field.key }}</code></label>
                  <el-input :id="`param-${field.key}`" v-model="parameterValues[field.key]" inputmode="decimal" :aria-label="`${group.name} ${field.label} ${field.key}`"><template #append>{{ field.unit }}</template></el-input>
                  <p class="field-help">{{ field.description }}</p>
                </div>
              </div>
            </section>

            <el-collapse v-model="expandedSections" class="optional-sections">
              <el-collapse-item name="alarms" title="可选告警阈值">
                <div class="form-grid">
                  <div v-for="field in ALARM_FIELDS" :key="field.key" class="parameter-field">
                    <label :for="`param-${field.key}`">{{ field.label }}<code>{{ field.key }}</code></label>
                    <el-input :id="`param-${field.key}`" v-model="parameterValues[field.key]" inputmode="decimal" placeholder="请输入现场参数" :aria-label="`${field.label} ${field.key}`" clearable><template #append>{{ field.unit }}</template></el-input>
                    <p class="field-help">{{ field.description }}</p>
                  </div>
                </div>
              </el-collapse-item>
              <el-collapse-item name="extensions" :title="`扩展参数${extraParams.trim() ? ' · 已填写' : ''}`">
                <el-input v-model="extraParams" type="textarea" :rows="4" aria-label="扩展参数" placeholder="每行填写 key=value" class="extra-input" />
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </el-form>
      <template #footer>
        <div class="dialog-footer"><el-button :disabled="saving" @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ editId ? '保存修改' : '添加设备' }}</el-button></div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.devices-page { gap: 24px; }
.page-heading h1 { margin: 4px 0 8px; }
.page-heading p { margin-bottom: 0; }
.device-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.stat-card { display: flex; align-items: center; gap: 17px; padding: 22px 24px; }
.stat-icon { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 12px; color: #087f83; background: #eaf6f5; }
.enabled-icon { color: #518b66; background: #f0f7ee; }
.connection-icon { color: #6a79a5; background: #f0f2f9; }
.stat-label { font-size: 13px; }
.stat-number { margin: 6px 0 0; line-height: 1; font-size: 27px; font-weight: 650; letter-spacing: -1px; }
.stat-number span { margin-left: 3px; font-size: 12px; color: #8b999f; font-weight: 400; letter-spacing: 0; }
.device-panel { overflow: hidden; }
.list-title { display: flex; align-items: center; gap: 10px; }
.list-title h2 { margin: 0; font-size: 16px; }
.count-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 23px; padding: 3px 7px; border-radius: 6px; background: #f0f4f5; color: #637780; font-size: 12px; }
.device-toolbar { padding: 0 24px 20px; display: flex; gap: 12px; flex-wrap: wrap; }
.search-field { width: 320px; max-width: 100%; }
.filter-select { width: 155px; }
.device-table { width: 100%; }
.device-table :deep(.el-table__cell) { padding: 19px 0; }
.device-table :deep(.el-table__cell:first-child .cell) { padding-left: 24px; }
.device-table :deep(.el-table__header .el-table__cell) { padding: 13px 0; }
.device-table :deep(.el-button + .el-button) { margin-left: 12px; }
.device-cell { display: flex; align-items: center; gap: 12px; }
.camera-icon { flex: 0 0 38px; height: 38px; display: grid; place-items: center; background: #edf6f5; border: 1px solid #e2eeec; color: #277d80; border-radius: 9px; }
.device-name { display: block; font-weight: 600; color: #263f49; line-height: 1.6; }
.serial { display: block; color: #8b999f; font-size: 11px; line-height: 1.7; }
.location { display: flex; align-items: center; gap: 5px; color: #637680; }
.location svg { flex-shrink: 0; color: #91a0a7; }
.quality-label { margin-left: 8px; color: #8a979e; font-size: 12px; }
.connection-status { display: inline-flex; align-items: center; gap: 7px; color: #41866a; font-size: 12px; }
.device-status-dot { width: 6px; height: 6px; border-radius: 50%; background: #4a9b76; }
.connection-status.offline { color: #8a969d; }
.offline .device-status-dot { background: #a9b1b6; }
.table-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 60px; padding: 14px 24px; font-size: 12px; }
.form-scroll { max-height: min(60vh, 600px); overflow-y: auto; overscroll-behavior: contain; padding: 4px 12px 12px 2px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }
.full-width { grid-column: 1 / -1; }
.basic-fields { padding-top: 8px; }
.basic-fields :deep(.el-input-number) { width: 100%; }
.field-help { width: 100%; margin: 7px 0 0; color: #87959c; font-size: 11px; line-height: 1.65; }
.switch-label { margin-left: 12px; font-size: 13px; color: #657b84; }
.parameter-notice { display: flex; align-items: flex-start; gap: 9px; padding: 12px 14px; margin-bottom: 17px; background: #fff8eb; color: #9a7c42; border-radius: 7px; font-size: 12px; line-height: 1.7; }
.parameter-notice svg { flex-shrink: 0; margin-top: 2px; }
.parameter-error { margin-bottom: 12px; }
.parameter-group { margin-bottom: 22px; }
.parameter-group h3 { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin: 0 0 14px; padding-bottom: 11px; border-bottom: 1px solid #edf1f2; font-size: 13px; font-weight: 600; color: #314f5b; }
.sampling-hint { font-size: 11px; font-weight: 400; color: #087f83; }
.parameter-field { margin-bottom: 16px; }
.parameter-field label { display: block; margin-bottom: 7px; font-size: 12px; color: #536b76; }
.parameter-field code { display: block; margin-top: 3px; font-size: 10px; line-height: 1.6; color: #94a0a7; }
.parameter-field :deep(.el-input__inner) { font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; }
.parameter-field :deep(.el-input-group__append) { padding: 0 11px; font-size: 11px; }
.required-marker { color: #d8857b; }
.optional-sections { margin-top: 3px; }
.optional-sections :deep(.el-collapse-item__header) { color: #46606c; font-weight: 500; }
.extra-input :deep(textarea) { font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; }
.dialog-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding-top: 7px; }
.dialog-footer .el-button + .el-button { margin-left: 0; }
@media (max-width: 760px) {
  .devices-page { gap: 18px; }
  .device-stats { gap: 9px; }
  .stat-card { gap: 10px; padding: 17px 13px; }
  .stat-icon { width: 34px; height: 34px; border-radius: 9px; }
  .stat-number { font-size: 24px; }
  .stat-label { font-size: 11px; }
  .device-toolbar { padding: 0 16px 16px; gap: 9px; }
  .search-field { width: 100%; }
  .filter-select { width: calc(50% - 5px); }
  .table-footer { padding: 12px 16px; }
  .form-grid { grid-template-columns: 1fr; }
  .form-scroll { max-height: 56vh; padding-right: 4px; }
}
@media (max-width: 420px) {
  .stat-icon { display: none; }
  .stat-card { padding: 16px; }
}
</style>
