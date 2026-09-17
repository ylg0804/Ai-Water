import { describe, expect, it } from 'vitest'
import { mapAlarm } from '../../../src/services/api/alarms'
import { parseServerDate } from '../../../src/services/api/contract'
import { mapDevice } from '../../../src/services/api/devices'
import { historyParams, mapMeasurement } from '../../../src/services/api/measurements'

const row = { id: 7, serial: 'CAM7', name: '渠首', channel_no: 1, hd: 0, status: 1, infer_interval_sec: 60, device_other_params: 'frame_step=6.25', created_at: '2026-09-09T10:00:00' }
describe('real backend contracts', () => {
  it('maps snake_case configuration without inventing an online status', () => {
    const d = mapDevice(row)
    expect(d).toMatchObject({ id: '7', serial: 'CAM7', channelNo: 1, online: null, deviceOtherParams: 'frame_step=6.25' })
  })
  it('keeps missing measurements empty instead of replacing them with zero', () => {
    expect(mapMeasurement({ id: 9, serial: 'CAM7', measured_at: '2026-09-09T10:00:00', water_level: null, velocity: .41, discharge: null }, mapDevice(row))).toMatchObject({ deviceId: '7', waterLevel: null, velocity: .41, discharge: null, measuredAt: '2026-09-09T02:00:00.000Z' })
  })
  it('publishes snapshot endpoints without inventing unsupported alarm fields', () => {
    const alarm = mapAlarm({ id: 4, device_id: 7, type: 'DEBRIS', status: 'OPEN', snapshot_path: 'CAM7/image.jpg', created_at: '2026-09-09T10:00:00' }, [mapDevice(row)])
    expect(alarm).not.toHaveProperty('measurement')
    expect(alarm.snapshotUrl).toBe('/api/alarm/4/snapshot')
  })
  it('understands epoch, UTC and naive Beijing dates', () => {
    expect(parseServerDate(1788919200000)).toBe(new Date(1788919200000).toISOString())
    expect(parseServerDate('2026-09-09T02:00:00Z')).toBe(parseServerDate('2026-09-09 10:00:00'))
    expect(() => parseServerDate('bad-date')).toThrow()
  })
  it('requests Beijing LocalDateTime and requires a strictly positive range', () => {
    const params = historyParams({ deviceId: '7', start: '2026-09-09T02:00:00Z', end: '2026-09-09T03:00:00Z' })
    expect(params.get('start')).toBe('2026-09-09T10:00:00')
    expect(params.get('end')).toBe('2026-09-09T11:00:00')
    expect(() => historyParams({ deviceId: '7', start: '2026-09-09', end: '2026-09-09' })).toThrow()
  })
})
