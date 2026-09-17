import { describe, expect, it, vi } from 'vitest'
import { sessionKey } from '../../src/services/api/client'
import { createSession } from '../../src/services/session'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()

  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}

function windowFixture() {
  return Object.assign(new EventTarget(), { location: { hash: '#/dashboard' } })
}

describe('session boundary', () => {
  it('trims the username but sends the password unchanged', async () => {
    const storage = new MemoryStorage()
    const windowTarget = windowFixture()
    const loginRequest = vi.fn().mockResolvedValue({ token: 't', username: 'admin' })
    const current = createSession({ storage, loginRequest, windowTarget })

    await current.login('  admin  ', ' pass ')

    expect(loginRequest).toHaveBeenCalledWith({ username: 'admin', password: ' pass ' })
  })

  it.each([
    { token: '', username: 'admin' },
    { token: 't', username: '' },
  ])('does not accept a login response with an empty session field', async response => {
    const storage = new MemoryStorage()
    const current = createSession({ storage, loginRequest: vi.fn().mockResolvedValue(response), windowTarget: windowFixture() })

    await expect(current.login('admin', 'pass')).rejects.toThrow()
    expect(current.state.user).toBeNull()
    expect(storage.getItem(sessionKey)).toBeNull()
  })

  it('persists an accepted login session and exposes its user', async () => {
    const storage = new MemoryStorage()
    const current = createSession({
      storage,
      loginRequest: vi.fn().mockResolvedValue({ token: 'token-7', username: 'admin' }),
      windowTarget: windowFixture(),
    })

    await current.login('admin', 'pass')

    expect(current.state.user).toEqual({ name: 'admin', username: 'admin' })
    expect(JSON.parse(storage.getItem(sessionKey)!)).toEqual({ token: 'token-7', username: 'admin' })
  })

  it('clears the active session from memory state and storage on logout', async () => {
    const storage = new MemoryStorage()
    const current = createSession({
      storage,
      loginRequest: vi.fn().mockResolvedValue({ token: 'token-7', username: 'admin' }),
      windowTarget: windowFixture(),
    })
    await current.login('admin', 'pass')

    current.logout()

    expect(current.state.user).toBeNull()
    expect(storage.getItem(sessionKey)).toBeNull()
  })

  it('restores a valid saved session', () => {
    const storage = new MemoryStorage()
    storage.setItem(sessionKey, JSON.stringify({ token: 'saved-token', username: 'saved-user' }))

    const current = createSession({ storage, loginRequest: vi.fn(), windowTarget: windowFixture() })

    expect(current.state.user).toEqual({ name: 'saved-user', username: 'saved-user' })
  })

  it('ignores malformed saved JSON', () => {
    const storage = new MemoryStorage()
    storage.setItem(sessionKey, '{not-json')

    const current = createSession({ storage, loginRequest: vi.fn(), windowTarget: windowFixture() })

    expect(current.state.user).toBeNull()
  })

  it('logs out and navigates to the login hash after an unauthorized event', async () => {
    const storage = new MemoryStorage()
    const windowTarget = windowFixture()
    const current = createSession({
      storage,
      loginRequest: vi.fn().mockResolvedValue({ token: 'token-7', username: 'admin' }),
      windowTarget,
    })
    await current.login('admin', 'pass')

    windowTarget.dispatchEvent(new Event('water:unauthorized'))

    expect(current.state.user).toBeNull()
    expect(storage.getItem(sessionKey)).toBeNull()
    expect(windowTarget.location.hash).toBe('#/login')
  })
})
