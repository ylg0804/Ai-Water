import { nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Device } from '../../src/domain/models'
import type { PlayAddress } from '../../src/services/api/video'
import { useLiveVideo, type HlsAdapter } from '../../src/composables/useLiveVideo'

const firstDevice: Device = { id: '17', serial: 'CAM17', name: '北闸', location: '一号渠', channelNo: 2, hd: 1, deviceOtherParams: '', inferIntervalSec: 60, status: 1, online: null, createdAt: '2026-09-09T02:00:00Z' }
const secondDevice: Device = { ...firstDevice, id: '18', serial: 'CAM18' }
const validAddress: PlayAddress = { url: 'https://camera.example/live.m3u8?token=secret', expireSeconds: 120, quality: 'hd', protocol: 'hls' }
const playbackChanges: Array<[string, Device]> = [
  ['id', { ...firstDevice, id: '18' }],
  ['serial', { ...firstDevice, serial: 'CAM18' }],
  ['channel', { ...firstDevice, channelNo: 3 }],
  ['quality', { ...firstDevice, hd: 0 }],
  ['status', { ...firstDevice, status: 0 }],
]

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(onResolve => { resolve = onResolve })
  return { promise, resolve }
}

function videoStub(native = true) {
  return {
    src: '', error: null,
    canPlayType: vi.fn(() => native ? 'maybe' : ''),
    play: vi.fn(async () => {}), pause: vi.fn(), removeAttribute: vi.fn(), load: vi.fn(), requestFullscreen: vi.fn(async () => {}),
  } as unknown as HTMLVideoElement
}

function hlsHarness() {
  const handlers = new Map<string, (event: unknown, data?: { fatal?: boolean; type?: string; response?: { code?: number } }) => void>()
  const player = { attachMedia: vi.fn(), loadSource: vi.fn(), on: vi.fn((event: string, callback) => { handlers.set(event, callback) }), destroy: vi.fn() }
  const adapter: HlsAdapter = {
    isSupported: () => true,
    create: () => player,
    events: { mediaAttached: 'attached', manifestParsed: 'parsed', error: 'error' },
    errorTypes: { network: 'network', media: 'media' },
  }
  return { adapter, player, emit: (event: string, data?: { fatal?: boolean; type?: string; response?: { code?: number } }) => handlers.get(event)?.(event, data) }
}

afterEach(() => vi.useRealTimers())

describe('useLiveVideo', () => {
  it('does not request a stream for a disabled device and uses native HLS when available', async () => {
    const device = ref<Device | undefined>({ ...firstDevice, status: 0 })
    const video = ref(videoStub())
    const getPlayAddress = vi.fn(async () => validAddress)
    const live = useLiveVideo({ device, video, getPlayAddress })

    await live.loadStream()
    expect(getPlayAddress).not.toHaveBeenCalled()
    device.value = firstDevice
    await live.loadStream()
    expect(getPlayAddress).toHaveBeenCalledWith(firstDevice, expect.any(AbortSignal))
    expect(video.value?.src).toBe(validAddress.url)
    live.nativeReady()
    await Promise.resolve()
    expect(live.ready.value).toBe(true)
    expect(live.quality.value).toBe('高清')
  })

  it('cancels the previous play-address request after a device change', async () => {
    const first = deferred<PlayAddress>()
    let firstSignal!: AbortSignal
    const getPlayAddress = vi.fn()
      .mockImplementationOnce(async (_device: Device, signal: AbortSignal) => { firstSignal = signal; return first.promise })
      .mockResolvedValueOnce(validAddress)
    const device = ref<Device | undefined>(firstDevice)
    const video = ref(videoStub())
    const live = useLiveVideo({ device, video, getPlayAddress })

    const pending = live.loadStream()
    device.value = secondDevice
    await nextTick()
    first.resolve(validAddress)
    await pending

    expect(firstSignal.aborted).toBe(true)
    expect(getPlayAddress).toHaveBeenLastCalledWith(secondDevice, expect.any(AbortSignal))
    expect(video.value?.src).toBe(validAddress.url)
  })

  it('ignores an equivalent polling replacement but clears and reloads when playback fields change', async () => {
    const device = ref<Device | undefined>(firstDevice)
    const video = ref(videoStub())
    const getPlayAddress = vi.fn(async () => validAddress)
    const live = useLiveVideo({ device, video, getPlayAddress })
    await live.loadStream()
    const pause = vi.mocked(video.value!.pause)
    pause.mockClear()

    device.value = { ...firstDevice }
    await nextTick()
    expect(getPlayAddress).toHaveBeenCalledTimes(1)
    expect(pause).not.toHaveBeenCalled()

    device.value = { ...firstDevice, status: 0 }
    await nextTick()
    expect(pause).toHaveBeenCalledOnce()
    expect(getPlayAddress).toHaveBeenCalledTimes(1)

    device.value = { ...firstDevice, channelNo: 3 }
    await nextTick()
    expect(getPlayAddress).toHaveBeenCalledTimes(2)
  })

  it.each(playbackChanges)('cleans the current player when the device %s changes', async (_field, nextDevice) => {
    const hls = hlsHarness()
    const device = ref<Device | undefined>(firstDevice)
    const getPlayAddress = vi.fn(async () => validAddress)
    const live = useLiveVideo({ device, video: ref(videoStub(false)), getPlayAddress, hlsAdapter: hls.adapter })
    await live.loadStream()
    hls.player.destroy.mockClear()

    device.value = nextDevice
    await nextTick()

    expect(hls.player.destroy).toHaveBeenCalledOnce()
    expect(getPlayAddress).toHaveBeenCalledTimes(nextDevice.status === 1 ? 2 : 1)
    live.dispose()
  })

  it('uses hls.js when native HLS is unavailable and reports fatal playback errors', async () => {
    const hls = hlsHarness()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub(false)), getPlayAddress: vi.fn(async () => validAddress), hlsAdapter: hls.adapter })

    await live.loadStream()
    expect(hls.player.attachMedia).toHaveBeenCalled()
    hls.emit('attached')
    expect(hls.player.loadSource).toHaveBeenCalledWith(validAddress.url)
    hls.emit('parsed')
    expect(live.ready.value).toBe(true)
    hls.emit('error', { fatal: true, type: 'network', response: { code: 403 } })
    expect(live.error.value).not.toContain('token=secret')
    expect(hls.player.destroy).toHaveBeenCalled()
  })

  it('renews an expiring stream and gives an autoplay hint', async () => {
    vi.useFakeTimers()
    const video = videoStub()
    ;(video.play as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('blocked'))
    const getPlayAddress = vi.fn(async () => ({ ...validAddress, expireSeconds: 2 }))
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(video), getPlayAddress })

    await live.loadStream()
    live.nativeReady()
    await Promise.resolve()
    expect(live.playbackHint.value).not.toBe('')
    await vi.advanceTimersByTimeAsync(1_800)
    expect(getPlayAddress).toHaveBeenCalledTimes(2)
  })

  it('enforces a four-second capture cooldown and downloads only valid images with a safe file name', async () => {
    vi.useFakeTimers()
    vi.spyOn(Date, 'now').mockReturnValue(123)
    const download = vi.fn(() => () => {})
    const captureFrame = vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' }))
    const live = useLiveVideo({ device: ref({ ...firstDevice, serial: 'CAM/17' }), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), captureFrame, download })

    await live.capture()
    await live.capture()
    expect(captureFrame).toHaveBeenCalledTimes(1)
    expect(download).toHaveBeenCalledWith(expect.any(Blob), 'CAM_17_123.jpg', expect.any(Function))
    await vi.advanceTimersByTimeAsync(4_000)
    await live.capture()
    expect(captureFrame).toHaveBeenCalledTimes(2)

    const invalidCapture = vi.fn()
      .mockResolvedValueOnce(new Blob(['text'], { type: 'text/plain' }))
      .mockResolvedValueOnce(new Blob(['image'], { type: 'image/jpeg' }))
    const invalid = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), captureFrame: invalidCapture, download })
    await invalid.capture()
    expect(invalid.error.value).toBe('')
    expect(invalid.captureNotice.value).toContain('抓拍图片无效')
    await vi.advanceTimersByTimeAsync(4_000)
    await invalid.capture()
    expect(invalid.captureNotice.value).toBe('')
  })

  it('keeps a newer capture marked in flight when an older aborted capture settles late', async () => {
    vi.useFakeTimers()
    const first = deferred<Blob>(), second = deferred<Blob>()
    const captureFrame = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const device = ref<Device | undefined>(firstDevice)
    const live = useLiveVideo({ device, video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), captureFrame, download: vi.fn(() => () => {}) })

    const captureA = live.capture()
    device.value = secondDevice
    await nextTick()
    await vi.advanceTimersByTimeAsync(4_000)
    const captureB = live.capture()
    first.resolve(new Blob(['old'], { type: 'image/jpeg' }))
    await captureA
    expect(live.capturing.value).toBe(true)
    second.resolve(new Blob(['new'], { type: 'image/jpeg' }))
    await captureB
    expect(live.capturing.value).toBe(false)
  })

  it('removes an automatically released download cleanup before dispose', async () => {
    vi.useFakeTimers()
    const cleanup = vi.fn()
    const download = vi.fn((_blob: Blob, _name: string, released?: () => void) => {
      setTimeout(() => { cleanup(); released?.() }, 1_000)
      return cleanup
    })
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), captureFrame: vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' })), download })

    await live.capture()
    await vi.advanceTimersByTimeAsync(1_000)
    live.dispose()

    expect(cleanup).toHaveBeenCalledTimes(1)
  })

  it('does not retain a download cleanup that reports synchronous release', async () => {
    const cleanup = vi.fn()
    const download = vi.fn((_blob: Blob, _name: string, released?: () => void) => {
      released?.()
      return cleanup
    })
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), captureFrame: vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' })), download })

    await live.capture()
    live.dispose()

    expect(cleanup).not.toHaveBeenCalled()
  })

  it('cleans up requests, HLS, renewal timers, and pending download URLs on dispose', async () => {
    vi.useFakeTimers()
    const hls = hlsHarness()
    const cleanupDownload = vi.fn()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub(false)), getPlayAddress: vi.fn(async () => validAddress), captureFrame: vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' })), hlsAdapter: hls.adapter, download: vi.fn(() => cleanupDownload) })

    await live.loadStream()
    await live.capture()
    live.dispose()
    await vi.advanceTimersByTimeAsync(120_000)

    expect(hls.player.destroy).toHaveBeenCalled()
    expect(cleanupDownload).toHaveBeenCalled()
    expect(live.ready.value).toBe(false)
  })
})
