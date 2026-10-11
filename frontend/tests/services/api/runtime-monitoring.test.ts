import { describe, expect, it } from 'vitest'
import { mapRuntimeStatus } from '../../../src/services/api/measurements'
import { mapDevice } from '../../../src/services/api/devices'

const device = mapDevice({ id: 1, serial: 'CAM', created_at: '2026-10-08T00:00:00' })
describe('runtime monitoring', () => {
  it('preserves failure counters and the last failure after recovery without exposing stream URLs', () => {
    const status = mapRuntimeStatus({ failureCount: 3, consecutiveFailures: 0, lastSuccess: '2026-10-08T01:00:00Z',
      lastFailureAt: '2026-10-08T00:59:00Z', lastFailureReason: 'InvalidFrameException: source https://camera.example/live?token=secret failed', error: null }, device)
    expect(status.failureCount).toBe(3)
    expect(status.consecutiveFailures).toBe(0)
    expect(status.lastFailureAt).toBe('2026-10-08T00:59:00.000Z')
    expect(status.lastFailureReason).toContain('[视频地址]')
    expect(status.lastFailureReason).not.toContain('secret')
    expect(status.error).toBe('')
  })
  it('marks archived devices without changing the contract of old backend rows', () => {
    expect(mapDevice({ id: 2, serial: 'OLD', created_at: '2026-10-07T00:00:00', deleted_at: '2026-10-08T00:00:00' }).archived).toBe(true)
    expect(device.archived).toBeUndefined()
  })
})
