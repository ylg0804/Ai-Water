import { computed, ref, type Ref } from 'vue'
import type { Device, HistoryFilter, Measurement, Metric } from '../domain/models'
import { deviceDisplayName } from '../domain/device'
import { exportDeviceHistory } from '../services/api/measurements'
import { platform } from '../services/platform/store'
import { formatDate } from '../utils/format'

type HistoryPort = (filter: HistoryFilter, signal?: AbortSignal) => Promise<Measurement[]>
type ExportPort = (device: Device, filter: HistoryFilter, signal?: AbortSignal) => Promise<Blob>
type DownloadPort = (file: Blob, filename: string) => void

export interface HistoryQueryOptions {
  devices: Ref<readonly Device[]>
  history?: HistoryPort
  exportHistory?: ExportPort
  clock?: () => Date
  download?: DownloadPort
}

function recentRange(clock: () => Date, days = 1): [Date, Date] {
  const end = clock()
  return [new Date(end.getTime() - days * 86_400_000), end]
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback
}

function sameDate(left: Date, right: string | Date): boolean {
  return left.getTime() === new Date(right).getTime()
}

function stripBom(bytes: Uint8Array): Uint8Array {
  return bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? bytes.slice(3) : bytes
}

function firstLine(bytes: Uint8Array): { header: Uint8Array; body: Uint8Array } {
  const newline = bytes.indexOf(10)
  if (newline < 0) throw new Error('导出文件格式不正确。')
  return { header: bytes.slice(0, newline + 1), body: bytes.slice(newline + 1) }
}

function comparableHeader(header: Uint8Array): Uint8Array {
  return header[header.length - 2] === 13 ? header.slice(0, -2) : header.slice(0, -1)
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function mergeCsv(files: Blob[]): Promise<Blob> {
  if (!files.length) throw new Error('没有可导出的设备，请重新查询。')
  const parts = (await Promise.all(files.map(async file => firstLine(stripBom(new Uint8Array(await file.arrayBuffer()))))))
  const header = parts[0].header
  const expectedHeader = comparableHeader(header)
  if (!parts.every(part => equalBytes(comparableHeader(part.header), expectedHeader))) {
    throw new Error('设备导出文件的列不一致，请分别导出。')
  }
  const lineBreak = new Uint8Array([13, 10])
  const content: BlobPart[] = [new Uint8Array([0xef, 0xbb, 0xbf]), header]
  for (const part of parts) {
    content.push(part.body)
    if (part.body.length && part.body[part.body.length - 1] !== 10) content.push(lineBreak)
  }
  return new Blob(content, { type: 'text/csv;charset=utf-8' })
}

export function useHistoryQuery(options: HistoryQueryOptions) {
  const clock = options.clock ?? (() => new Date())
  const history = options.history ?? ((filter, signal) => platform.queryHistory(filter, signal))
  const exportHistory = options.exportHistory ?? ((device, filter, signal) => exportDeviceHistory(device, filter, undefined, signal))
  const deviceId = ref('')
  const initialRange = recentRange(clock)
  const dateRange = ref<[Date, Date] | null>(initialRange)
  const applied = ref<HistoryFilter>({ deviceId: '', start: initialRange[0], end: initialRange[1] })
  const rows = ref<Measurement[]>([])
  const metric = ref<Metric>('waterLevel')
  const currentPage = ref(1)
  const pageSize = ref(10)
  const loading = ref(false)
  const exporting = ref(false)
  const error = ref('')
  const queryFailed = ref(false)
  const appliedDeviceIds = ref<string[]>([])
  const objectUrls = new Set<string>()
  const objectUrlTimers = new Map<string, ReturnType<typeof setTimeout>>()
  let querySequence = 0
  let disposed = false
  let queryController: AbortController | null = null
  const exportController = new AbortController()

  const deviceOptions = computed(() => options.devices.value.map(device => ({ id: device.id, name: deviceDisplayName(device) })))
  const hasUnappliedChanges = computed(() => deviceId.value !== applied.value.deviceId
    || !dateRange.value
    || !sameDate(dateRange.value[0], applied.value.start)
    || !sameDate(dateRange.value[1], applied.value.end))
  const descendingRows = computed(() => [...rows.value].sort((left, right) => Date.parse(right.measuredAt) - Date.parse(left.measuredAt)))
  const pagedRows = computed(() => descendingRows.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
  const reportedValues = computed(() => rows.value.map(row => row[metric.value]).filter((value): value is number => value !== null && Number.isFinite(value)))
  const metricAverage = computed(() => reportedValues.value.length
    ? reportedValues.value.reduce((total, value) => total + value, 0) / reportedValues.value.length
    : null)
  const emptyDescription = computed(() => loading.value ? '正在加载历史数据…'
    : queryFailed.value ? '历史数据加载失败，请检查提示后重新查询。'
    : '该时间范围内暂无观测数据，请调整设备或时间后查询。')
  const shortcuts = [
    { text: '最近 24 小时', value: () => recentRange(clock) },
    { text: '最近 3 天', value: () => recentRange(clock, 3) },
    { text: '最近 7 天', value: () => recentRange(clock, 7) },
  ]

  async function query(): Promise<void> {
    if (!dateRange.value || dateRange.value.length !== 2) {
      queryFailed.value = false
      error.value = '请选择完整的开始时间和结束时间。'
      return
    }
    const [start, end] = dateRange.value
    if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end) {
      queryFailed.value = false
      error.value = '结束时间必须晚于开始时间，请调整查询范围。'
      return
    }
    const filter: HistoryFilter = { deviceId: deviceId.value, start: new Date(start), end: new Date(end) }
    const sequence = ++querySequence
    queryController?.abort()
    queryController = null
    loading.value = false
    rows.value = []
    appliedDeviceIds.value = []
    queryFailed.value = false
    applied.value = filter
    loading.value = true
    error.value = ''
    const controller = new AbortController()
    queryController = controller
    try {
      const result = await history(filter, controller.signal)
      if (disposed || sequence !== querySequence) return
      rows.value = result
      appliedDeviceIds.value = filter.deviceId ? [filter.deviceId] : options.devices.value.map(device => device.id)
      currentPage.value = 1
    } catch (cause) {
      if (disposed || sequence !== querySequence) return
      queryFailed.value = true
      error.value = errorMessage(cause, '查询暂未完成，请重试。')
    } finally {
      if (queryController === controller) queryController = null
      if (!disposed && sequence === querySequence) loading.value = false
    }
  }

  function reset() {
    deviceId.value = ''
    dateRange.value = recentRange(clock)
    void query()
  }

  function releaseObjectUrl(url: string) {
    const timer = objectUrlTimers.get(url)
    if (timer !== undefined) clearTimeout(timer)
    objectUrlTimers.delete(url)
    if (objectUrls.delete(url)) URL.revokeObjectURL(url)
  }

  function download(file: Blob, filename: string) {
    const url = URL.createObjectURL(file)
    objectUrls.add(url)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
    objectUrlTimers.set(url, setTimeout(() => releaseObjectUrl(url), 0))
  }

  async function exportRows(): Promise<void> {
    if (!rows.value.length || loading.value || exporting.value || disposed) return
    const sequence = querySequence
    const filter = { ...applied.value }
    const devicesById = new Map(options.devices.value.map(device => [device.id, device]))
    const selected = appliedDeviceIds.value.map(id => devicesById.get(id)).filter((device): device is Device => Boolean(device))
    if (selected.length !== appliedDeviceIds.value.length) {
      error.value = '设备列表已变更，请重新查询后再导出。'
      return
    }
    exporting.value = true
    error.value = ''
    try {
      if (!selected.length) throw new Error('没有可导出的设备，请重新查询。')
      const files: Blob[] = []
      for (let offset = 0; offset < selected.length; offset += 4) {
        files.push(...await Promise.all(selected.slice(offset, offset + 4).map(device => exportHistory(device, { ...filter, deviceId: device.id }, exportController.signal))))
      }
      if (disposed || sequence !== querySequence) return
      const file = await mergeCsv(files)
      if (disposed || sequence !== querySequence) return
      const filename = `灌区水情_历史数据_${formatDate(filter.end).slice(0, 10)}.csv`
      ;(options.download ?? download)(file, filename)
    } catch (cause) {
      if (!disposed && sequence === querySequence) error.value = errorMessage(cause, '导出失败，请重试。')
    } finally {
      if (!disposed) exporting.value = false
    }
  }

  function dispose() {
    if (disposed) return
    disposed = true
    querySequence++
    queryController?.abort()
    queryController = null
    exportController.abort()
    for (const timer of objectUrlTimers.values()) clearTimeout(timer)
    objectUrlTimers.clear()
    for (const url of objectUrls) URL.revokeObjectURL(url)
    objectUrls.clear()
  }

  const initialQuery = query()
  return {
    deviceId, dateRange, applied, rows, metric, currentPage, pageSize,
    loading, exporting, error, deviceOptions, hasUnappliedChanges,
    pagedRows, reportedValues, metricAverage, emptyDescription,
    query, reset, exportRows, dispose, initialQuery, shortcuts,
  }
}
