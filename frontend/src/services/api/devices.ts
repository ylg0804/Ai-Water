import type { Device, DeviceDraft } from '../../domain/models'
import { DEVICE_INTEGER_MAX, effectiveDeviceHd } from '../../domain/device'
import { apiRequest, type ApiRequest } from './client'
import { asIdentifier, asList, asNullableNumber, asRecord, parseServerDate } from './contract'

function optionalText(value: unknown): string { return value == null ? '' : String(value).trim() }
function deviceLocation(value: unknown): string {
  const location = optionalText(value)
  return /^请填写\s+.+\s+的现场位置$/.test(location) ? '' : location
}
function requiredText(value: unknown, message: string): string {
  const text = optionalText(value)
  if (!text) throw new Error(message)
  return text
}
function requiredBinary(value: unknown, message: string): 0 | 1 {
  if (value === true || value === 1 || value === '1') return 1
  if (value === false || value === 0 || value === '0') return 0
  throw new Error(message)
}
function binaryOrDefault(value: unknown, fallback: 0 | 1, message: string): 0 | 1 {
  return value == null ? fallback : requiredBinary(value, message)
}
function requiredPositiveInteger(value: unknown, message: string): number {
  const number = asNullableNumber(value)
  if (number === null || !Number.isInteger(number) || number < 1) throw new Error(message)
  return number
}
function positiveIntegerOrDefault(value: unknown, fallback: number, message: string): number {
  return value == null ? fallback : requiredPositiveInteger(value, message)
}

export function mapDevice(value: unknown): Device {
  const row = asRecord(value)
  const serial = requiredText(row.serial, '设备数据缺少序列号。')
  const hd = binaryOrDefault(row.hd, 0, '设备清晰度设置异常。')
  return {
    id: asIdentifier(row.id),
    serial,
    name: optionalText(row.name),
    location: deviceLocation(row.location),
    channelNo: positiveIntegerOrDefault(row.channel_no, 1, '设备通道号格式异常。'),
    hd: effectiveDeviceHd(serial, hd),
    deviceOtherParams: optionalText(row.device_other_params),
    inferIntervalSec: positiveIntegerOrDefault(row.infer_interval_sec, 60, '设备运行周期格式异常。'),
    status: binaryOrDefault(row.status, 1, '设备状态格式异常。'),
    online: null,
    createdAt: parseServerDate(row.created_at),
    ...(row.deleted_at != null ? { archived: true } : {}),
  }
}

export async function listDevices(request: ApiRequest = apiRequest, includeDeleted = false): Promise<Device[]> {
  return asList(await request(includeDeleted ? '/api/device?includeDeleted=true' : '/api/device')).map(mapDevice)
}

export function normalizeDeviceDraft(draft: DeviceDraft, existing: Device[], id?: string): DeviceDraft {
  const serial = draft.serial.trim().toUpperCase()
  const name = draft.name.trim()
  const location = draft.location.trim()
  if (!serial) throw new Error('请填写设备序列号。')
  if (serial.length > 64) throw new Error('设备序列号最多 64 个字符。')
  if (name.length > 128) throw new Error('设备名称最多 128 个字符。')
  if (location.length > 256) throw new Error('安装位置最多 256 个字符。')
  if (id !== undefined) asIdentifier(id)
  if (existing.some(device => device.id !== id && device.serial.trim().toUpperCase() === serial)) throw new Error('设备序列号已存在。')
  if (!Number.isInteger(draft.channelNo) || draft.channelNo < 1 || draft.channelNo > DEVICE_INTEGER_MAX
    || !Number.isInteger(draft.inferIntervalSec) || draft.inferIntervalSec < 1 || draft.inferIntervalSec > DEVICE_INTEGER_MAX) {
    throw new Error(`通道号和运行周期必须是 1 到 ${DEVICE_INTEGER_MAX.toLocaleString('en-US')} 之间的整数。`)
  }
  if (draft.hd !== 0 && draft.hd !== 1) throw new Error('清晰度设置不正确。')
  if (draft.status !== 0 && draft.status !== 1) throw new Error('设备状态不正确。')
  return { ...draft, serial, name, location, hd: effectiveDeviceHd(serial, draft.hd) }
}

export async function saveDeviceRequest(draft: DeviceDraft, id?: string, request: ApiRequest = apiRequest): Promise<void> {
  const normalized = normalizeDeviceDraft(draft, [], id)
  const body = JSON.stringify({ serial: normalized.serial, name: normalized.name, location: normalized.location, channelNo: normalized.channelNo, hd: normalized.hd, deviceOtherParams: normalized.deviceOtherParams, inferIntervalSec: normalized.inferIntervalSec, status: normalized.status })
  if (id === undefined) await request('/api/device', { method: 'POST', body })
  else await request(`/api/device/${encodeURIComponent(asIdentifier(id))}`, { method: 'PUT', body })
}

export async function deleteDeviceRequest(id: string, request: ApiRequest = apiRequest): Promise<void> {
  await request(`/api/device/${encodeURIComponent(asIdentifier(id))}`, { method: 'DELETE' })
}
