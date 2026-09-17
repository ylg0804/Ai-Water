import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPlatform, type PlatformGateway } from '../../../src/services/platform/store'
import type { Alarm, Device, DeviceDraft, Measurement } from '../../../src/domain/models'
import type { RealtimeOptions } from '../../../src/services/platform/realtime'
import type { RuntimeStatus } from '../../../src/services/api/measurements'
const validParams = 'gauge_roi_x1=0,gauge_roi_y1=0,gauge_roi_x2=10,gauge_roi_y2=10,flow_roi_x1=0,flow_roi_y1=0,flow_roi_x2=10,flow_roi_y2=10,scale_top_y=0,scale_top_value=0,scale_bottom_y=10,scale_bottom_value=1,gauge_base_height=0,frame_width=640,frame_height=480,fps=25,frame_step=1,channel_bottom_width=1,channel_side_slope_1=1,channel_side_slope_2=1,alpha=0.8'

const now = () => new Date('2026-09-09T02:00:30Z')
const device = (id = '1'): Device => ({ id, serial: `CAM${id}`, name: `Camera ${id}`, location: '', channelNo: 1, hd: 0, deviceOtherParams: validParams, inferIntervalSec: 60, status: 1, online: null, createdAt: '2026-09-09T01:00:00Z' })
const measurement = (id = '1', measuredAt = '2026-09-09T02:00:00Z'): Measurement => ({ id: `m${id}`, deviceId: id, deviceName: `Camera ${id}`, waterLevel: 1.26, velocity: null, discharge: null, measuredAt })
const alarm: Alarm = { id: '3', deviceId: '1', deviceName: 'Camera 1', type: 'DEBRIS', status: 'OPEN', detail: '', snapshotUrl: null, createdAt: '2026-09-09T02:00:00Z', handledAt: null }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((r, fail) => { resolve = r; reject = fail })
  return { promise, resolve, reject }
}
function fixture() {
  return {
    listDevices: vi.fn(async () => [device()]),
    listAlarms: vi.fn(async () => [{ ...alarm }]),
    getLatestMeasurement: vi.fn(async (d: Device) => measurement(d.id)),
    getRuntimeStatus: vi.fn(async () => ({ lastSuccess: '2026-09-09T02:00:00Z', measurement: null, error: '' })),
    listDeviceHistory: vi.fn(async (d: Device) => [measurement(d.id)]),
    saveDeviceRequest: vi.fn(async (_draft: DeviceDraft, _id?: string) => {}),
    deleteDeviceRequest: vi.fn(async (_id: string) => {}),
    handleAlarmRequest: vi.fn(async (_id: string, _action: 'CONFIRMED' | 'IGNORED') => {}),
  } satisfies PlatformGateway
}
afterEach(() => vi.useRealTimers())

describe('platform store', () => {
  it('starts empty and reports a device-list failure without inventing data', async () => {
    const gateway = fixture(), api = createPlatform({ gateway })
    expect([api.state.devices, api.state.measurements, api.state.alarms]).toEqual([[], [], []])
    gateway.listDevices.mockRejectedValue(new Error('device unavailable'))
    await expect(api.refreshMeasurements()).rejects.toThrow('device unavailable')
    expect(api.state).toMatchObject({ devices: [], measurements: [], alarms: [], connection: 'error', ready: false, loading: false })
  })
  it('keeps independent devices available during alarm, latest and runtime failures', async () => {
    const gateway = fixture()
    gateway.listDevices.mockResolvedValue([device(), device('2')])
    gateway.listAlarms.mockRejectedValue(new Error('alarms unavailable'))
    gateway.getLatestMeasurement.mockRejectedValueOnce(new Error('latest unavailable'))
    gateway.getRuntimeStatus.mockRejectedValueOnce(new Error('runtime unavailable'))
    const api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    expect(api.state.connection).toBe('connected')
    expect(api.state.storageWarning).toContain('alarms unavailable')
    expect(api.state.alarmError).toContain('alarms unavailable')
    expect(api.state.devices[0]).toMatchObject({ online: null })
    expect(api.state.devices[0].runtimeError).toContain('latest unavailable')
    expect(api.state.devices[0].runtimeError).toContain('runtime unavailable')
    expect(api.state.devices[1].online).toBe(true)
    expect(api.latestForDevice('2')).toMatchObject({ waterLevel: 1.26, velocity: null, discharge: null })
    expect(api.latestForDevice('1')).toBeUndefined()

    gateway.listAlarms.mockResolvedValue([{ ...alarm }])
    await api.refreshMeasurements()
    expect(api.state.alarmError).toBe('')
  })
  it.each([
    ['2026-09-09T02:00:00Z', true],
    ['2026-09-09T01:00:00Z', false],
    ['2026-09-09T02:00:36Z', false],
    [null, false],
  ])('derives online only from lastSuccess %s while preserving runtime errors', async (lastSuccess, online) => {
    const gateway: PlatformGateway = { ...fixture(), getRuntimeStatus: async () => ({ lastSuccess, measurement: null, error: 'measurement persistence failed' }) }
    const api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    expect(api.state.devices[0]).toMatchObject({ online, runtimeError: 'measurement persistence failed' })
  })
  it('merges a typed runtime measurement and retains null values', async () => {
    const gateway: PlatformGateway = { ...fixture(), getRuntimeStatus: async () => ({ lastSuccess: '2026-09-09T02:00:20Z', measurement: { ...measurement('1', '2026-09-09T02:00:20Z'), waterLevel: null }, error: '' }) }
    const api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    expect(api.latestForDevice('1')).toMatchObject({ waterLevel: null, measuredAt: '2026-09-09T02:00:20Z' })
  })
  it.each(['2026-09-09T01:00:00Z', null])('ignores runtime lastSuccess %s and its error after a newer realtime sample arrives', async lastSuccess => {
    const gateway: PlatformGateway = fixture(), pending = deferred<RuntimeStatus>()
    gateway.getRuntimeStatus = vi.fn(() => pending.promise)
    const api = createPlatform({ gateway, clock: now })
    const refreshing = api.refreshMeasurements()
    await vi.waitFor(() => expect(gateway.getRuntimeStatus).toHaveBeenCalledOnce())
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.8 } }))
    pending.resolve({ lastSuccess, measurement: null, error: 'old sampling failed' })
    await refreshing
    expect(api.state.devices[0]).toMatchObject({ lastSuccess: '2026-09-09T02:00:20.000Z', online: true })
    expect(api.state.devices[0].runtimeError ?? '').toBe('')
    expect(api.latestForDevice('1')?.waterLevel).toBe(1.8)
  })
  it('keeps a newer realtime sample online if the pending runtime request fails', async () => {
    const gateway = fixture(), pending = deferred<{ lastSuccess: string; measurement: null; error: string }>()
    gateway.getRuntimeStatus.mockReturnValue(pending.promise)
    const api = createPlatform({ gateway, clock: now })
    const refreshing = api.refreshMeasurements()
    await vi.waitFor(() => expect(gateway.getRuntimeStatus).toHaveBeenCalledOnce())
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.8 } }))
    pending.reject(new Error('runtime transport unavailable'))
    await refreshing
    expect(api.state.devices[0]).toMatchObject({ lastSuccess: '2026-09-09T02:00:20.000Z', online: true })
    expect(api.state.devices[0].runtimeError).toContain('runtime transport unavailable')
  })
  it('keeps realtime freshness when a pending device list is followed by a failed runtime', async () => {
    const gateway = fixture(), staleDevices = deferred<Device[]>(), pendingRuntime = deferred<{ lastSuccess: string; measurement: null; error: string }>()
    const api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    gateway.listDevices.mockReturnValueOnce(staleDevices.promise)
    gateway.getRuntimeStatus.mockReturnValueOnce(pendingRuntime.promise)
    const refreshing = api.refreshMeasurements()
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.8 } }))
    staleDevices.resolve([device()])
    await vi.waitFor(() => expect(gateway.getRuntimeStatus).toHaveBeenCalledTimes(2))
    pendingRuntime.reject(new Error('runtime transport unavailable'))
    await refreshing
    expect(api.latestForDevice('1')).toMatchObject({ waterLevel: 1.8, measuredAt: '2026-09-09T02:00:20.000Z' })
    expect(api.state.devices[0]).toMatchObject({ lastSuccess: '2026-09-09T02:00:20.000Z', online: true })
  })
  it.each(['2026-09-09T02:00:20Z', '2026-09-09T02:00:25Z'])('accepts an equally recent or newer runtime sample %s and exposes persistence failure', async lastSuccess => {
    const gateway = fixture(), pending = deferred<{ lastSuccess: string; measurement: null; error: string }>()
    gateway.getRuntimeStatus.mockReturnValue(pending.promise)
    const api = createPlatform({ gateway, clock: now })
    const refreshing = api.refreshMeasurements()
    await vi.waitFor(() => expect(gateway.getRuntimeStatus).toHaveBeenCalledOnce())
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.8 } }))
    pending.resolve({ lastSuccess, measurement: null, error: 'measurement persistence failed' })
    await refreshing
    expect(api.state.devices[0]).toMatchObject({ lastSuccess, online: true, runtimeError: 'measurement persistence failed' })
  })
  it('saves a normalized draft and waits for the accepted device list', async () => {
    const gateway = fixture(), api = createPlatform({ gateway }), accepted = deferred<void>(), refreshed = deferred<Device[]>()
    await api.refreshMeasurements()
    gateway.saveDeviceRequest.mockReturnValue(accepted.promise)
    gateway.listDevices.mockReturnValue(refreshed.promise)
    const draft: DeviceDraft = { serial: ' cam2 ', name: ' Camera 2 ', location: ' Canal ', channelNo: 1, hd: 0, status: 1, inferIntervalSec: 60, deviceOtherParams: validParams }
    let complete = false
    const saving = api.saveDevice(draft).then(result => { complete = true; return result })
    expect(gateway.saveDeviceRequest).toHaveBeenCalledWith({ ...draft, serial: 'CAM2', name: 'Camera 2', location: 'Canal' }, undefined)
    expect(gateway.listDevices).toHaveBeenCalledTimes(1)
    accepted.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(complete).toBe(false)
    expect(api.state.devices).toHaveLength(1)
    refreshed.resolve([device(), device('2')])
    expect(await saving).toMatchObject({ id: '2' })
    expect(api.state.devices).toHaveLength(2)
  })
  it('rejects duplicate or invalid drafts before writing and reports refresh failure after acceptance', async () => {
    const gateway = fixture(), api = createPlatform({ gateway })
    await api.refreshMeasurements()
    await expect(api.saveDevice(device())).rejects.toThrow()
    await expect(api.saveDevice({ ...device('2'), deviceOtherParams: 'broken' })).rejects.toThrow()
    expect(gateway.saveDeviceRequest).not.toHaveBeenCalled()
    gateway.listDevices.mockRejectedValue(new Error('read failed'))
    await expect(api.saveDevice(device('2'))).rejects.toThrow(/保存成功/)
    expect(api.state.devices).toHaveLength(1)
  })
  it('only deletes after acceptance and keeps state when deletion is rejected', async () => {
    const gateway = fixture(), api = createPlatform({ gateway })
    await api.refreshMeasurements()
    gateway.deleteDeviceRequest.mockRejectedValueOnce(new Error('denied'))
    await expect(api.deleteDevice('1')).rejects.toThrow('denied')
    expect(api.state.devices).toHaveLength(1)
    const accepted = deferred<void>()
    gateway.deleteDeviceRequest.mockReturnValue(accepted.promise)
    const deleting = api.deleteDevice('1')
    expect(api.state.devices).toHaveLength(1)
    accepted.resolve(); await deleting
    expect(api.state.devices).toEqual([])
    expect(api.state.measurements).toEqual([])
  })
  it('only updates alarms from the refreshed backend response after acceptance', async () => {
    const gateway = fixture(), api = createPlatform({ gateway })
    await api.refreshMeasurements()
    gateway.handleAlarmRequest.mockRejectedValueOnce(new Error('denied'))
    await expect(api.handleAlarm('3', 'CONFIRMED')).rejects.toThrow('denied')
    expect(api.state.alarms[0].status).toBe('OPEN')
    const accepted = deferred<void>()
    gateway.handleAlarmRequest.mockReturnValue(accepted.promise)
    gateway.listAlarms.mockResolvedValue([{ ...alarm, status: 'CONFIRMED', handledAt: '2026-09-09T02:00:30Z' }])
    const handling = api.handleAlarm('3', 'CONFIRMED')
    expect(api.state.alarms[0].status).toBe('OPEN')
    accepted.resolve(); await handling
    expect(api.state.alarms[0]).toMatchObject({ status: 'CONFIRMED', handledAt: '2026-09-09T02:00:30Z' })
  })
  it('deduplicates refreshes and prevents pending responses from restoring logged-out state', async () => {
    const gateway = fixture(), pending = deferred<Device[]>()
    gateway.listDevices.mockReturnValue(pending.promise)
    const api = createPlatform({ gateway })
    const first = api.refreshMeasurements()
    expect(api.refreshMeasurements()).toBe(first)
    api.stop(); pending.resolve([device()]); await first
    expect(api.state).toMatchObject({ devices: [], measurements: [], alarms: [], ready: false, loading: false, connection: 'idle' })
  })
  it('does not restore a deleted device from an older refresh or clear the newer refresh loading state', async () => {
    const gateway = fixture(), api = createPlatform({ gateway }), stale = deferred<Device[]>(), current = deferred<Device[]>()
    await api.refreshMeasurements()
    gateway.listDevices.mockReturnValueOnce(stale.promise).mockReturnValueOnce(current.promise)
    const oldRefresh = api.refreshMeasurements()
    await api.deleteDevice('1')
    const newRefresh = api.refreshMeasurements()
    expect(newRefresh).not.toBe(oldRefresh)
    stale.resolve([device()]); await oldRefresh
    expect(api.state.devices).toEqual([])
    expect(api.state.measurements).toEqual([])
    expect(api.state.loading).toBe(true)
    current.resolve([]); await newRefresh
    expect(api.state.loading).toBe(false)
  })
  it('keeps the accepted save list when the preceding refresh returns its older list', async () => {
    const gateway = fixture(), api = createPlatform({ gateway }), stale = deferred<Device[]>()
    await api.refreshMeasurements()
    gateway.listDevices.mockReturnValueOnce(stale.promise).mockResolvedValueOnce([device(), device('2')])
    const refreshing = api.refreshMeasurements()
    await api.saveDevice(device('2'))
    stale.resolve([device()]); await refreshing
    expect(api.state.devices.map(row => row.id)).toEqual(['1', '2'])
  })
  it('keeps sampling state and updates alarm device names after editing a device', async () => {
    const gateway = fixture(), api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    expect(api.state.devices[0]).toMatchObject({ online: true, lastSuccess: '2026-09-09T02:00:00Z' })

    gateway.listDevices.mockResolvedValueOnce([{ ...device(), name: '渠首监测点' }])
    await api.saveDevice({ ...device(), name: '渠首监测点' }, '1')

    expect(api.state.devices[0]).toMatchObject({ name: '渠首监测点', online: true, lastSuccess: '2026-09-09T02:00:00Z' })
    expect(api.state.alarms[0].deviceName).toBe('渠首监测点')
  })
  it('does not apply old latest, runtime or alarm results after accepting a device save', async () => {
    const gateway = fixture(), api = createPlatform({ gateway, clock: now }), latest = deferred<Measurement>(), runtime = deferred<{ lastSuccess: string; measurement: null; error: string }>(), alarms = deferred<Alarm[]>()
    await api.refreshMeasurements()
    gateway.getLatestMeasurement.mockReturnValueOnce(latest.promise)
    gateway.getRuntimeStatus.mockReturnValueOnce(runtime.promise)
    gateway.listAlarms.mockReturnValueOnce(alarms.promise)
    const refreshing = api.refreshMeasurements()
    await vi.waitFor(() => expect(gateway.getRuntimeStatus).toHaveBeenCalledTimes(2))
    gateway.listDevices.mockResolvedValueOnce([{ ...device(), name: 'Updated camera' }])
    await api.saveDevice({ ...device(), name: 'Updated camera' }, '1')
    const accepted = JSON.stringify(api.state)
    latest.resolve({ ...measurement(), waterLevel: 9 })
    runtime.resolve({ lastSuccess: '2026-09-09T01:00:00Z', measurement: null, error: 'old runtime error' })
    alarms.resolve([])
    await refreshing
    expect(JSON.stringify(api.state)).toBe(accepted)
  })
  it('keeps a confirmed alarm when an older refresh or realtime alarm reload finishes later', async () => {
    const gateway = fixture(), api = createPlatform({ gateway }), refreshAlarms = deferred<Alarm[]>(), realtimeAlarms = deferred<Alarm[]>()
    await api.refreshMeasurements()
    gateway.listAlarms.mockReturnValueOnce(refreshAlarms.promise).mockReturnValueOnce(realtimeAlarms.promise)
    const refreshing = api.refreshMeasurements()
    await vi.waitFor(() => expect(gateway.listAlarms).toHaveBeenCalledTimes(2))
    api.receiveRealtime('{"type":"alarm"}')
    gateway.listAlarms.mockResolvedValueOnce([{ ...alarm, status: 'CONFIRMED', handledAt: now().toISOString() }])
    await api.handleAlarm('3', 'CONFIRMED')
    refreshAlarms.resolve([{ ...alarm }]); realtimeAlarms.resolve([{ ...alarm }])
    await refreshing
    expect(api.state.alarms[0]).toMatchObject({ status: 'CONFIRMED', handledAt: now().toISOString() })
  })
  it('ignores an old refresh failure after acceptance and keeps the existing realtime channel usable', async () => {
    vi.useFakeTimers()
    const gateway = fixture(), stale = deferred<Device[]>()
    let callbacks!: RealtimeOptions
    const api = createPlatform({ gateway, clock: now, realtimeFactory: options => { callbacks = options; return { connect() {}, stop() {} } } })
    try {
      gateway.listDevices.mockResolvedValue([device(), device('2')])
      api.start(); await api.refreshMeasurements()
      gateway.listDevices.mockReturnValueOnce(stale.promise)
      const refreshing = api.refreshMeasurements()
      await api.deleteDevice('1')
      stale.reject(new Error('old request failed'))
      await expect(refreshing).resolves.toBeUndefined()
      callbacks.onState('connected')
      callbacks.onMessage(JSON.stringify({ type: 'measurement', serial: 'CAM2', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.8 } }))
      expect(api.state.connection).toBe('connected')
      expect(api.state.storageWarning).toBe('')
      expect(api.state.realtime).toBe('connected')
      expect(api.latestForDevice('2')?.waterLevel).toBe(1.8)
    } finally { api.stop() }
  })
  it('does not restore a deleted device from a previously accepted save that is still reloading', async () => {
    const gateway = fixture(), api = createPlatform({ gateway }), savedList = deferred<Device[]>()
    await api.refreshMeasurements()
    gateway.listDevices.mockReturnValueOnce(savedList.promise)
    const saving = api.saveDevice({ ...device(), name: 'Updated camera' }, '1')
    const rejected = expect(saving).rejects.toThrow(/数据已更新/)
    await vi.waitFor(() => expect(gateway.listDevices).toHaveBeenCalledTimes(2))
    await api.deleteDevice('1')
    savedList.resolve([{ ...device(), name: 'Updated camera' }])
    await rejected
    expect(api.state.devices).toEqual([])
  })
  it('replaces only the matching latest row and ignores malformed, unknown or older realtime events', async () => {
    const gateway = fixture()
    gateway.listDevices.mockResolvedValue([device(), device('2')])
    const api = createPlatform({ gateway, clock: now })
    await api.refreshMeasurements()
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:00:20Z', data: { waterLevel: 1.3 } }))
    expect(api.latestForDevice('1')).toMatchObject({ waterLevel: 1.3, velocity: null, discharge: null })
    expect(api.latestForDevice('2')?.waterLevel).toBe(1.26)
    const before = JSON.stringify(api.state)
    for (const payload of ['not-json', 'null', '{}', '{"type":"unknown"}', '{"type":"measurement","serial":"CAM1"}', JSON.stringify({ type: 'measurement', serial: 'missing', measuredAt: now().toISOString(), data: {} }), JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T01:00:00Z', data: { waterLevel: 9 } })]) api.receiveRealtime(payload)
    expect(JSON.stringify(api.state)).toBe(before)
  })
  it('validates history before requests and queries just the selected typed device', async () => {
    const gateway = fixture(), api = createPlatform({ gateway })
    await expect(api.queryHistory({ deviceId: '', start: 'invalid', end: now() })).rejects.toThrow()
    expect(gateway.listDevices).not.toHaveBeenCalled()
    await api.refreshMeasurements()
    const filter = { deviceId: '1', start: '2026-09-09T01:00:00Z', end: now() }
    expect(await api.queryHistory(filter)).toHaveLength(1)
    expect(gateway.listDeviceHistory).toHaveBeenCalledWith(expect.objectContaining({ id: '1' }), filter)
    await expect(api.queryHistory({ ...filter, deviceId: 'missing' })).rejects.toThrow()
  })
  it('bounds all-device history to four requests and sorts merged rows', async () => {
    const gateway = fixture(), devices = ['1', '2', '3', '4', '5'].map(device)
    gateway.listDevices.mockResolvedValue(devices)
    const pending = devices.map(() => deferred<Measurement[]>())
    gateway.listDeviceHistory.mockImplementation(d => pending[Number(d.id) - 1].promise)
    const api = createPlatform({ gateway }); await api.refreshMeasurements()
    const querying = api.queryHistory({ deviceId: '', start: '2026-09-09T01:00:00Z', end: now() })
    expect(gateway.listDeviceHistory).toHaveBeenCalledTimes(4)
    for (let i = 0; i < 4; i++) pending[i].resolve([measurement(String(i + 1), `2026-09-09T01:0${4 - i}:00Z`)])
    await vi.waitFor(() => expect(gateway.listDeviceHistory).toHaveBeenCalledTimes(5))
    pending[4].resolve([measurement('5', '2026-09-09T01:00:00Z')])
    expect((await querying).map(row => row.deviceId)).toEqual(['5', '4', '3', '2', '1'])
  })
  it('passes caller cancellation to history requests and does not start another batch', async () => {
    const gateway = fixture(), devices = ['1', '2', '3', '4', '5'].map(device)
    gateway.listDevices.mockResolvedValue(devices)
    const signals: Array<AbortSignal | undefined> = []
    gateway.listDeviceHistory.mockImplementation(async (_device, _filter, signal) => {
      signals.push(signal)
      return []
    })
    const api = createPlatform({ gateway })
    await api.refreshMeasurements()
    const controller = new AbortController()

    const querying = api.queryHistory({ deviceId: '', start: '2026-09-09T01:00:00Z', end: now() }, controller.signal)
    controller.abort()

    await expect(querying).rejects.toMatchObject({ name: 'AbortError' })
    expect(gateway.listDeviceHistory).toHaveBeenCalledTimes(4)
    expect(signals.every(signal => signal === controller.signal)).toBe(true)
  })
  it.each([false, true])('rejects history invalidated by logout (during initial load: %s)', async initial => {
    const gateway = fixture(), api = createPlatform({ gateway }), rows = deferred<Measurement[]>(), devices = deferred<Device[]>()
    if (!initial) await api.refreshMeasurements()
    if (initial) gateway.listDevices.mockReturnValue(devices.promise)
    else gateway.listDeviceHistory.mockReturnValue(rows.promise)
    const querying = api.queryHistory({ deviceId: '', start: '2026-09-09T01:00:00Z', end: now() })
    api.stop(); devices.resolve([device()]); rows.resolve([measurement()])
    await expect(querying).rejects.toThrow(/会话/)
  })
  it('polls only while realtime is unavailable, pauses without clearing data and restarts with a fresh channel', async () => {
    vi.useFakeTimers()
    const gateway = fixture(), channels: { options: RealtimeOptions; connect: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn> }[] = []
    const realtimeFactory = (options: RealtimeOptions) => { const channel = { options, connect: vi.fn(), stop: vi.fn() }; channels.push(channel); return channel }
    const api = createPlatform({ gateway, realtimeFactory, clock: now })
    api.start(); api.start(); await api.refreshMeasurements()
    expect(channels).toHaveLength(1)
    channels[0].options.onState('connected')
    expect(api.state.realtime).toBe('connected')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(gateway.listDevices).toHaveBeenCalledTimes(1)
    channels[0].options.onState('polling')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(gateway.listDevices).toHaveBeenCalledTimes(2)
    api.pauseUpdates()
    expect(channels[0].stop).toHaveBeenCalledOnce()
    channels[0].options.onState('connected')
    expect(api.state.realtime).toBe('stopped')
    expect(api.state.devices).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(gateway.listDevices).toHaveBeenCalledTimes(2)
    api.start(); await api.refreshMeasurements()
    expect(channels).toHaveLength(2)
    api.stop()
    expect(api.state.devices).toEqual([])
  })
  it('expires sampling freshness while realtime stays connected without polling', async () => {
    vi.useFakeTimers()
    let current = new Date('2026-09-09T02:00:30Z')
    const gateway = fixture()
    const realtimeFactory = (options: RealtimeOptions) => ({ options, connect: vi.fn(), stop: vi.fn() })
    const api = createPlatform({ gateway, realtimeFactory, clock: () => current })
    api.start()
    await api.refreshMeasurements()
    expect(api.state.devices[0].online).toBe(true)
    api.state.realtime = 'connected'
    current = new Date('2026-09-09T02:04:00Z')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.state.devices[0].online).toBe(false)
    expect(gateway.listDevices).toHaveBeenCalledTimes(1)
    api.stop()
  })
})
