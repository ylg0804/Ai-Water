import { nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Device } from '../../src/domain/models'
import type { PlayAddress } from '../../src/services/api/video'
import { useLiveVideo, type HlsAdapter } from '../../src/composables/useLiveVideo'

vi.mock('../../src/services/api/video', async importOriginal => ({
  ...await importOriginal<typeof import('../../src/services/api/video')>(),
  reportPlayback: vi.fn(async () => {}),
}))

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
  it('starts detection only after playing, renews it, and stops on stalls, pauses and dispose', async () => {
    vi.useFakeTimers()
    const reportPlayback = vi.fn(async () => {})
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), reportPlayback })
    await live.loadStream()
    live.nativeReady()
    live.onLoadedData()
    expect(reportPlayback).not.toHaveBeenCalled()
    live.onPlaying()
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 1, true)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 2, true)
    live.onWaiting()
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 3, false)
    live.onPlaying()
    live.onPaused()
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 5, false)
    live.onPlaying()
    live.dispose()
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 7, false)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(reportPlayback).toHaveBeenCalledTimes(7)
  })

  it('stops detection for the old camera when switching and waits for the new camera to play', async () => {
    const reportPlayback = vi.fn(async () => {})
    const device = ref(firstDevice)
    const live = useLiveVideo({ device, video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress), reportPlayback })
    await live.loadStream()
    live.onPlaying()
    device.value = secondDevice
    await nextTick()
    expect(reportPlayback).toHaveBeenLastCalledWith(firstDevice, expect.any(String), 2, false)
    live.onPlaying()
    expect(reportPlayback).toHaveBeenLastCalledWith(secondDevice, expect.any(String), 3, true)
    live.dispose()
  })
  it('does not extend the deadline when a stalled stream repeatedly emits waiting events', async () => {
    vi.useFakeTimers()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress) })
    await live.loadStream()
    live.onPlaying()
    live.onWaiting()
    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(4_000)
      live.onWaiting()
    }
    await vi.advanceTimersByTimeAsync(4_000)
    expect(live.error.value).toContain('超时')
    expect(live.ready.value).toBe(false)
    live.dispose()
  })
  it('times out a stream that has a playlist but never produces playable frames and allows retry', async () => {
    vi.useFakeTimers()
    const hls = hlsHarness()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub(false)), getPlayAddress: vi.fn(async () => validAddress), hlsAdapter: hls.adapter })
    await live.loadStream()
    hls.emit('parsed')
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.loading.value).toBe(false)
    expect(live.error.value).toContain('并发观看限制')
    expect(live.ready.value).toBe(false)
    hls.emit('parsed') // Late callbacks cannot revive the failed player.
    expect(live.ready.value).toBe(false)
    await live.loadStream()
    expect(live.error.value).toBe('')
    expect(live.loading.value).toBe(true)
    live.dispose()
  })

  it('aborts a stalled address request and ignores its late response', async () => {
    vi.useFakeTimers()
    const request = deferred<PlayAddress>()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: () => request.promise })
    const pending = live.loadStream()
    await vi.advanceTimersByTimeAsync(20_000)
    request.resolve(validAddress)
    await pending
    expect(live.error.value).toContain('超时')
    expect(live.ready.value).toBe(false)
    live.dispose()
  })

  it('cancels connection timeout after playback and reports a later stalled stream', async () => {
    vi.useFakeTimers()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress) })
    await live.loadStream()
    live.onPlaying()
    await vi.advanceTimersByTimeAsync(25_000)
    expect(live.error.value).toBe('')
    live.onWaiting()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.error.value).toContain('超时')
    live.dispose()
  })

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

  it('times out a play-address request, allows retry, and ignores its late response', async () => {
    vi.useFakeTimers()
    const first = deferred<PlayAddress>()
    const getPlayAddress = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce({ ...validAddress, url: 'https://camera.example/retry.m3u8' })
    const video = ref(videoStub())
    const live = useLiveVideo({ device: ref(firstDevice), video, getPlayAddress })

    const oldAttempt = live.loadStream()
    const oldSignal = getPlayAddress.mock.calls[0][1] as AbortSignal
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.loading.value).toBe(false)
    expect(live.ready.value).toBe(false)
    expect(live.error.value).toContain('连接超时')
    expect(oldSignal.aborted).toBe(true)

    await live.loadStream()
    live.nativeReady()
    first.resolve(validAddress)
    await oldAttempt
    expect(video.value?.src).toBe('https://camera.example/retry.m3u8')
    expect(live.ready.value).toBe(true)
    expect(live.error.value).toBe('')
    live.dispose()
  })

  it('ignores an autoplay rejection that arrives after connection timeout', async () => {
    vi.useFakeTimers()
    let rejectPlay!: (reason: Error) => void
    const pendingPlay = new Promise<void>((_resolve, reject) => { rejectPlay = reject })
    const video = videoStub()
    video.play = vi.fn(() => pendingPlay)
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(video), getPlayAddress: vi.fn(async () => validAddress) })

    await live.loadStream()
    live.nativeReady()
    await vi.advanceTimersByTimeAsync(20_000)
    rejectPlay(new Error('late autoplay rejection'))
    await Promise.resolve()
    expect(live.error.value).toContain('连接超时')
    expect(live.playbackHint.value).toBe('')
    live.dispose()
  })

  it('times out native playback when no first frame arrives and ignores late media events', async () => {
    vi.useFakeTimers()
    const video = ref(videoStub())
    const getPlayAddress = vi.fn(async () => validAddress)
    const live = useLiveVideo({ device: ref(firstDevice), video, getPlayAddress })

    await live.loadStream()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.loading.value).toBe(false)
    expect(live.ready.value).toBe(false)
    expect(live.error.value).toContain('连接超时')
    expect(video.value?.removeAttribute).toHaveBeenCalledWith('src')
    live.nativeReady()
    expect(live.ready.value).toBe(false)

    await live.loadStream()
    expect(live.loading.value).toBe(true)
    live.nativeReady()
    expect(live.ready.value).toBe(true)
    expect(live.error.value).toBe('')
    live.dispose()
  })

  it('times out HLS playback if parsing never produces a frame and ignores late callbacks', async () => {
    vi.useFakeTimers()
    const hls = hlsHarness()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub(false)), getPlayAddress: vi.fn(async () => validAddress), hlsAdapter: hls.adapter })

    await live.loadStream()
    hls.emit('parsed')
    expect(live.ready.value).toBe(true)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.loading.value).toBe(false)
    expect(live.error.value).toContain('连接超时')
    expect(hls.player.destroy).toHaveBeenCalledOnce()
    hls.emit('parsed')
    live.nativeReady()
    expect(live.ready.value).toBe(false)
    live.dispose()
  })

  it('keeps a first frame ready after the connection deadline passes', async () => {
    vi.useFakeTimers()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(async () => validAddress) })

    await live.loadStream()
    live.nativeReady()
    live.onLoadedData()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.ready.value).toBe(true)
    expect(live.loading.value).toBe(false)
    expect(live.error.value).toBe('')
    live.dispose()
  })

  it('starts a fresh deadline after switching devices', async () => {
    vi.useFakeTimers()
    const device = ref<Device | undefined>(firstDevice)
    const getPlayAddress = vi.fn(async () => validAddress)
    const live = useLiveVideo({ device, video: ref(videoStub()), getPlayAddress })

    await live.loadStream()
    await vi.advanceTimersByTimeAsync(10_000)
    device.value = secondDevice
    await nextTick()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(live.loading.value).toBe(true)
    expect(live.error.value).toBe('')
    await vi.advanceTimersByTimeAsync(15_000)
    expect(live.error.value).toContain('连接超时')
    live.dispose()
  })

  it('applies the connection timeout to automatic renewal as a new attempt', async () => {
    vi.useFakeTimers()
    const renewal = deferred<PlayAddress>()
    const getPlayAddress = vi.fn()
      .mockResolvedValueOnce({ ...validAddress, expireSeconds: 2 })
      .mockReturnValueOnce(renewal.promise)
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress })

    await live.loadStream()
    live.nativeReady()
    await vi.advanceTimersByTimeAsync(1_800)
    expect(getPlayAddress).toHaveBeenCalledTimes(2)
    expect(live.loading.value).toBe(true)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(live.loading.value).toBe(false)
    expect(live.error.value).toContain('连接超时')
    renewal.resolve(validAddress)
    live.dispose()
  })

  it('renews a stream whose address is near expiry even before the first frame arrives', async () => {
    vi.useFakeTimers()
    const getPlayAddress = vi.fn()
      .mockResolvedValueOnce({ ...validAddress, expireSeconds: 2 })
      .mockResolvedValueOnce(validAddress)
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress })

    await live.loadStream()
    await vi.advanceTimersByTimeAsync(1_800)
    expect(getPlayAddress).toHaveBeenCalledTimes(2)
    live.dispose()
  })

  it('renews an expiring stream and gives an autoplay hint', async () => {
    vi.useFakeTimers()
    const video = videoStub()
    ;(video.play as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'))
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

  it('clears the pending connection deadline on dispose', async () => {
    vi.useFakeTimers()
    const pending = deferred<PlayAddress>()
    const live = useLiveVideo({ device: ref(firstDevice), video: ref(videoStub()), getPlayAddress: vi.fn(() => pending.promise) })

    void live.loadStream()
    expect(vi.getTimerCount()).toBe(1)
    live.dispose()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(live.error.value).toBe('')
  })
})
