export type Metric = 'waterLevel' | 'velocity' | 'discharge'
export interface Device {
  id: string
  serial: string
  name: string
  location: string
  channelNo: number
  hd: 0 | 1
  deviceOtherParams: string
  inferIntervalSec: number
  status: 0 | 1
  online: boolean | null
  runtimeError?: string
  lastSuccess?: string | null
  createdAt: string
}
export type DeviceDraft = Omit<Device, 'id' | 'createdAt' | 'online' | 'runtimeError' | 'lastSuccess'>
export interface Measurement {
  id: string
  deviceId: string
  deviceName: string
  waterLevel: number | null
  velocity: number | null
  discharge: number | null
  measuredAt: string
}
export type AlarmType = 'DEBRIS' | 'OCCLUSION' | 'CHANNEL_FULL' | 'COLLAPSE'
export type AlarmStatus = 'OPEN' | 'CONFIRMED' | 'IGNORED'
export interface Alarm {
  id: string
  deviceId: string
  deviceName: string
  type: AlarmType
  status: AlarmStatus
  detail: string
  snapshotUrl: string | null
  createdAt: string
  handledAt: string | null
}
export interface HistoryFilter { deviceId: string; start: string | Date; end: string | Date }
