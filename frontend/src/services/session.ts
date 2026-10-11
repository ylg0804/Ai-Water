import { reactive } from 'vue'
import { type LoginCredentials, login, logout as logoutRequest, type LoginResponse } from './api/auth'
import { sessionKey, setSessionToken } from './api/client'

interface User {
  name: string
  username: string
}

type LoginRequest = (credentials: LoginCredentials) => Promise<LoginResponse>
type SessionWindow = EventTarget & { location?: { hash: string } }

export interface SessionOptions {
  storage: Storage
  loginRequest: LoginRequest
  windowTarget: SessionWindow
  revokeSession?: () => Promise<void>
}

function validSession(value: unknown): value is LoginResponse {
  if (!value || typeof value !== 'object') return false
  const session = value as { token?: unknown; username?: unknown }
  return typeof session.token === 'string' && session.token.trim().length > 0
    && typeof session.username === 'string' && session.username.trim().length > 0
}

function savedSession(storage: Storage): LoginResponse | null {
  try {
    const saved: unknown = JSON.parse(storage.getItem(sessionKey) ?? 'null')
    return validSession(saved) ? saved : null
  } catch {
    return null
  }
}

export function createSession({ storage, loginRequest, windowTarget, revokeSession }: SessionOptions) {
  const saved = savedSession(storage)
  setSessionToken(saved?.token ?? '')
  const state = reactive<{ user: User | null }>({
    user: saved ? { name: saved.username, username: saved.username } : null,
  })

  const logout = () => {
    if (state.user && revokeSession) void revokeSession().catch(() => {})
    state.user = null
    setSessionToken('')
    try { storage.removeItem(sessionKey) } catch { /* The active session has already been cleared. */ }
  }

  const expire = () => {
    logout()
    if (windowTarget.location) windowTarget.location.hash = '#/login'
  }
  windowTarget.addEventListener('water:unauthorized', expire)

  return {
    state,
    async login(username: string, password: string) {
      const response: unknown = await loginRequest({ username: username.trim(), password })
      if (!validSession(response)) throw new Error('登录响应缺少有效会话信息。')
      setSessionToken(response.token)
      try { storage.setItem(sessionKey, JSON.stringify({ token: response.token, username: response.username })) } catch { /* The in-memory token remains usable. */ }
      state.user = { name: response.username, username: response.username }
    },
    logout,
    dispose() {
      windowTarget.removeEventListener('water:unauthorized', expire)
    },
  }
}

const emptyStorage: Storage = {
  get length() { return 0 },
  clear() {},
  getItem() { return null },
  key() { return null },
  removeItem() {},
  setItem() {},
}

const storage = typeof sessionStorage === 'undefined' ? emptyStorage : sessionStorage
const windowTarget: SessionWindow = typeof window === 'undefined' ? new EventTarget() : window

export const session = createSession({ storage, loginRequest: login, windowTarget, revokeSession: logoutRequest })

import.meta.hot?.dispose(() => session.dispose())
