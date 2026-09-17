import { describe, expect, it, vi } from 'vitest'
import { createPlatform, type PlatformGateway } from '../../../src/services/platform/store'
import { listDevices, saveDeviceRequest, deleteDeviceRequest } from '../../../src/services/api/devices'
import { listAlarms, handleAlarmRequest } from '../../../src/services/api/alarms'
import { getLatestMeasurement, getRuntimeStatus, listDeviceHistory } from '../../../src/services/api/measurements'
import type { ApiRequest } from '../../../src/services/api/client'
const validParams = 'gauge_roi_x1=0,gauge_roi_y1=0,gauge_roi_x2=10,gauge_roi_y2=10,flow_roi_x1=0,flow_roi_y1=0,flow_roi_x2=10,flow_roi_y2=10,scale_top_y=0,scale_top_value=0,scale_bottom_y=10,scale_bottom_value=1,gauge_base_height=0,frame_width=640,frame_height=480,fps=25,frame_step=1,channel_bottom_width=1,channel_side_slope_1=1,channel_side_slope_2=1,alpha=0.8'
function gatewayFor(transport: (path: string, options?: RequestInit) => Promise<unknown>): PlatformGateway {
  const request: ApiRequest = async <T>(path: string, options?: RequestInit) => await transport(path, options) as T
  return {
    listDevices: () => listDevices(request),
    listAlarms: devices => listAlarms(devices, request),
    getLatestMeasurement: device => getLatestMeasurement(device, request),
    getRuntimeStatus: device => getRuntimeStatus(device, request),
    listDeviceHistory: (device, filter) => listDeviceHistory(device, filter, request),
    saveDeviceRequest: (draft, id) => saveDeviceRequest(draft, id, request),
    deleteDeviceRequest: id => deleteDeviceRequest(id, request),
    handleAlarmRequest: (id, action) => handleAlarmRequest(id, action, request),
  }
}
const device = { id: 1, serial: 'CAM1', name: '渠首', channel_no: 1, hd: 0, status: 1, infer_interval_sec: 60, device_other_params: validParams, created_at: '2026-09-09T10:00:00' }
const measurement = { id: 8, serial: 'CAM1', water_level: 1.26, velocity: .4, discharge: .8, measured_at: '2026-09-09T10:00:00' }
const alarm = { id: 3, device_id: 1, type: 'DEBRIS', status: 'OPEN', created_at: '2026-09-09T10:00:00', handled_at: null }
function requestFixture() {
  return vi.fn(async (path: string, options?: RequestInit): Promise<unknown> => {
    if (options?.method === 'DELETE') throw new Error('数据库操作失败')
    if (path === '/api/device') return [device]
    if (path === '/api/alarm') return [alarm]
    if (path.startsWith('/api/ezviz/measurements/latest')) return { lastSuccess: '2026-09-09T02:00:00Z', error: null, running: false, measurement: null }
    if (path.startsWith('/api/measurement/latest')) return measurement
    if (path.startsWith('/api/measurement/history')) return [measurement]
    throw new Error('Unexpected request: ' + path)
  })
}
describe('real platform', () => {
  it('starts empty and does not read or generate demonstration data', () => {
    const api = createPlatform({ gateway: gatewayFor(requestFixture()) })
    expect(api.state.devices).toEqual([])
    expect(api.state.measurements).toEqual([])
    expect(api.state.alarms).toEqual([])
  })
  it('loads actual device, measurement, and alarm responses', async () => {
    const api = createPlatform({ gateway: gatewayFor(requestFixture()), clock: () => new Date('2026-09-09T02:00:30Z') })
    await api.refreshMeasurements()
    expect(api.state.connection).toBe('connected')
    expect(api.state.devices[0].online).toBe(true)
    expect(api.latestForDevice('1')?.waterLevel).toBe(1.26)
    expect(api.state.alarms[0]).not.toHaveProperty('measurement')
  })
  it('does not fabricate data after connection failure', async () => {
    const api = createPlatform({ gateway: gatewayFor(async () => { throw new Error('not connected') }) })
    await expect(api.refreshMeasurements()).rejects.toThrow('not connected')
    expect(api.state.devices).toEqual([])
    expect(api.state.measurements).toEqual([])
    expect(api.state.connection).toBe('error')
  })
  it('does not remove a device when backend rejects deletion', async () => {
    const api = createPlatform({ gateway: gatewayFor(requestFixture()) })
    await api.refreshMeasurements()
    await expect(api.deleteDevice('1')).rejects.toThrow('数据库操作失败')
    expect(api.state.devices).toHaveLength(1)
  })
  it('queries all devices separately with backend LocalDateTime parameters', async () => {
    const request = requestFixture(), api = createPlatform({ gateway: gatewayFor(request) })
    await api.refreshMeasurements()
    const rows = await api.queryHistory({ deviceId: '', start: '2026-09-09T01:00:00Z', end: '2026-09-09T03:00:00Z' })
    expect(rows).toHaveLength(1)
    const call = request.mock.calls.find(([path]) => path.includes('/history'))!
    const params = new URL(call[0], 'http://localhost').searchParams
    expect(params.get('deviceId')).toBe('1')
    expect(params.get('start')).toBe('2026-09-09T09:00:00')
  })
  it('consumes a real WS measurement without inventing an alarm id', async () => {
    const request = requestFixture(), api = createPlatform({ gateway: gatewayFor(request) })
    await api.refreshMeasurements()
    api.receiveRealtime(JSON.stringify({ type: 'measurement', serial: 'CAM1', measuredAt: '2026-09-09T02:01:00Z', data: { waterLevel: 1.3, velocity: .42, discharge: .9 } }))
    expect(api.latestForDevice('1')?.waterLevel).toBe(1.3)
    expect(api.state.measurements).toHaveLength(1)
    api.receiveRealtime('not-json')
    expect(api.state.measurements).toHaveLength(1)
  })
  it('stopping invalidates pending responses so logout cannot restore old data', async () => {
    let resolve!: (rows: unknown) => void
    const request = vi.fn(() => new Promise(r => { resolve = r }))
    const api = createPlatform({ gateway: gatewayFor(request) })
    const pending = api.refreshMeasurements()
    api.stop()
    resolve([device])
    await pending
    expect(api.state.devices).toEqual([])
    expect(api.state.ready).toBe(false)
  })
})
