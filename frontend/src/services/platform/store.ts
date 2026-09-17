import { reactive } from 'vue'
import type { Alarm, AlarmStatus, Device, DeviceDraft, HistoryFilter, Measurement } from '../../domain/models'
import { deviceDisplayName } from '../../domain/device'
import { validateParams } from '../../domain/deviceParameters'
import type { PlatformState } from './types'
import { listDevices, normalizeDeviceDraft, saveDeviceRequest, deleteDeviceRequest } from '../api/devices'
import { listAlarms, handleAlarmRequest } from '../api/alarms'
import { getLatestMeasurement, getRuntimeStatus, listDeviceHistory, historyParams, mapMeasurement, type RuntimeStatus } from '../api/measurements'
import { asRecord } from '../api/contract'
import { createRealtimeChannel, type RealtimeChannel, type RealtimeOptions } from './realtime'

export interface PlatformGateway {
  listDevices(): Promise<Device[]>
  listAlarms(devices: Device[]): Promise<Alarm[]>
  getLatestMeasurement(device: Device): Promise<Measurement | null>
  getRuntimeStatus(device: Device): Promise<RuntimeStatus>
  listDeviceHistory(device: Device, filter: HistoryFilter, signal?: AbortSignal): Promise<Measurement[]>
  saveDeviceRequest(draft: DeviceDraft, id?: string): Promise<void>
  deleteDeviceRequest(id: string): Promise<void>
  handleAlarmRequest(id: string, action: Exclude<AlarmStatus, 'OPEN'>): Promise<void>
}
export interface PlatformOptions {
  gateway?: PlatformGateway
  realtimeFactory?: (options: RealtimeOptions) => RealtimeChannel
  clock?: () => Date
}
const defaultGateway: PlatformGateway = {
  listDevices,
  listAlarms,
  getLatestMeasurement,
  getRuntimeStatus,
  listDeviceHistory: (device, filter, signal) => listDeviceHistory(device, filter, undefined, signal),
  saveDeviceRequest,
  deleteDeviceRequest,
  handleAlarmRequest,
}
function message(error: unknown): string { return error instanceof Error ? error.message : '服务请求失败。' }
function throwIfAborted(signal?: AbortSignal) {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}

export function createPlatform(options: PlatformOptions = {}) {
  const gateway = options.gateway ?? defaultGateway
  const clock = options.clock ?? (() => new Date())
  const realtimeFactory = options.realtimeFactory ?? createRealtimeChannel
  const state = reactive<PlatformState>({ devices: [], measurements: [], alarms: [], alarmError: '', storageWarning: '', connection: 'idle', realtime: 'stopped', loading: false, ready: false, lastUpdated: null })
  let generation = 0, stateRevision = 0, active = false
  let channel: RealtimeChannel | null = null
  let timer: ReturnType<typeof setInterval> | undefined
  let inFlight: Promise<void> | null = null
  function assertCurrent(epoch: number) {
    if (epoch !== generation) throw new Error('会话已变更，请重新查询。')
  }
  function isCurrentRead(epoch: number, revision: number): boolean {
    return epoch === generation && revision === stateRevision
  }
  // Accepted writes invalidate earlier reads without replacing the session or its realtime channel.
  function acceptMutation(): number {
    stateRevision++
    inFlight = null
    state.loading = false
    return stateRevision
  }
  function assertCurrentRead(epoch: number, revision: number) {
    assertCurrent(epoch)
    if (revision !== stateRevision) throw new Error('操作已完成，但数据已更新，请刷新确认结果。')
  }
  function updateOnline(device: Device) {
    const age = device.lastSuccess ? clock().getTime() - Date.parse(device.lastSuccess) : Infinity
    device.online = age >= -5_000 && age <= Math.max(120_000, device.inferIntervalSec * 2_000 + 45_000)
  }
  function mergeLatest(row: Measurement): boolean {
    const previous = state.measurements.find(value => value.deviceId === row.deviceId)
    if (previous && Date.parse(previous.measuredAt) > Date.parse(row.measuredAt)) return false
    state.measurements = [...state.measurements.filter(value => value.deviceId !== row.deviceId), row]
    return true
  }
  function preserveRuntimeState(devices: Device[]): Device[] {
    return devices.map(device => {
      const current = state.devices.find(value => value.id === device.id)
      return current?.serial === device.serial
        ? { ...device, online: current.online, runtimeError: current.runtimeError, lastSuccess: current.lastSuccess }
        : device
    })
  }
  function updateDeviceLabels() {
    const names = new Map(state.devices.map(device => [device.id, deviceDisplayName(device)]))
    state.measurements = state.measurements.map(row => names.has(row.deviceId) ? { ...row, deviceName: names.get(row.deviceId)! } : row)
    state.alarms = state.alarms.map(row => names.has(row.deviceId) ? { ...row, deviceName: names.get(row.deviceId)! } : row)
  }
  async function alarmsNow(epoch: number, revision: number) {
    try {
      const rows = await gateway.listAlarms(state.devices)
      if (isCurrentRead(epoch, revision)) {
        state.alarms = rows
        state.alarmError = ''
      }
    } catch (error) {
      if (isCurrentRead(epoch, revision)) state.alarmError = `告警列表获取失败：${message(error)}`
      throw error
    }
  }
  async function deviceLatest(device: Device, epoch: number, revision: number) {
    const [latest, runtime] = await Promise.allSettled([
      gateway.getLatestMeasurement(device),
      device.status === 1 ? gateway.getRuntimeStatus(device) : Promise.resolve(null),
    ])
    if (!isCurrentRead(epoch, revision)) return
    const current = state.devices.find(row => row.id === device.id)
    if (!current) return
    const errors: string[] = []
    if (latest.status === 'fulfilled') { if (latest.value) mergeLatest(latest.value) }
    else errors.push(`测量结果获取失败：${message(latest.reason)}`)
    if (runtime.status === 'fulfilled' && runtime.value) {
      const receivedAt = runtime.value.lastSuccess ? Date.parse(runtime.value.lastSuccess) : -Infinity
      const currentAt = current.lastSuccess ? Date.parse(current.lastSuccess) : -Infinity
      if (receivedAt < currentAt) {
        // This runtime snapshot predates the current sample, including its sampling error.
        if (errors.length) current.runtimeError = [current.runtimeError, ...errors].filter(Boolean).join('；')
        return
      }
      current.lastSuccess = runtime.value.lastSuccess
      if (runtime.value.measurement) mergeLatest(runtime.value.measurement)
      if (runtime.value.error) errors.push(runtime.value.error)
      updateOnline(current)
    } else {
      if (current.lastSuccess) updateOnline(current)
      else current.online = null
      if (runtime.status === 'rejected') errors.push(`采样状态未取得：${message(runtime.reason)}`)
    }
    current.runtimeError = errors.join('；')
  }
  const api = {
    state,
    latestForDevice(id: string): Measurement | undefined { return state.measurements.find(row => row.deviceId === id) },
    refreshMeasurements(): Promise<void> {
      if (inFlight) return inFlight
      const epoch = generation, revision = stateRevision
      state.loading = true
      if (!state.ready) state.connection = 'loading'
      const task = (async () => {
        try {
          const devices = await gateway.listDevices()
          if (!isCurrentRead(epoch, revision)) return
          state.devices = preserveRuntimeState(devices)
          state.measurements = state.measurements.filter(row => devices.some(device => device.id === row.deviceId))
          updateDeviceLabels()
          const results = await Promise.allSettled([alarmsNow(epoch, revision), ...devices.map(device => deviceLatest(device, epoch, revision))])
          if (!isCurrentRead(epoch, revision)) return
          state.storageWarning = results[0].status === 'rejected' ? state.alarmError : ''
          state.connection = 'connected'
          state.ready = true
          state.lastUpdated = clock().toISOString()
        } catch (error) {
          if (!isCurrentRead(epoch, revision)) return
          state.connection = 'error'
          state.storageWarning = message(error)
          state.devices.forEach(device => { device.online = null; device.runtimeError = '服务连接失败，当前显示上次数据。' })
          throw error
        } finally {
          if (isCurrentRead(epoch, revision)) { state.loading = false; inFlight = null }
        }
      })()
      inFlight = task
      return task
    },
    async queryHistory(filter: HistoryFilter, signal?: AbortSignal): Promise<Measurement[]> {
      historyParams(filter)
      throwIfAborted(signal)
      const epoch = generation
      if (!state.ready) await api.refreshMeasurements()
      assertCurrent(epoch)
      throwIfAborted(signal)
      const devices = filter.deviceId ? state.devices.filter(device => device.id === filter.deviceId) : [...state.devices]
      if (filter.deviceId && !devices.length) throw new Error('监测设备不存在，请重新选择。')
      const rows: Measurement[] = []
      for (let offset = 0; offset < devices.length; offset += 4) {
        assertCurrent(epoch)
        throwIfAborted(signal)
        const batch = await Promise.all(devices.slice(offset, offset + 4).map(device => {
          const deviceFilter = { ...filter, deviceId: device.id }
          return signal ? gateway.listDeviceHistory(device, deviceFilter, signal) : gateway.listDeviceHistory(device, deviceFilter)
        }))
        assertCurrent(epoch)
        throwIfAborted(signal)
        rows.push(...batch.flat())
      }
      assertCurrent(epoch)
      return rows.sort((a, b) => Date.parse(a.measuredAt) - Date.parse(b.measuredAt))
    },
    async saveDevice(draft: DeviceDraft, id?: string): Promise<Device> {
      const normalized = normalizeDeviceDraft(draft, state.devices, id)
      const error = validateParams(normalized.deviceOtherParams)
      if (error) throw new Error(error)
      const epoch = generation
      await gateway.saveDeviceRequest(normalized, id)
      assertCurrent(epoch)
      const revision = acceptMutation()
      let devices: Device[]
      try { devices = await gateway.listDevices() }
      catch { throw new Error('保存成功，但列表刷新失败。请刷新确认，避免重复添加。') }
      assertCurrentRead(epoch, revision)
      state.devices = preserveRuntimeState(devices)
      updateDeviceLabels()
      const saved = state.devices.find(device => id ? device.id === id : device.serial === normalized.serial)
      if (!saved) throw new Error('保存成功，但列表尚未更新，请刷新确认。')
      return saved
    },
    async deleteDevice(id: string): Promise<void> {
      const epoch = generation
      await gateway.deleteDeviceRequest(id)
      assertCurrent(epoch)
      acceptMutation()
      state.devices = state.devices.filter(device => device.id !== id)
      state.measurements = state.measurements.filter(row => row.deviceId !== id)
    },
    async handleAlarm(id: string, status: Exclude<AlarmStatus, 'OPEN'>): Promise<void> {
      if (status !== 'CONFIRMED' && status !== 'IGNORED') throw new Error('请选择确认或忽略。')
      const epoch = generation
      await gateway.handleAlarmRequest(id, status)
      assertCurrent(epoch)
      const revision = acceptMutation()
      try { await alarmsNow(epoch, revision) }
      catch { throw new Error('处理成功，但告警列表刷新失败，请刷新确认状态。') }
      assertCurrentRead(epoch, revision)
    },
    receiveRealtime(payload: string) {
      try {
        const event = asRecord(JSON.parse(payload))
        if (event.type === 'alarm') {
          const epoch = generation, revision = stateRevision
          void alarmsNow(epoch, revision).catch(error => { if (isCurrentRead(epoch, revision)) state.storageWarning = `告警列表获取失败：${message(error)}` })
          return
        }
        if (event.type !== 'measurement' || typeof event.measuredAt !== 'string' || !event.measuredAt) return
        const device = state.devices.find(row => row.serial === event.serial)
        if (!device) return
        const row = mapMeasurement(event.data, device, event.measuredAt)
        if (!mergeLatest(row)) return
        device.lastSuccess = row.measuredAt
        updateOnline(device)
        state.lastUpdated = clock().toISOString()
      } catch { /* Malformed events are ignored; polling remains available. */ }
    },
    start() {
      if (active) return
      active = true
      const epoch = generation
      channel = realtimeFactory({
        onState: value => { if (active && epoch === generation) state.realtime = value },
        onMessage: payload => { if (active && epoch === generation) api.receiveRealtime(payload) },
      })
      void api.refreshMeasurements().catch(() => {})
      channel.connect()
  timer = setInterval(() => {
    state.devices.forEach(updateOnline)
    if (state.realtime !== 'connected') void api.refreshMeasurements().catch(() => {})
    channel?.connect()
  }, 30_000)
    },
    pauseUpdates() {
      active = false
      generation++
      inFlight = null
      if (timer !== undefined) clearInterval(timer)
      timer = undefined
      channel?.stop()
      channel = null
      state.realtime = 'stopped'
      state.loading = false
    },
    stop() {
      api.pauseUpdates()
      Object.assign(state, { devices: [], measurements: [], alarms: [], alarmError: '', storageWarning: '', connection: 'idle', ready: false, lastUpdated: null })
    },
  }
  return api
}

export const platform = createPlatform()
import.meta.hot?.dispose(() => platform.stop())
