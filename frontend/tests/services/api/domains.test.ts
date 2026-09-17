import { describe, expect, it, vi } from 'vitest'
import type { Device, DeviceDraft, HistoryFilter } from '../../../src/domain/models'
import type { ApiRequest, BlobRequest } from '../../../src/services/api/client'
import { asIdentifier, asList, asNullableNumber, asRecord, parseServerDate, toBackendLocalDateTime } from '../../../src/services/api/contract'
import { deleteDeviceRequest, listDevices, mapDevice, normalizeDeviceDraft, saveDeviceRequest } from '../../../src/services/api/devices'
import { exportDeviceHistory, getLatestMeasurement, getRuntimeStatus, historyParams, listDeviceHistory } from '../../../src/services/api/measurements'
import { getAlarmSnapshot, handleAlarmRequest, listAlarms, mapAlarm } from '../../../src/services/api/alarms'
import { captureFrame, getPlayAddress, safeVideoError } from '../../../src/services/api/video'

const deviceRow = {
  id: 17, serial: 'CAM17', name: '北闸', location: '一号渠', channel_no: 2, hd: 0,
  device_other_params: 'frame_step=6.25', infer_interval_sec: 60, status: 1, created_at: '2026-09-09 10:00:00',
}
const measurementRow = {
  id: 41, serial: 'CAM17', measured_at: '2026-09-09 10:00:00', water_level: null, velocity: '0.41', discharge: 2.3,
}
const device = {
  id: '17', serial: 'CAM17', name: '北闸', location: '一号渠', channelNo: 2, hd: 0,
  deviceOtherParams: 'frame_step=6.25', inferIntervalSec: 60, status: 1, online: null, createdAt: '2026-09-09T02:00:00.000Z',
} satisfies Device
const draft = {
  serial: ' cam17 ', name: ' 北闸 ', location: ' 一号渠 ', channelNo: 2, hd: 0,
  deviceOtherParams: 'frame_step=6.25', inferIntervalSec: 60, status: 1,
} satisfies DeviceDraft
const filter = { deviceId: '17', start: '2026-09-09T02:00:00Z', end: '2026-09-09T03:00:00Z' } satisfies HistoryFilter

function requestReturning(value: unknown) {
  return vi.fn(async () => value) as unknown as ReturnType<typeof vi.fn> & ApiRequest
}

describe('typed backend domain contracts', () => {
  it('parses complete backend primitives and preserves missing numeric values', () => {
    expect(asRecord({ id: 17 })).toEqual({ id: 17 })
    expect(asList([{ id: 17 }])).toEqual([{ id: 17 }])
    expect(asIdentifier(17)).toBe('17')
    expect(asIdentifier('17')).toBe('17')
    expect(asNullableNumber(null)).toBeNull()
    expect(asNullableNumber('0.41')).toBe(0.41)
    expect(() => asNullableNumber(' ')).toThrow()
    expect(() => asNullableNumber(false)).toThrow()
    expect(() => asNullableNumber({ value: 1 })).toThrow()
    expect(parseServerDate('2026-09-09 10:00:00')).toBe('2026-09-09T02:00:00.000Z')
    expect(toBackendLocalDateTime('2026-09-09T02:00:00Z')).toBe('2026-09-09T10:00:00')
    expect(() => asIdentifier(Number.MAX_SAFE_INTEGER + 1)).toThrow()
    for (const value of ['17/../18', '-1', '0', '01', '1.5', '1e3', '9223372036854775808']) expect(() => asIdentifier(value)).toThrow()
  })

  it('maps and lists snake_case device rows', async () => {
    const request = requestReturning([deviceRow])
    expect(mapDevice(deviceRow)).toEqual(device)
    await expect(listDevices(request)).resolves.toEqual([device])
    expect(request).toHaveBeenCalledWith('/api/device')
  })

  it('keeps the device list usable when backend names are null, empty, or missing', async () => {
    const { name: _name, ...withoutName } = deviceRow
    const request = requestReturning([
      { ...deviceRow, id: 18, serial: 'CAM18', name: null },
      { ...deviceRow, id: 19, serial: 'CAM19', name: '' },
      { ...withoutName, id: 20, serial: 'CAM20' },
    ])

    await expect(listDevices(request)).resolves.toMatchObject([
      { id: '18', serial: 'CAM18', name: '' },
      { id: '19', serial: 'CAM19', name: '' },
      { id: '20', serial: 'CAM20', name: '' },
    ])
  })

  it('treats the database seed location prompt as missing data', () => {
    expect(mapDevice({ ...deviceRow, location: '请填写 CAM17 的现场位置' }).location).toBe('')
  })

  it('uses the same defaults as the backend scheduler for nullable device settings', () => {
    expect(mapDevice({
      ...deviceRow,
      channel_no: null,
      hd: null,
      infer_interval_sec: null,
      status: null,
    })).toMatchObject({ channelNo: 1, hd: 0, inferIntervalSec: 60, status: 1 })
  })

  it('uses standard definition for the backend-restricted camera', () => {
    expect(mapDevice({ ...deviceRow, serial: 'G04690603', hd: 1 }).hd).toBe(0)
    expect(normalizeDeviceDraft({ ...draft, serial: 'g04690603', hd: 1 }, [])).toMatchObject({ serial: 'G04690603', hd: 0 })
  })

  it('rejects a device row missing its required creation time', () => {
    const { created_at: _createdAt, ...withoutCreatedAt } = deviceRow
    expect(() => mapDevice(withoutCreatedAt)).toThrow()
  })

  it('normalizes a draft from the editor before creating it', async () => {
    const request = requestReturning(null)
    expect(normalizeDeviceDraft(draft, [])).toEqual({ ...draft, serial: 'CAM17', name: '北闸', location: '一号渠' })
    await saveDeviceRequest(draft, undefined, request)
    expect(request).toHaveBeenCalledWith('/api/device', {
      method: 'POST',
      body: JSON.stringify({
        serial: 'CAM17', name: '北闸', location: '一号渠', channelNo: 2,
        hd: 0, deviceOtherParams: 'frame_step=6.25', inferIntervalSec: 60, status: 1,
      }),
    })
  })

  it('allows the optional backend device name to be blank', () => {
    expect(normalizeDeviceDraft({ ...draft, name: '  ' }, [])).toMatchObject({ serial: 'CAM17', name: '' })
  })

  it.each([
    ['serial', { serial: 'A'.repeat(65) }],
    ['serial after uppercase normalization', { serial: `${'A'.repeat(63)}ß` }],
    ['name', { name: '水'.repeat(129) }],
    ['location', { location: '水'.repeat(257) }],
  ])('rejects an overlong device %s before sending it', (_field, values) => {
    expect(() => normalizeDeviceDraft({ ...draft, ...values }, [])).toThrow()
  })

  it.each([
    ['channelNo', { channelNo: 2_147_483_648 }],
    ['inferIntervalSec', { inferIntervalSec: 2_147_483_648 }],
  ])('rejects %s values outside the backend integer range', (_field, values) => {
    expect(() => normalizeDeviceDraft({ ...draft, ...values }, [])).toThrow()
  })

  it('sends the exact device update contract', async () => {
    const request = requestReturning(null)
    await saveDeviceRequest(draft, '17', request)
    expect(request).toHaveBeenCalledWith('/api/device/17', {
      method: 'PUT',
      body: JSON.stringify({
        serial: 'CAM17', name: '北闸', location: '一号渠', channelNo: 2,
        hd: 0, deviceOtherParams: 'frame_step=6.25', inferIntervalSec: 60, status: 1,
      }),
    })
  })

  it('rejects invalid device IDs before a request', async () => {
    const request = requestReturning(null)
    await expect(deleteDeviceRequest('17/../18', request)).rejects.toThrow()
    await expect(saveDeviceRequest(draft, '', request)).rejects.toThrow()
    expect(request).not.toHaveBeenCalled()
  })

  it('serializes history dates as Beijing LocalDateTime', async () => {
    const request = requestReturning([measurementRow])
    await expect(listDeviceHistory(device, filter, request)).resolves.toMatchObject([{ id: '41', waterLevel: null, velocity: 0.41 }])
    expect(request).toHaveBeenCalledWith(
      '/api/measurement/history?deviceId=17&start=2026-09-09T10%3A00%3A00&end=2026-09-09T11%3A00%3A00',
    )
  })

  it('forwards the caller abort signal to a history request', async () => {
    const request = requestReturning([])
    const signal = new AbortController().signal

    await listDeviceHistory(device, filter, request, signal)

    expect(request).toHaveBeenCalledWith(
      '/api/measurement/history?deviceId=17&start=2026-09-09T10%3A00%3A00&end=2026-09-09T11%3A00%3A00',
      { signal },
    )
  })

  it('uses the exact latest and runtime contracts, including the EZVIZ envelope payload', async () => {
    const latest = requestReturning(measurementRow)
    await expect(getLatestMeasurement(device, latest)).resolves.toMatchObject({ id: '41', measuredAt: '2026-09-09T02:00:00.000Z' })
    expect(latest).toHaveBeenCalledWith('/api/measurement/latest?deviceId=17')

    const runtime = requestReturning({
      serial: 'CAM17', running: true, lastStarted: '2026-09-09T09:59:00', lastSuccess: '2026-09-09T10:00:00', error: 'EzvizApiException: appKey missing', measurement: measurementRow,
    })
    await expect(getRuntimeStatus(device, runtime)).resolves.toMatchObject({
      lastSuccess: '2026-09-09T02:00:00.000Z', error: '设备采样异常，请联系系统管理员。', measurement: { id: 'live:17:2026-09-09T02:00:00.000Z' },
    })
    expect(runtime).toHaveBeenCalledWith('/api/ezviz/measurements/latest?serial=CAM17')
  })

  it('ignores an in-memory measurement until the backend publishes its timestamp', async () => {
    const runtime = requestReturning({
      serial: 'CAM17', running: true, lastSuccess: null, error: null,
      measurement: { waterLevel: 1.2, velocity: 0.4, discharge: 0.8 },
    })
    await expect(getRuntimeStatus(device, runtime)).resolves.toEqual({ lastSuccess: null, measurement: null, error: '' })
  })

  it('returns null for absent latest measurements and rejects reversed history ranges', async () => {
    await expect(getLatestMeasurement(device, requestReturning(null))).resolves.toBeNull()
    expect(() => historyParams({ ...filter, end: filter.start })).toThrow()
  })

  it('validates the date range before the platform expands an all-device history filter', () => {
    expect(historyParams({ ...filter, deviceId: '' }).toString()).toBe(
      'deviceId=&start=2026-09-09T10%3A00%3A00&end=2026-09-09T11%3A00%3A00',
    )
  })

  it('exports device history with the identical query and caller signal', async () => {
    const output = new Blob(['serial\\r\\nCAM17\\r\\n'], { type: 'text/csv' })
    const blob = vi.fn(async () => output) as unknown as ReturnType<typeof vi.fn> & BlobRequest
    const signal = new AbortController().signal
    await expect(exportDeviceHistory(device, filter, blob, signal)).resolves.toBe(output)
    expect(blob).toHaveBeenCalledWith(
      '/api/measurement/export?deviceId=17&start=2026-09-09T10%3A00%3A00&end=2026-09-09T11%3A00%3A00',
      { signal },
    )
  })

  it('maps alarms, lists them, and protects unknown backend enum values', async () => {
    const row = { id: 8, device_id: 17, type: 'DEBRIS', status: 'OPEN', detail: '漂浮物', snapshot_path: 'private/path.jpg', created_at: '2026-09-09 10:00:00', handled_at: null }
    const request = requestReturning([row])
    const alarm = mapAlarm(row, [device])
    expect(alarm).toMatchObject({ id: '8', deviceId: '17', snapshotUrl: '/api/alarm/8/snapshot' })
    expect(alarm).not.toHaveProperty('measurement')
    await expect(listAlarms([device], request)).resolves.toHaveLength(1)
    expect(request).toHaveBeenCalledWith('/api/alarm')
    expect(() => mapAlarm({ ...row, type: 'NEW_TYPE' }, [device])).toThrow()
    expect(() => mapAlarm({ ...row, status: 'CLOSED' }, [device])).toThrow()
  })

  it('uses the real device serial when a legacy backend device has no name', async () => {
    const unnamedDevice = { ...device, name: '' }
    const alarmRow = { id: 8, device_id: 17, type: 'DEBRIS', status: 'OPEN', detail: '', created_at: '2026-09-09 10:00:00' }

    await expect(getLatestMeasurement(unnamedDevice, requestReturning(measurementRow))).resolves.toMatchObject({ deviceName: 'CAM17' })
    expect(mapAlarm(alarmRow, [unnamedDevice])).toMatchObject({ deviceName: 'CAM17' })
  })

  it('posts only supported alarm actions and requests its snapshot as a blob', async () => {
    const request = requestReturning(null)
    const image = new Blob(['image'], { type: 'image/jpeg' })
    const blob = vi.fn(async () => image) as unknown as ReturnType<typeof vi.fn> & BlobRequest
    await handleAlarmRequest('8', 'CONFIRMED', request)
    expect(request).toHaveBeenCalledWith('/api/alarm/8/handle', { method: 'POST', body: JSON.stringify({ action: 'CONFIRMED' }) })
    await expect(getAlarmSnapshot('8', blob)).resolves.toBe(image)
    expect(blob).toHaveBeenCalledWith('/api/alarm/8/snapshot')
    await expect(handleAlarmRequest('8', 'OPEN' as never, request)).rejects.toThrow()
  })

  it('validates raw EZVIZ play responses and sends the exact video request', async () => {
    const request = requestReturning({ url: 'https://camera.example/live.m3u8?token=secret', expireSeconds: 120, quality: 'hd', protocol: 'hls', ignored: 'field' })
    await expect(getPlayAddress(device, undefined, request, 'https:')).resolves.toEqual({ url: 'https://camera.example/live.m3u8?token=secret', expireSeconds: 120, quality: 'hd', protocol: 'hls' })
    expect(request).toHaveBeenCalledWith('/api/ezviz/play-url?serial=CAM17&channel=2&protocol=hls&hd=false', { signal: undefined })
  })

  it('rejects invalid and HTTPS mixed-content video URLs without exposing signed URLs', async () => {
    await expect(getPlayAddress(device, undefined, requestReturning({ url: 'javascript:alert(1)', expireSeconds: 1, quality: 'sd', protocol: 'hls' }), 'http:')).rejects.toThrow()
    await expect(getPlayAddress(device, undefined, requestReturning({ url: 'http://camera.example/live.m3u8?token=secret', expireSeconds: 1, quality: 'sd', protocol: 'hls' }), 'https:')).rejects.toThrow()
    await expect(getPlayAddress(device, undefined, requestReturning({ url: 'https://operator:secret@camera.example/live.m3u8', expireSeconds: 1, quality: 'sd', protocol: 'hls' }), 'https:')).rejects.toThrow()
    expect(safeVideoError(new Error('failed https://camera.example/live.m3u8?token=secret'), '备用错误')).toBe('failed [视频地址]')
  })

  it('captures a frame using the device serial and channel', async () => {
    const image = new Blob(['image'], { type: 'image/jpeg' })
    const blob = vi.fn(async () => image) as unknown as ReturnType<typeof vi.fn> & BlobRequest
    const signal = new AbortController().signal
    await expect(captureFrame(device, signal, blob)).resolves.toBe(image)
    expect(blob).toHaveBeenCalledWith('/api/ezviz/capture?serial=CAM17&channel=2', { signal })
  })
})
