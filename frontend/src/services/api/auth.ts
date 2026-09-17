import { apiRequest } from './client'

export interface LoginCredentials {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  username: string
}

export async function login(credentials: LoginCredentials): Promise<LoginResponse> {
  return apiRequest<LoginResponse>('/api/login', {
    method: 'POST',
    body: JSON.stringify({ username: credentials.username.trim(), password: credentials.password }),
  })
}
