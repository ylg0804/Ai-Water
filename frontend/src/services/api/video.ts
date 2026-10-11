import type { Device } from '../../domain/models'
import { apiBlob, apiRequest, type ApiRequest, type BlobRequest } from './client'
import { asRecord } from './contract'

export interface PlayAddress { url: string; expireSeconds: number; quality: 'hd' | 'sd'; protocol: 'hls' }
export async function reportPlayback(device: Device, viewerId: string, version: number, ready: boolean): Promise<void> {
  await apiRequest('/api/ezviz/playback', { method: 'POST', body: JSON.stringify({ serial: device.serial, viewerId, version, ready }), keepalive: !ready })
}
function videoError(message: string): Error { return new Error(message) }

export async function getPlayAddress(device: Device, signal?: AbortSignal, request: ApiRequest = apiRequest, pageProtocol = typeof location === 'undefined' ? 'http:' : location.protocol): Promise<PlayAddress> {
  const query = new URLSearchParams({ serial: device.serial, channel: String(device.channelNo), protocol: 'hls', hd: String(device.hd === 1) })
  const value = asRecord(await request(`/api/ezviz/play-url?${query}`, { signal }))
  if (typeof value.url !== 'string' || !value.url.trim()) throw videoError('未获取到摄像头播放地址。')
  let url: URL
  try { url = new URL(value.url) } catch { throw videoError('视频地址格式异常。') }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw videoError('视频地址无法播放。')
  if (url.username || url.password) throw videoError('视频地址包含不安全凭据。')
  if (pageProtocol === 'https:' && url.protocol !== 'https:') throw videoError('当前页面无法播放非 HTTPS 视频源。')
  if (typeof value.expireSeconds !== 'number' || !Number.isFinite(value.expireSeconds) || value.expireSeconds < 0) throw videoError('视频地址有效期异常。')
  if (value.quality !== 'hd' && value.quality !== 'sd') throw videoError('视频清晰度配置异常。')
  if (value.protocol !== 'hls') throw videoError('视频协议不受支持。')
  return { url: value.url, expireSeconds: value.expireSeconds, quality: value.quality, protocol: value.protocol }
}

export async function captureFrame(device: Device, signal?: AbortSignal, blob: BlobRequest = apiBlob): Promise<Blob> {
  const query = new URLSearchParams({ serial: device.serial, channel: String(device.channelNo) })
  return blob(`/api/ezviz/capture?${query}`, { signal })
}

export function safeVideoError(value: unknown, fallback: string): string { return value instanceof Error ? value.message.replace(/https?:\/\/[^\s'"<>]+/gi, '[视频地址]') : fallback }
