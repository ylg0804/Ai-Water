import { describe, expect, it, vi } from 'vitest'
import { createRealtimeChannel, type RealtimeSocket } from '../../../src/services/platform/realtime'

function fixture(url = 'https://water.example/#/dashboard') {
  const sockets: RealtimeSocket[] = []
  const createSocket = vi.fn(() => {
    const socket: RealtimeSocket = { onopen: null, onmessage: null, onerror: null, onclose: null, close: vi.fn() }
    sockets.push(socket)
    return socket
  })
  const onState = vi.fn(), onMessage = vi.fn()
  const channel = createRealtimeChannel({ pageUrl: new URL(url), createSocket, onState, onMessage })
  return { channel, sockets, createSocket, onState, onMessage }
}

describe('realtime lifecycle', () => {
  it.each([
    ['https://water.example/#/dashboard', 'wss://water.example/ws/realtime'],
    ['http://localhost:8080/nested', 'ws://localhost:8080/ws/realtime'],
  ])('uses the page origin for %s and forwards raw messages', (page, expected) => {
    const f = fixture(page)
    f.channel.connect()
    expect(f.createSocket).toHaveBeenCalledWith(expected)
    expect(f.onState).toHaveBeenLastCalledWith('connecting')
    f.sockets[0].onopen?.({} as Event)
    expect(f.onState).toHaveBeenLastCalledWith('connected')
    f.sockets[0].onmessage?.({ data: '{"type":"alarm"}' } as MessageEvent)
    expect(f.onMessage).toHaveBeenCalledWith('{"type":"alarm"}')
  })
  it('keeps only one socket while connecting and connected', () => {
    const f = fixture()
    f.channel.connect(); f.channel.connect()
    f.sockets[0].onopen?.({} as Event)
    f.channel.connect()
    expect(f.createSocket).toHaveBeenCalledTimes(1)
  })
  it.each(['onerror', 'onclose'] as const)('falls back to polling on %s and releases handlers before retry', handler => {
    const f = fixture()
    f.channel.connect()
    const old = f.sockets[0], lateMessage = old.onmessage
    old[handler]?.({} as CloseEvent)
    expect(f.onState).toHaveBeenLastCalledWith('polling')
    expect([old.onopen, old.onmessage, old.onerror, old.onclose]).toEqual([null, null, null, null])
    f.channel.connect()
    expect(f.createSocket).toHaveBeenCalledTimes(2)
    lateMessage?.({ data: 'stale' } as MessageEvent)
    expect(f.onMessage).not.toHaveBeenCalled()
  })
  it('stop closes the socket, detaches every handler and prevents reconnect or stale callbacks', () => {
    const f = fixture()
    f.channel.connect()
    const old = f.sockets[0], lateOpen = old.onopen, lateError = old.onerror
    f.channel.stop(); f.channel.stop(); f.channel.connect()
    lateOpen?.({} as Event); lateError?.({} as Event)
    expect(old.close).toHaveBeenCalledTimes(1)
    expect([old.onopen, old.onmessage, old.onerror, old.onclose]).toEqual([null, null, null, null])
    expect(f.onState).toHaveBeenLastCalledWith('stopped')
    expect(f.createSocket).toHaveBeenCalledTimes(1)
  })
  it('falls back to polling if creating the socket fails', () => {
    const onState = vi.fn()
    const channel = createRealtimeChannel({ pageUrl: new URL('https://water.example'), onState, onMessage: vi.fn(), createSocket: () => { throw new Error('unavailable') } })
    expect(() => channel.connect()).not.toThrow()
    expect(onState).toHaveBeenLastCalledWith('polling')
  })
})
