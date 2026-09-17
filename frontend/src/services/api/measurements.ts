import type { Device, HistoryFilter, Measurement } from '../../domain/models'
import { deviceDisplayName } from '../../domain/device'
import { apiBlob, apiRequest, type ApiRequest, type BlobRequest } from './client'
import { asIdentifier, asList, asNullableNumber, asRecord, parseServerDate, toBackendLocalDateTime } from './contract'

export interface RuntimeStatus { lastSuccess: string | null; measurement: Measurement | null; error: string }
const runtimeFailureMessage = '设备采样异常，请联系系统管理员。'

export function mapMeasurement(value: unknown, device: Device, streamTime?: unknown): Measurement {
  const row = asRecord(value)
  if (row.serial != null && String(row.serial) !== device.serial) throw new Error('测量记录与所选设备不一致。')
  const measuredAt = parseServerDate(streamTime ?? row.measured_at)
  return {
    id: streamTime == null ? asIdentifier(row.id) : `live:${device.id}:${measuredAt}`,
    deviceId: device.id,
    deviceName: deviceDisplayName(device),
    measuredAt,
    waterLevel: asNullableNumber(streamTime == null ? row.water_level : (row.waterLevel ?? row.water_level)),
    velocity: asNullableNumber(row.velocity),
    discharge: asNullableNumber(row.discharge),
  }
}

export function historyParams(filter: HistoryFilter): URLSearchParams {
  const deviceId = filter.deviceId === '' ? '' : asIdentifier(filter.deviceId)
  const start = new Date(filter.start)
  const end = new Date(filter.end)
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || end <= start) throw new Error('结束时间必须晚于开始时间。')
  return new URLSearchParams({ deviceId, start: toBackendLocalDateTime(start), end: toBackendLocalDateTime(end) })
}

export async function getLatestMeasurement(device: Device, request: ApiRequest = apiRequest): Promise<Measurement | null> {
  const result = await request(`/api/measurement/latest?deviceId=${encodeURIComponent(asIdentifier(device.id))}`)
  return result == null ? null : mapMeasurement(result, device)
}

export async function getRuntimeStatus(device: Device, request: ApiRequest = apiRequest): Promise<RuntimeStatus> {
  const runtime = asRecord(await request(`/api/ezviz/measurements/latest?serial=${encodeURIComponent(device.serial)}`))
  const lastSuccess = runtime.lastSuccess == null || runtime.lastSuccess === '' ? null : parseServerDate(runtime.lastSuccess)
  const hasRuntimeError = runtime.error != null && String(runtime.error).trim() !== ''
  const measurement = lastSuccess && runtime.measurement != null ? mapMeasurement(runtime.measurement, device, lastSuccess) : null
  return { lastSuccess, measurement, error: hasRuntimeError ? runtimeFailureMessage : '' }
}

export async function listDeviceHistory(device: Device, filter: HistoryFilter, request: ApiRequest = apiRequest, signal?: AbortSignal): Promise<Measurement[]> {
  const query = historyParams({ ...filter, deviceId: device.id })
  const path = `/api/measurement/history?${query}`
  return asList(await (signal ? request(path, { signal }) : request(path))).map(row => mapMeasurement(row, device))
}

export async function exportDeviceHistory(device: Device, filter: HistoryFilter, blob: BlobRequest = apiBlob, signal?: AbortSignal): Promise<Blob> {
  const query = historyParams({ ...filter, deviceId: device.id })
  return blob(`/api/measurement/export?${query}`, { signal })
}
