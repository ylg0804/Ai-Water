import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiBlob, apiRequest, createApiClient, setSessionToken } from '../../../src/services/api/client'

afterEach(() => {
  setSessionToken('')
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function abortingFetch() {
  return vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  }))
}

const unauthorizedResponses = [
  {
    name: 'HTTP 401',
    create: () => new Response(JSON.stringify({ msg: 'Session expired' }), {
      status: 401, headers: { 'content-type': 'application/json' },
    }),
  },
  {
    name: '401 backend envelope',
    create: () => new Response(JSON.stringify({ code: '401', msg: 'Session expired' }), {
      status: 200, headers: { 'content-type': 'application/json' },
    }),
  },
] as const

describe('API client', () => {
  it('adds the bearer token and unwraps a successful backend envelope', async () => {
    setSessionToken('token-7')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: '200', msg: 'ok', data: { id: 7 } }),
      { status: 200, headers: { 'content-type': 'application/json' } },
    )))

    await expect(apiRequest('/api/device')).resolves.toEqual({ id: 7 })
    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-7')
  })

  it.each(unauthorizedResponses)('dispatches unauthorized for a current-token $name and preserves the backend message', async ({ create }) => {
    const target = new EventTarget()
    const listener = vi.fn()
    target.addEventListener('water:unauthorized', listener)
    const client = createApiClient({
      fetchImpl: vi.fn().mockResolvedValue(create()),
      getToken: () => 'current-token', unauthorizedTarget: target, timeoutMs: 20_000,
    })

    await expect(client.request('/api/device')).rejects.toMatchObject({ status: 401, message: 'Session expired' })
    expect(listener).toHaveBeenCalledOnce()
  })

  it.each(unauthorizedResponses)('does not dispatch unauthorized when a stale-token request receives a late $name', async ({ create }) => {
    const target = new EventTarget()
    const listener = vi.fn()
    target.addEventListener('water:unauthorized', listener)
    let currentToken = 'old-token'
    let resolveResponse!: (response: Response) => void
    const fetchImpl = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => new Promise<Response>((resolve) => {
      resolveResponse = resolve
    }))
    const client = createApiClient({
      fetchImpl,
      getToken: () => currentToken,
      unauthorizedTarget: target,
      timeoutMs: 20_000,
    })

    const pending = client.request('/api/device')
    const rejection = expect(pending).rejects.toMatchObject({ status: 401, message: 'Session expired' })
    expect(new Headers(fetchImpl.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer old-token')
    currentToken = 'new-token'
    resolveResponse(create())

    await rejection
    expect(listener).not.toHaveBeenCalled()
  })

  it('returns a successful JSON response that is not an envelope', async () => {
    const client = createApiClient({
      fetchImpl: vi.fn().mockResolvedValue(new Response(
        JSON.stringify({ ready: true }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      )),
      getToken: () => '',
    })

    await expect(client.request('/api/status')).resolves.toEqual({ ready: true })
  })

  it('returns a non-JSON file response as a blob', async () => {
    const client = createApiClient({
      fetchImpl: vi.fn().mockResolvedValue(new Response('report', {
        status: 200, headers: { 'content-type': 'text/csv' },
      })),
      getToken: () => '',
    })

    await expect(client.blob('/api/report')).resolves.toEqual(new Blob(['report'], { type: 'text/csv' }))
  })

  it('rejects JSON responses requested as files', async () => {
    const client = createApiClient({
      fetchImpl: vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '200' }), {
        status: 200, headers: { 'content-type': 'application/json' },
      })),
      getToken: () => '',
    })

    await expect(client.blob('/api/report')).rejects.toBeInstanceOf(ApiError)
  })

  it('reports an aborted caller request as an API error', async () => {
    const client = createApiClient({ fetchImpl: abortingFetch(), getToken: () => '', timeoutMs: 20_000 })
    const controller = new AbortController()
    const pending = client.request('/api/device', { signal: controller.signal })
    controller.abort()

    await expect(pending).rejects.toBeInstanceOf(ApiError)
  })

  it('reports a timed out request as an API error', async () => {
    const client = createApiClient({ fetchImpl: abortingFetch(), getToken: () => '', timeoutMs: 1 })

    await expect(client.request('/api/device')).rejects.toBeInstanceOf(ApiError)
  })

  it('keeps caller cancellation connected while a JSON body is being read', async () => {
    let bodySignal!: AbortSignal
    let rejectBody!: (reason: unknown) => void
    const client = createApiClient({
      fetchImpl: vi.fn(async (_path, init) => ({
        ok: true, headers: new Headers({ 'content-type': 'application/json' }),
        json: () => new Promise((_resolve, reject) => { bodySignal = init?.signal as AbortSignal; rejectBody = reject }),
      }) as unknown as Response),
      getToken: () => '', timeoutMs: 20_000,
    })
    const caller = new AbortController()
    const pending = client.request('/api/device', { signal: caller.signal })
    await vi.waitFor(() => expect(bodySignal).toBeDefined())
    caller.abort()
    expect(bodySignal.aborted).toBe(true)
    rejectBody(new DOMException('Aborted', 'AbortError'))
    await expect(pending).rejects.toMatchObject({ message: '请求超时，请稍后重试。' })
  })

  it('keeps the default 20-second timeout connected while a JSON body is being read', async () => {
    vi.useFakeTimers()
    let bodySignal!: AbortSignal
    const client = createApiClient({
      fetchImpl: vi.fn(async (_path, init) => ({
        ok: true, headers: new Headers({ 'content-type': 'application/json' }),
        json: () => new Promise((_resolve, reject) => {
          bodySignal = init?.signal as AbortSignal
          bodySignal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
        }),
      }) as unknown as Response),
      getToken: () => '',
    })
    const pending = client.request('/api/device')
    const rejection = expect(pending).rejects.toMatchObject({ message: '请求超时，请稍后重试。' })
    await Promise.resolve()
    await Promise.resolve()

    await vi.advanceTimersByTimeAsync(19_999)
    expect(bodySignal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)

    await rejection
  })

  it('keeps the timeout connected while a blob body is being read', async () => {
    vi.useFakeTimers()
    let bodySignal!: AbortSignal
    let rejectBody!: (reason: unknown) => void
    const client = createApiClient({
      fetchImpl: vi.fn(async (_path, init) => ({
        ok: true, headers: new Headers({ 'content-type': 'image/jpeg' }),
        blob: () => new Promise((_resolve, reject) => { bodySignal = init?.signal as AbortSignal; rejectBody = reject }),
      }) as unknown as Response),
      getToken: () => '', timeoutMs: 20,
    })
    const pending = client.blob('/api/snapshot')
    await vi.waitFor(() => expect(bodySignal).toBeDefined())
    await vi.advanceTimersByTimeAsync(20)
    expect(bodySignal.aborted).toBe(true)
    rejectBody(new DOMException('Aborted', 'AbortError'))
    await expect(pending).rejects.toBeInstanceOf(ApiError)
  })

  it('rejects a path outside the API boundary before fetching', async () => {
    const fetchImpl = vi.fn()
    const client = createApiClient({ fetchImpl, getToken: () => '' })

    await expect(client.request('/assets/report.csv')).rejects.toBeInstanceOf(ApiError)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('uses the singleton blob client for file responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('snapshot', {
      status: 200, headers: { 'content-type': 'image/jpeg' },
    })))

    await expect(apiBlob('/api/snapshot')).resolves.toEqual(new Blob(['snapshot'], { type: 'image/jpeg' }))
  })
})
