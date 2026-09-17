export const sessionKey = 'water-backend-session-v1'

let memoryToken = ''
export function setSessionToken(value: string) { memoryToken = value }

export class ApiError extends Error {
  constructor(message: string, public status = 0) { super(message); this.name = 'ApiError' }
}

export type ApiRequest = <T = unknown>(path: string, options?: RequestInit) => Promise<T>
export type BlobRequest = (path: string, options?: RequestInit) => Promise<Blob>
type BackendEnvelope = { code: unknown; msg?: unknown; message?: unknown; data?: unknown }

function messageFrom(body: unknown): string {
  if (!body || typeof body !== 'object') return ''
  const value = body as { msg?: unknown; message?: unknown }
  return typeof value.msg === 'string' ? value.msg : typeof value.message === 'string' ? value.message : ''
}
function isEnvelope(body: unknown): body is BackendEnvelope { return Boolean(body && typeof body === 'object' && 'code' in body) }
function isFormData(body: BodyInit | null | undefined): boolean { return typeof FormData !== 'undefined' && body instanceof FormData }

export function createApiClient(options: {
  fetchImpl: typeof fetch
  getToken: () => string
  unauthorizedTarget?: EventTarget
  timeoutMs?: number
}): { request: ApiRequest; blob: BlobRequest } {
  const timeoutMs = options.timeoutMs ?? 20_000
  const dispatchUnauthorized = (requestToken: string) => {
    if (options.getToken() === requestToken) options.unauthorizedTarget?.dispatchEvent(new Event('water:unauthorized'))
  }

  async function withResponse<T>(path: string, requestOptions: RequestInit, consume: (response: Response, requestToken: string) => Promise<T>): Promise<T> {
    if (!path.startsWith('/api/')) throw new ApiError('系统请求配置错误。')
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    const abort = () => controller.abort()
    if (requestOptions.signal?.aborted) controller.abort()
    requestOptions.signal?.addEventListener('abort', abort, { once: true })
    try {
      const headers = new Headers(requestOptions.headers)
      if (requestOptions.body && !isFormData(requestOptions.body)) headers.set('Content-Type', 'application/json')
      const token = options.getToken()
      if (token) headers.set('Authorization', `Bearer ${token}`)
      const response = await options.fetchImpl(path, { ...requestOptions, headers, signal: controller.signal, cache: 'no-store' })
      if (!response.ok) {
        let body: unknown
        try { body = await response.json() } catch { /* Proxies may return plain text. */ }
        if (response.status === 401) dispatchUnauthorized(token)
        const responseMessage = response.status >= 500 ? '' : messageFrom(body)
        throw new ApiError(responseMessage || (response.status >= 500 ? '服务暂时不可用，请稍后重试。' : `请求失败，请重试（${response.status}）。`), response.status)
      }
      return await consume(response, token)
    } catch (cause) {
      if (controller.signal.aborted) throw new ApiError('请求超时，请稍后重试。')
      if (cause instanceof ApiError) throw cause
      throw new ApiError('网络连接失败，请检查网络后重试。')
    } finally {
      clearTimeout(timeout)
      requestOptions.signal?.removeEventListener('abort', abort)
    }
  }

  const request: ApiRequest = async <T = unknown>(path: string, requestOptions: RequestInit = {}): Promise<T> => withResponse(path, requestOptions, async (response, requestToken) => {
    let body: unknown
    try { body = await response.json() } catch { throw new ApiError('服务响应异常，请稍后重试。') }
    if (!body || typeof body !== 'object') throw new ApiError('服务响应异常，请稍后重试。')
    if (!isEnvelope(body)) return body as T
    if (String(body.code) === '200') return body.data as T
    const status = Number(body.code)
    if (status === 401) dispatchUnauthorized(requestToken)
    throw new ApiError(messageFrom(body) || '请求未完成，请重试。', Number.isFinite(status) ? status : 0)
  })

  const blob: BlobRequest = async (path: string, requestOptions: RequestInit = {}): Promise<Blob> => withResponse(path, requestOptions, async response => {
    if (response.headers.get('content-type')?.toLowerCase().includes('json')) throw new ApiError('文件下载失败，请重试。')
    return response.blob()
  })

  return { request, blob }
}

const singleton = createApiClient({
  fetchImpl: (...args) => fetch(...args),
  getToken: () => memoryToken,
  unauthorizedTarget: typeof window === 'undefined' ? undefined : window,
})
export async function apiRequest<T = unknown>(path: string, options: RequestInit = {}): Promise<T> { return singleton.request<T>(path, options) }
export async function apiBlob(path: string, options: RequestInit = {}): Promise<Blob> { return singleton.blob(path, options) }
