import type { Alarm, AlarmStatus, Device } from '../../domain/models'
import { deviceDisplayName } from '../../domain/device'
import { apiBlob, apiRequest, type ApiRequest, type BlobRequest } from './client'
import { asIdentifier, asList, asRecord, parseServerDate } from './contract'

const alarmTypes = ['DEBRIS', 'OCCLUSION', 'CHANNEL_FULL', 'COLLAPSE'] as const
const alarmStatuses = ['OPEN', 'CONFIRMED', 'IGNORED'] as const
function text(value: unknown, fallback = ''): string { return value == null ? fallback : String(value) }

export function mapAlarm(value: unknown, devices: Device[]): Alarm {
  const row = asRecord(value)
  const id = asIdentifier(row.id), deviceId = asIdentifier(row.device_id), type = text(row.type), status = text(row.status)
  const device = devices.find(candidate => candidate.id === deviceId)
  if (!alarmTypes.includes(type as Alarm['type'])) throw new Error('告警类型无法识别。')
  if (!alarmStatuses.includes(status as AlarmStatus)) throw new Error('告警状态无法识别。')
  return {
    id, deviceId, deviceName: device ? deviceDisplayName(device) : `设备 ID ${deviceId}`, type: type as Alarm['type'], status: status as AlarmStatus,
    detail: text(row.detail), snapshotUrl: row.snapshot_path ? `/api/alarm/${encodeURIComponent(id)}/snapshot` : null,
    createdAt: parseServerDate(row.created_at), handledAt: row.handled_at == null ? null : parseServerDate(row.handled_at),
  }
}

export async function listAlarms(devices: Device[], request: ApiRequest = apiRequest): Promise<Alarm[]> { return asList(await request('/api/alarm')).map(row => mapAlarm(row, devices)) }
export async function handleAlarmRequest(id: string, action: Exclude<AlarmStatus, 'OPEN'>, request: ApiRequest = apiRequest): Promise<void> {
  if (action !== 'CONFIRMED' && action !== 'IGNORED') throw new Error('请选择确认或忽略。')
  await request(`/api/alarm/${encodeURIComponent(asIdentifier(id))}/handle`, { method: 'POST', body: JSON.stringify({ action }) })
}
export async function getAlarmSnapshot(id: string, blob: BlobRequest = apiBlob, signal?: AbortSignal): Promise<Blob> {
  const path = `/api/alarm/${encodeURIComponent(asIdentifier(id))}/snapshot`
  return signal ? blob(path, { signal }) : blob(path)
}
