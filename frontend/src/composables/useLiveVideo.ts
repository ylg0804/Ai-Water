import { ref, watch, type Ref } from 'vue'
import Hls from 'hls.js'
import type { Device } from '../domain/models'
import { captureFrame as requestCaptureFrame, getPlayAddress as requestPlayAddress, safeVideoError, type PlayAddress } from '../services/api/video'

interface HlsPlayer {
  attachMedia(video: HTMLVideoElement): void
  loadSource(url: string): void
  on(event: string, callback: (event: unknown, data?: { fatal?: boolean; type?: string; response?: { code?: number } }) => void): void
  destroy(): void
}

export interface HlsAdapter {
  isSupported(): boolean
  create(): HlsPlayer
  events: { mediaAttached: string; manifestParsed: string; error: string }
  errorTypes: { network: string; media: string }
}

export interface UseLiveVideoOptions {
  device: Readonly<Ref<Device | undefined>>
  video: Ref<HTMLVideoElement | undefined>
  getPlayAddress?: (device: Device, signal?: AbortSignal) => Promise<PlayAddress>
  captureFrame?: (device: Device, signal?: AbortSignal) => Promise<Blob>
  hlsAdapter?: HlsAdapter
  pageProtocol?: string
  download?: (blob: Blob, fileName: string, onReleased?: () => void) => (() => void)
}

const defaultHlsAdapter: HlsAdapter = {
  isSupported: () => Hls.isSupported(),
  create: () => new Hls({ debug: false, enableWorker: true, backBufferLength: 30 }) as unknown as HlsPlayer,
  events: { mediaAttached: Hls.Events.MEDIA_ATTACHED, manifestParsed: Hls.Events.MANIFEST_PARSED, error: Hls.Events.ERROR },
  errorTypes: { network: Hls.ErrorTypes.NETWORK_ERROR, media: Hls.ErrorTypes.MEDIA_ERROR },
}

const CONNECTION_TIMEOUT_MS = 15_000

function downloadBlob(blob: Blob, fileName: string, onReleased?: () => void): () => void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.click()
  let isReleased = false
  const release = () => {
    if (isReleased) return
    isReleased = true
    clearTimeout(timer)
    URL.revokeObjectURL(url)
    onReleased?.()
  }
  const timer = setTimeout(release, 1_000)
  return release
}

export function useLiveVideo(options: UseLiveVideoOptions) {
  const loading = ref(false)
  const ready = ref(false)
  const playing = ref(false)
  const error = ref('')
  const playbackBlocked = ref(false)
  const playbackHint = ref('')
  const captureNotice = ref('')
  const quality = ref('')
  const capturing = ref(false)
  const captureCooldown = ref(false)
  const hlsAdapter = options.hlsAdapter ?? defaultHlsAdapter
  const getPlayAddress = options.getPlayAddress ?? ((device: Device, signal?: AbortSignal) => requestPlayAddress(device, signal, undefined, options.pageProtocol))
  const captureFrame = options.captureFrame ?? requestCaptureFrame
  const download = options.download ?? downloadBlob
  let generation = 0
  let player: HlsPlayer | undefined
  let requestController: AbortController | undefined
  let captureController: AbortController | undefined
  let connectionTimer: ReturnType<typeof setTimeout> | undefined
  let expiryTimer: ReturnType<typeof setTimeout> | undefined
  let renewalAt: number | undefined
  let cooldownTimer: ReturnType<typeof setTimeout> | undefined
  const downloadCleanups = new Set<() => void>()

  function enabled(): boolean { return options.device.value?.status === 1 }
  function playbackKey(): string {
    const device = options.device.value
    return device ? [device.id, device.serial, device.channelNo, device.hd, device.status].join('\u0000') : ''
  }

  function clearConnectionTimer() {
    if (connectionTimer !== undefined) clearTimeout(connectionTimer)
    connectionTimer = undefined
  }

  function startConnectionTimer(id: number) {
    clearConnectionTimer()
    connectionTimer = setTimeout(() => {
      fail(new Error('视频连接超时，请检查网络或摄像头观看人数后重新连接。'), id, '视频连接超时，请重新连接。')
    }, CONNECTION_TIMEOUT_MS)
  }

  function clearPlayer() {
    clearConnectionTimer()
    if (expiryTimer !== undefined) clearTimeout(expiryTimer)
    expiryTimer = undefined
    renewalAt = undefined
    player?.destroy()
    player = undefined
    ready.value = false
    playing.value = false
    playbackBlocked.value = false
    const element = options.video.value
    if (element) {
      element.pause()
      element.removeAttribute('src')
      element.load()
    }
  }

  function fail(value: unknown, id: number, fallback: string) {
    if (id !== generation) return
    requestController?.abort()
    requestController = undefined
    clearPlayer()
    loading.value = false
    error.value = safeVideoError(value, fallback)
    playbackHint.value = ''
    generation++
  }

  async function play(id: number) {
    try { await options.video.value?.play() }
    catch (cause) {
      if (id !== generation || error.value) return
      if (!cause || typeof cause !== 'object' || !('name' in cause) || cause.name !== 'NotAllowedError') return
      if (loading.value) {
        playbackBlocked.value = true
        clearConnectionTimer()
      } else playbackHint.value = '画面已就绪，请点击播放器中的播放按钮。'
    }
  }

  function resumePlayback() {
    if (!enabled() || !loading.value || !playbackBlocked.value || error.value || !options.video.value) return
    playbackBlocked.value = false
    playbackHint.value = ''
    startConnectionTimer(generation)
    void play(generation)
  }

  function installHls(address: PlayAddress, id: number, element: HTMLVideoElement) {
    const nextPlayer = hlsAdapter.create()
    player = nextPlayer
    nextPlayer.on(hlsAdapter.events.mediaAttached, () => { if (id === generation) nextPlayer.loadSource(address.url) })
    nextPlayer.on(hlsAdapter.events.manifestParsed, () => {
      if (id !== generation) return
      void play(id)
    })
    nextPlayer.on(hlsAdapter.events.error, (_event, data) => {
      if (!data?.fatal || id !== generation) return
      if (data.type === hlsAdapter.errorTypes.network) {
        fail(new Error(data.response?.code === 401 || data.response?.code === 403 ? '视频地址已失效或无权访问，请重新连接获取地址。' : '无法读取摄像头视频流，请检查设备和网络。'), id, '视频播放失败，请重新连接摄像头。')
      } else if (data.type === hlsAdapter.errorTypes.media) fail(new Error('浏览器无法解码当前码流，请使用标准 H.264 码流。'), id, '视频播放失败，请重新连接摄像头。')
      else fail(new Error('视频播放失败，请重新连接摄像头。'), id, '视频播放失败，请重新连接摄像头。')
    })
    nextPlayer.attachMedia(element)
  }

  async function loadStream(): Promise<void> {
    const id = ++generation
    requestController?.abort()
    requestController = undefined
    clearPlayer()
    error.value = ''
    playbackBlocked.value = false
    playbackHint.value = ''
    captureNotice.value = ''
    quality.value = ''
    loading.value = false
    const device = options.device.value
    const element = options.video.value
    if (!device || device.status !== 1 || !element) return
    const controller = new AbortController()
    requestController = controller
    loading.value = true
    startConnectionTimer(id)
    try {
      const address = await getPlayAddress(device, controller.signal)
      if (id !== generation || controller.signal.aborted || !options.video.value) return
      quality.value = address.quality === 'hd' ? '高清' : '标清'
      if (address.expireSeconds > 0) {
        const delay = Math.max(1_000, Math.min(2_147_483_647, address.expireSeconds * 900))
        renewalAt = Date.now() + delay
      }
      if (options.video.value.canPlayType('application/vnd.apple.mpegurl')) {
        options.video.value.src = address.url
        options.video.value.load()
        void play(id)
      } else if (hlsAdapter.isSupported()) installHls(address, id, options.video.value)
      else throw new Error('当前浏览器不支持 HLS 直播。')
    } catch (cause) {
      if (id === generation && !controller.signal.aborted) fail(cause, id, '获取视频地址失败，请重试。')
    } finally {
      if (requestController === controller) requestController = undefined
    }
  }

  function nativeReady() {
    if (!enabled() || error.value || !loading.value) return
    clearConnectionTimer()
    if (playbackBlocked.value) {
      playbackBlocked.value = false
      playbackHint.value = '画面已就绪，请点击播放器中的播放按钮。'
    }
    ready.value = true
    loading.value = false
    if (renewalAt !== undefined) {
      const id = generation
      expiryTimer = setTimeout(() => { if (id === generation) void loadStream() }, Math.max(0, renewalAt - Date.now()))
    }
  }

  function nativeError() {
    if (!enabled() || !options.video.value?.getAttribute('src') || player) return
    fail(new Error(options.video.value.error?.code === 4 ? '浏览器无法解码当前码流，请使用标准 H.264 码流。' : '视频连接已中断，请重新连接摄像头。'), generation, '视频播放失败，请重新连接摄像头。')
  }

  async function capture(): Promise<void> {
    const device = options.device.value
    if (!device || device.status !== 1 || capturing.value || captureCooldown.value) return
    const controller = new AbortController()
    captureController?.abort()
    captureController = controller
    capturing.value = true
    captureCooldown.value = true
    captureNotice.value = ''
    if (cooldownTimer !== undefined) clearTimeout(cooldownTimer)
    cooldownTimer = setTimeout(() => { captureCooldown.value = false }, 4_000)
    try {
      const blob = await captureFrame(device, controller.signal)
      if (controller.signal.aborted) return
      if (!blob.size || !blob.type.toLowerCase().startsWith('image/')) throw new Error('抓拍图片无效，请重试。')
      const serial = device.serial.replace(/[^a-zA-Z0-9_-]/g, '_') || 'capture'
      let cleanup!: () => void
      let releasedBeforeRegistration = false
      const released = () => {
        releasedBeforeRegistration = true
        if (cleanup) downloadCleanups.delete(cleanup)
      }
      cleanup = download(blob, `${serial}_${Date.now()}.jpg`, released)
      if (!releasedBeforeRegistration) downloadCleanups.add(cleanup)
    } catch (cause) {
      if (!controller.signal.aborted) captureNotice.value = safeVideoError(cause, '抓拍失败，请稍后重试。')
    } finally {
      if (captureController === controller) {
        captureController = undefined
        capturing.value = false
      }
    }
  }

  async function fullscreen() {
    try { await options.video.value?.requestFullscreen() }
    catch { playbackHint.value = '当前浏览器暂不支持全屏播放。' }
  }

  function dispose() {
    generation++
    requestController?.abort()
    requestController = undefined
    captureController?.abort()
    captureController = undefined
    if (cooldownTimer !== undefined) clearTimeout(cooldownTimer)
    cooldownTimer = undefined
    captureCooldown.value = false
    capturing.value = false
    captureNotice.value = ''
    clearPlayer()
    downloadCleanups.forEach(cleanup => cleanup())
    downloadCleanups.clear()
    stopWatching()
  }

  const stopWatching = watch(playbackKey, () => {
    captureController?.abort()
    capturing.value = false
    void loadStream()
  })

  return { loading, ready, playing, error, playbackBlocked, playbackHint, captureNotice, quality, capturing, captureCooldown, loadStream, resumePlayback, capture, nativeReady, nativeError, fullscreen, dispose, onPlaying: () => { if (error.value || !ready.value) return; playing.value = true; playbackBlocked.value = false; playbackHint.value = '' }, onPaused: () => { playing.value = false } }
}
