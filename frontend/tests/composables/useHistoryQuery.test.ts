import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useHistoryQuery } from '../../src/composables/useHistoryQuery'
import type { Device, Measurement } from '../../src/domain/models'

function device(id: string): Device {
  return {
    id, serial: `CAM${id}`, name: `Camera ${id}`, location: '', channelNo: 1,
    hd: 0, deviceOtherParams: '', inferIntervalSec: 60, status: 1,
    online: null, createdAt: '2026-09-09T01:00:00Z',
  }
}

function measurement(id: string, waterLevel: number | null, deviceId = '7'): Measurement {
  return { id, deviceId, deviceName: `Camera ${deviceId}`, measuredAt: '2026-09-09T01:00:00Z', waterLevel, velocity: null, discharge: null }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('useHistoryQuery', () => {
  it('loads immediately and keeps the newer result when the older query resolves last', async () => {
    const first = deferred<Measurement[]>(), second = deferred<Measurement[]>()
    const history = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = useHistoryQuery({ devices: ref([device('7')]), history })
    expect(state.loading.value).toBe(true)
    state.deviceId.value = '7'
    const newest = state.query()
    second.resolve([measurement('new', 2)])
    await newest
    first.resolve([measurement('old', 1)])
    await state.initialQuery
    expect(state.rows.value.map(row => row.id)).toEqual(['new'])
    expect(state.loading.value).toBe(false)
  })

  it('aborts the previous query and the active query on disposal', async () => {
    const calls: Array<{ signal?: AbortSignal; request: ReturnType<typeof deferred<Measurement[]>> }> = []
    const history = vi.fn((_filter, signal?: AbortSignal) => {
      const request = deferred<Measurement[]>()
      calls.push({ signal, request })
      signal?.addEventListener('abort', () => request.reject(new DOMException('Aborted', 'AbortError')), { once: true })
      return request.promise
    })
    const state = useHistoryQuery({ devices: ref([device('7')]), history })
    await vi.waitFor(() => expect(calls).toHaveLength(1))

    const newest = state.query()
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[0].signal?.aborted).toBe(true)

    state.dispose()
    expect(calls[1].signal?.aborted).toBe(true)
    await Promise.allSettled([state.initialQuery, newest])
  })

  it('clears stale rows when the newest query fails', async () => {
    const history = vi.fn().mockResolvedValueOnce([measurement('old', 2)]).mockRejectedValueOnce(new Error('backend unavailable'))
    const state = useHistoryQuery({ devices: ref([device('7')]), history })
    await state.initialQuery
    await state.query()
    expect(state.rows.value).toEqual([])
    expect(state.error.value).toBe('backend unavailable')
    expect(state.metricAverage.value).toBeNull()
  })

  it('includes a reported zero in the average and excludes missing values', async () => {
    const state = useHistoryQuery({
      devices: ref([device('7')]),
      history: vi.fn().mockResolvedValue([measurement('missing', null), measurement('zero', 0), measurement('valid', 2)]),
    })
    await state.initialQuery
    expect(state.metricAverage.value).toBe(1)
  })

  it('rejects invalid dates without discarding the applied result', async () => {
    const history = vi.fn().mockResolvedValue([measurement('current', 1)])
    const state = useHistoryQuery({ devices: ref([device('7')]), history })
    await state.initialQuery
    const applied = { ...state.applied.value }
    history.mockClear()
    const date = new Date('2026-09-09T01:00:00Z')
    state.dateRange.value = [date, date]
    await state.query()
    expect(history).not.toHaveBeenCalled()
    expect(state.error.value).toMatch(/结束时间/)
    expect(state.rows.value.map(row => row.id)).toEqual(['current'])
    expect(state.applied.value).toEqual(applied)
  })

  it('exports all selected devices with at most four requests in flight', async () => {
    const devices = ref(['1', '2', '3', '4', '5'].map(device))
    const waiting = new Map(devices.value.map(row => [row.id, deferred<Blob>()]))
    const exportHistory = vi.fn((row: Device) => waiting.get(row.id)!.promise)
    const download = vi.fn()
    const state = useHistoryQuery({ devices, history: vi.fn().mockResolvedValue([measurement('row', 1)]), exportHistory, download })
    await state.initialQuery
    const exporting = state.exportRows()
    expect(exportHistory).toHaveBeenCalledTimes(4)
    for (const row of devices.value.slice(0, 4)) waiting.get(row.id)!.resolve(new Blob(['id\r\n', `${row.id}\r\n`]))
    await vi.waitFor(() => expect(exportHistory).toHaveBeenCalledTimes(5))
    waiting.get('5')!.resolve(new Blob(['id\r\n5\r\n']))
    await exporting
    expect(download).toHaveBeenCalledOnce()
  })

  it('merges exported CSV bytes with one header only after every export succeeds', async () => {
    const download = vi.fn()
    const first = new Blob(['\uFEFFserial,water\r\n"CAM,7",0\r\n'])
    const second = new Blob(['serial,water\r\nCAM8,2\r\n'])
    const state = useHistoryQuery({
      devices: ref([device('7'), device('8')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      download,
    })
    await state.initialQuery
    await state.exportRows()
    const bytes = new Uint8Array(await (download.mock.calls[0][0] as Blob).arrayBuffer())
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf])
    expect(new TextDecoder().decode(bytes.slice(3))).toBe('serial,water\r\n"CAM,7",0\r\nCAM8,2\r\n')
  })

  it('exports small metric values as plain decimals without changing other CSV cells', async () => {
    const download = vi.fn()
    const header = 'serial,measured_at,water_level,velocity,discharge\r\n'
    const first = new Blob(['\uFEFF', header, '"7.09315E-4,""CAM""","2026-09-18 14:21:00","0.9490","7.09315E-4","0.0027"\r\n'])
    const second = new Blob([header, '"CAM8","2026-09-18 14:22:00","1.2E-3","0","1.3e-6"\r\n'])
    const state = useHistoryQuery({
      devices: ref([device('7'), device('8')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      download,
    })
    await state.initialQuery
    await state.exportRows()

    const bytes = new Uint8Array(await (download.mock.calls[0][0] as Blob).arrayBuffer())
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf])
    expect(new TextDecoder().decode(bytes.slice(3))).toBe(
      header + '"7.09315E-4,""CAM""","2026-09-18 14:21:00","0.9490","0.000709315","0.0027"\r\n'
      + '"CAM8","2026-09-18 14:22:00","0.0012","0","0.0000013"\r\n',
    )
  })

  it('rejects inconsistent CSV headers without downloading a file', async () => {
    const download = vi.fn()
    const state = useHistoryQuery({
      devices: ref([device('7'), device('8')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValueOnce(new Blob(['serial,water\r\nCAM7,1\r\n']))
        .mockResolvedValueOnce(new Blob(['serial,velocity\r\nCAM8,2\r\n'])),
      download,
    })
    await state.initialQuery
    await state.exportRows()
    expect(download).not.toHaveBeenCalled()
    expect(state.error.value).toBe('设备导出文件的列不一致，请分别导出。')
  })

  it('requires every applied device to remain available before starting an export', async () => {
    const devices = ref([device('7'), device('8')])
    const exportHistory = vi.fn()
    const download = vi.fn()
    const state = useHistoryQuery({ devices, history: vi.fn().mockResolvedValue([measurement('row', 1)]), exportHistory, download })
    await state.initialQuery
    devices.value = [device('7')]
    await state.exportRows()
    expect(exportHistory).not.toHaveBeenCalled()
    expect(download).not.toHaveBeenCalled()
    expect(state.error.value).toBe('设备列表已变更，请重新查询后再导出。')
  })

  it('does not download a partial CSV when one export fails', async () => {
    const download = vi.fn()
    const state = useHistoryQuery({
      devices: ref([device('7'), device('8')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValueOnce(new Blob(['id\r\n7\r\n'])).mockRejectedValueOnce(new Error('export unavailable')),
      download,
    })
    await state.initialQuery
    await state.exportRows()
    expect(download).not.toHaveBeenCalled()
    expect(state.error.value).toBe('export unavailable')
  })

  it('revokes each created object URL on disposal', async () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:history')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const anchor = { href: '', download: '', click: vi.fn(), remove: vi.fn() }
    vi.stubGlobal('document', { createElement: vi.fn(() => anchor), body: { appendChild: vi.fn() } })
    const state = useHistoryQuery({
      devices: ref([device('7')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValue(new Blob(['id\r\n7\r\n'])),
    })
    await state.initialQuery
    await state.exportRows()
    expect(create).toHaveBeenCalledOnce()
    state.dispose()
    expect(revoke).toHaveBeenCalledWith('blob:history')
  })

  it('clicks an attached download link and keeps its object URL alive while the download starts', async () => {
    vi.useFakeTimers()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:history')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const appendChild = vi.fn()
    const anchor = { href: '', download: '', click: vi.fn(() => expect(appendChild).toHaveBeenCalledWith(anchor)), remove: vi.fn() }
    vi.stubGlobal('document', { createElement: vi.fn(() => anchor), body: { appendChild } })
    const state = useHistoryQuery({
      devices: ref([device('7')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValue(new Blob(['id\r\n7\r\n'])),
    })
    await state.initialQuery
    await state.exportRows()

    expect(anchor.click).toHaveBeenCalledOnce()
    expect(anchor.remove).toHaveBeenCalledOnce()
    expect(revoke).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(999)
    expect(revoke).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(revoke).toHaveBeenCalledWith('blob:history')
    state.dispose()
  })

  it('releases the object URL and reports the error when the browser rejects the download click', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:history')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const anchor = { href: '', download: '', click: vi.fn(() => { throw new Error('浏览器拒绝下载') }), remove: vi.fn() }
    vi.stubGlobal('document', { createElement: vi.fn(() => anchor), body: { appendChild: vi.fn() } })
    const state = useHistoryQuery({
      devices: ref([device('7')]),
      history: vi.fn().mockResolvedValue([measurement('row', 1)]),
      exportHistory: vi.fn().mockResolvedValue(new Blob(['id\r\n7\r\n'])),
    })
    await state.initialQuery
    await state.exportRows()

    expect(state.error.value).toBe('浏览器拒绝下载')
    expect(anchor.remove).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith('blob:history')
    state.dispose()
    expect(revoke).toHaveBeenCalledOnce()
  })
})
