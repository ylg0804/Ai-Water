import type { Metric } from '../domain/models'

export const metricMeta: Record<Metric, { label: string; unit: string; color: string }> = {
  waterLevel: { label: '水位', unit: 'm', color: '#4d83e8' },
  velocity: { label: '流速', unit: 'm/s', color: '#38aeb9' },
  discharge: { label: '流量', unit: 'm³/s', color: '#9c7bdd' },
}
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})
export function formatDate(value: string | Date | null | undefined): string {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '—'
  return dateFormatter.format(new Date(value)).replaceAll('/', '-')
}
export function formatNumber(value: number | null | undefined, digits = 2): string {
  return value == null || !Number.isFinite(value) ? '—' : value.toFixed(digits)
}
