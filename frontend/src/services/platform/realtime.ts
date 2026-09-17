import type { PlatformState } from './types'

export interface RealtimeSocket {
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  close(): void
}
export interface RealtimeOptions {
  pageUrl?: URL
  onState: (state: PlatformState['realtime']) => void
  onMessage: (payload: string) => void
  createSocket?: (url: string) => RealtimeSocket
}
export interface RealtimeChannel { connect(): void; stop(): void }

export function createRealtimeChannel(options: RealtimeOptions): RealtimeChannel {
  let socket: RealtimeSocket | null = null
  let stopped = false
  function release(close: boolean) {
    const previous = socket
    socket = null
    if (!previous) return
    previous.onopen = previous.onmessage = previous.onerror = previous.onclose = null
    if (close) previous.close()
  }
  return {
    connect() {
      if (stopped || socket) return
      options.onState('connecting')
      try {
        const page = options.pageUrl ?? new URL(window.location.href)
        const url = new URL('/ws/realtime', page)
        url.protocol = page.protocol === 'https:' ? 'wss:' : 'ws:'
        const current = (options.createSocket ?? (address => new WebSocket(address)))(url.href)
        socket = current
        const valid = () => !stopped && socket === current
        current.onopen = () => { if (valid()) options.onState('connected') }
        current.onmessage = event => { if (valid()) options.onMessage(String(event.data)) }
        current.onerror = () => { if (valid()) { release(true); options.onState('polling') } }
        current.onclose = () => { if (valid()) { release(false); options.onState('polling') } }
      } catch { release(true); options.onState('polling') }
    },
    stop() {
      if (stopped) return
      stopped = true
      release(true)
      options.onState('stopped')
    },
  }
}
