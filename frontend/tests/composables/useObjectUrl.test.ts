import { describe, expect, it, vi } from 'vitest'
import { useObjectUrl } from '../../src/composables/useObjectUrl'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((onResolve, onReject) => { resolve = onResolve; reject = onReject })
  return { promise, resolve, reject }
}

describe('useObjectUrl', () => {
  it('loads an authenticated image blob into an object URL', async () => {
    const loadBlob = vi.fn(async (_id: string, _signal: AbortSignal) => new Blob(['image'], { type: 'image/jpeg' }))
    const createObjectUrl = vi.fn(() => 'blob:alarm-1')
    const objectUrl = useObjectUrl({ loadBlob, createObjectUrl, revokeObjectUrl: vi.fn() })

    await objectUrl.load('alarm-1')

    expect(loadBlob).toHaveBeenCalledWith('alarm-1', expect.any(AbortSignal))
    expect(objectUrl.url.value).toBe('blob:alarm-1')
    expect(objectUrl.loading.value).toBe(false)
  })

  it('suppresses a stale blob response after a newer selection', async () => {
    const first = deferred<Blob>()
    const loadBlob = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(new Blob(['new'], { type: 'image/jpeg' }))
    const createObjectUrl = vi.fn(() => 'blob:new')
    const objectUrl = useObjectUrl({ loadBlob, createObjectUrl, revokeObjectUrl: vi.fn() })

    const pending = objectUrl.load('old')
    await objectUrl.load('new')
    first.resolve(new Blob(['old'], { type: 'image/jpeg' }))
    await pending

    expect(objectUrl.url.value).toBe('blob:new')
    expect(createObjectUrl).toHaveBeenCalledTimes(1)
  })

  it('revokes the replaced object URL', async () => {
    const loadBlob = vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' }))
    const createObjectUrl = vi.fn().mockReturnValueOnce('blob:one').mockReturnValueOnce('blob:two')
    const revokeObjectUrl = vi.fn()
    const objectUrl = useObjectUrl({ loadBlob, createObjectUrl, revokeObjectUrl })

    await objectUrl.load('one')
    await objectUrl.load('two')

    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:one')
    expect(objectUrl.url.value).toBe('blob:two')
  })

  it('aborts the active request and clears the image', async () => {
    const pending = deferred<Blob>()
    let signal!: AbortSignal
    const objectUrl = useObjectUrl({ loadBlob: vi.fn(async (_id: string, nextSignal: AbortSignal) => { signal = nextSignal; return pending.promise }), createObjectUrl: vi.fn(), revokeObjectUrl: vi.fn() })

    const loading = objectUrl.load('alarm-1')
    objectUrl.clear()
    pending.resolve(new Blob(['image'], { type: 'image/jpeg' }))
    await loading

    expect(signal.aborted).toBe(true)
    expect(objectUrl.url.value).toBeNull()
  })

  it('rejects blobs that are not images', async () => {
    const objectUrl = useObjectUrl({ loadBlob: vi.fn(async () => new Blob(['csv'], { type: 'text/csv' })), createObjectUrl: vi.fn(), revokeObjectUrl: vi.fn() })

    await expect(objectUrl.load('alarm-1')).rejects.toThrow()
    expect(objectUrl.url.value).toBeNull()
    expect(objectUrl.error.value).not.toBe('')
  })

  it('revokes the active object URL on dispose', async () => {
    const revokeObjectUrl = vi.fn()
    const objectUrl = useObjectUrl({ loadBlob: vi.fn(async () => new Blob(['image'], { type: 'image/jpeg' })), createObjectUrl: vi.fn(() => 'blob:alarm-1'), revokeObjectUrl })
    await objectUrl.load('alarm-1')

    objectUrl.dispose()

    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:alarm-1')
    expect(objectUrl.url.value).toBeNull()
  })
})
