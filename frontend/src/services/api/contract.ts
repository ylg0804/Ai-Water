export function asRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('服务返回的数据格式异常。')
  return value as Record<string, unknown>
}

export function asList(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('服务返回的数据格式异常。')
  return value
}

export function asIdentifier(value: unknown): string {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 1)) throw new Error('记录编号格式异常。')
  if (typeof value !== 'string' && typeof value !== 'number') throw new Error('数据缺少记录编号。')
  const identifier = String(value).trim()
  if (!/^[1-9]\d*$/.test(identifier) || BigInt(identifier) > 9_223_372_036_854_775_807n) throw new Error('记录编号格式异常。')
  return identifier
}

export function asNullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  if (typeof value !== 'number' && typeof value !== 'string') throw new Error('测量值格式异常。')
  let number: number
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) throw new Error('测量值格式异常。')
    number = Number(text)
  } else number = value
  if (!Number.isFinite(number)) throw new Error('测量值格式异常。')
  return number
}

export function parseServerDate(value: unknown): string {
  let date: Date
  if (typeof value === 'number') date = new Date(value)
  else if (typeof value === 'string') {
    let normalized = value.trim().replace(' ', 'T')
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(normalized)) normalized += '+08:00'
    else if (!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized)) throw new Error('日期时间格式异常。')
    date = new Date(normalized)
  } else throw new Error('数据缺少日期时间。')
  if (!Number.isFinite(date.getTime())) throw new Error('日期时间格式异常。')
  return date.toISOString()
}

export function toBackendLocalDateTime(value: string | Date): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) throw new Error('时间格式不正确。')
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
  return parts.replace(' ', 'T')
}
