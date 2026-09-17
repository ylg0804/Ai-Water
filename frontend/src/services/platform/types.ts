import type { Alarm, Device, Measurement } from '../../domain/models'

export interface PlatformState {
  devices: Device[]
  measurements: Measurement[]
  alarms: Alarm[]
  alarmError: string
  storageWarning: string
  connection: 'idle' | 'loading' | 'connected' | 'error'
  realtime: 'stopped' | 'connecting' | 'connected' | 'polling'
  loading: boolean
  ready: boolean
  lastUpdated: string | null
}
