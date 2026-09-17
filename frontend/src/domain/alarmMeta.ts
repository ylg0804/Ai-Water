import type { AlarmStatus, AlarmType } from './models'

export interface AlarmTypeMeta {
  dashboardLabel: string
  listLabel: string
  className: string
  color: string
}

export interface AlarmStatusMeta {
  label: string
  className: string
}

export const alarmTypeMeta = {
  DEBRIS: { dashboardLabel: '河道漂浮物', listLabel: '漂浮物告警', className: 'debris', color: '#c8923a' },
  OCCLUSION: { dashboardLabel: '摄像头遮挡', listLabel: '画面遮挡', className: 'occlusion', color: '#9375c5' },
  CHANNEL_FULL: { dashboardLabel: '渠道水满', listLabel: '渠道满溢', className: 'channel-full', color: '#d26b63' },
  COLLAPSE: { dashboardLabel: '渠道垮塌', listLabel: '渠道垮塌', className: 'collapse', color: '#899298' },
} satisfies Record<AlarmType, AlarmTypeMeta>

// COLLAPSE is reserved in the database, but this release has no collapse detector.
export const implementedAlarmTypes = ['DEBRIS', 'OCCLUSION', 'CHANNEL_FULL'] as const satisfies readonly AlarmType[]

export const alarmStatusMeta = {
  OPEN: { label: '待处理', className: 'open' },
  CONFIRMED: { label: '已确认', className: 'confirmed' },
  IGNORED: { label: '已忽略', className: 'ignored' },
} satisfies Record<AlarmStatus, AlarmStatusMeta>
