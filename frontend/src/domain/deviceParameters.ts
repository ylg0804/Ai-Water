import { DEVICE_PARAMS_MAX_BYTES, FLOAT32_EXACT_INTEGER_MAX } from './device'

export interface ParameterField {
  key: string
  label: string
  unit: string
  description: string
  group: string
}

export const PARAM_FIELDS: ParameterField[] = [
  { key: 'gauge_roi_x1', label: '左上角 X', unit: 'px', description: '水尺识别区域左上角横坐标，640×640 画布像素。', group: '水尺识别区域' },
  { key: 'gauge_roi_y1', label: '左上角 Y', unit: 'px', description: '水尺识别区域左上角纵坐标，640×640 画布像素。', group: '水尺识别区域' },
  { key: 'gauge_roi_x2', label: '右下角 X', unit: 'px', description: '水尺识别区域右下角横坐标，640×640 画布像素。', group: '水尺识别区域' },
  { key: 'gauge_roi_y2', label: '右下角 Y', unit: 'px', description: '水尺识别区域右下角纵坐标，640×640 画布像素。', group: '水尺识别区域' },
  { key: 'flow_roi_x1', label: '左上角 X', unit: 'px', description: '光流测速区域左上角横坐标，640×640 画布像素。', group: '流速识别区域' },
  { key: 'flow_roi_y1', label: '左上角 Y', unit: 'px', description: '光流测速区域左上角纵坐标，640×640 画布像素。', group: '流速识别区域' },
  { key: 'flow_roi_x2', label: '右下角 X', unit: 'px', description: '光流测速区域右下角横坐标，640×640 画布像素。', group: '流速识别区域' },
  { key: 'flow_roi_y2', label: '右下角 Y', unit: 'px', description: '光流测速区域右下角纵坐标，640×640 画布像素。', group: '流速识别区域' },
  { key: 'scale_top_y', label: '上定标点纵坐标', unit: 'px', description: '自水尺 ROI 顶部起算的原图像素纵坐标。', group: '水尺标定' },
  { key: 'scale_top_value', label: '上定标点读数', unit: 'm', description: '上定标点对应的实际水尺读数。', group: '水尺标定' },
  { key: 'scale_bottom_y', label: '下定标点纵坐标', unit: 'px', description: '自水尺 ROI 顶部起算的原图像素纵坐标。', group: '水尺标定' },
  { key: 'scale_bottom_value', label: '下定标点读数', unit: 'm', description: '下定标点对应的实际水尺读数。', group: '水尺标定' },
  { key: 'gauge_base_height', label: '水尺零点高程', unit: 'm', description: '水尺基座海拔，加到换算水位上。', group: '水尺标定' },
  { key: 'frame_width', label: '原始画面宽度', unit: 'px', description: '原始视频分辨率的宽度，保留原始数值。', group: '视频与采样' },
  { key: 'frame_height', label: '原始画面高度', unit: 'px', description: '原始视频分辨率的高度，非模型画布尺寸。', group: '视频与采样' },
  { key: 'fps', label: '视频帧率', unit: '帧/s', description: '原始视频每秒帧数，例如 12.5。', group: '视频与采样' },
  { key: 'frame_step', label: '帧对采样间隔', unit: '帧', description: '支持小数；6.25 帧 ÷ 12.5 fps = 0.5 秒，与运行周期分别设置。', group: '视频与采样' },
  { key: 'channel_bottom_width', label: '渠道底宽', unit: 'm', description: '梯形渠道断面的底部宽度。', group: '渠道断面' },
  { key: 'channel_side_slope_1', label: '左岸边坡系数', unit: '—', description: '水平距离 : 垂直距离 = 当前值 : 1。', group: '渠道断面' },
  { key: 'channel_side_slope_2', label: '右岸边坡系数', unit: '—', description: '水平距离 : 垂直距离 = 当前值 : 1。', group: '渠道断面' },
  { key: 'alpha', label: '流量折算系数', unit: '0–1', description: '表面流速折算为断面平均流速的系数。', group: '渠道断面' },
]

export const ALARM_FIELDS: ParameterField[] = [
  { key: 'channel_full_level', label: '渠道水满阈值', unit: 'm', description: '留空时不启用水满告警。', group: '水满' },
  { key: 'full_consec_count', label: '水满连续命中次数', unit: '次', description: '连续达到阈值才告警，默认 3 次。', group: '水满' },
  { key: 'occ_min_mean', label: '灰度均值下限', unit: '0–255', description: '检测黑屏，默认 15。', group: '遮挡' },
  { key: 'occ_max_mean', label: '灰度均值上限', unit: '0–255', description: '检测全白过曝，默认 240。', group: '遮挡' },
  { key: 'occ_min_std', label: '灰度标准差下限', unit: '—', description: '检测无纹理或画面糊住，默认 10。', group: '遮挡' },
  { key: 'occ_min_lapvar', label: '拉普拉斯方差下限', unit: '—', description: '检测严重模糊或污损，默认 20。', group: '遮挡' },
  { key: 'occ_consec_frames', label: '连续异常帧数', unit: '帧', description: '遮挡防抖，默认 5 帧。', group: '遮挡' },
  { key: 'debris_min_conf', label: '漂浮物置信度下限', unit: '0–1', description: '过滤低置信度检出，默认 0.5。', group: '漂浮物' },
  { key: 'debris_min_count', label: '单次最少检出数', unit: '个', description: '单次推理检出数量阈值，默认 1 个。', group: '漂浮物' },
  { key: 'debris_consec_frames', label: '连续检出次数', unit: '次', description: '连续推理命中才告警，默认 3 次。', group: '漂浮物' },
]

export function emptyParameterValues(): Record<string, string> {
  return Object.fromEntries([...PARAM_FIELDS, ...ALARM_FIELDS].map((field) => [field.key, '']))
}

export function parseParams(text: string): Record<string, string> {
  const values: Record<string, string> = Object.create(null)
  if (!text.trim()) return values
  for (const entry of text.split(',')) {
    const parts = entry.trim().split('=')
    const key = parts[0]?.trim() ?? ''
    const value = parts[1]?.trim() ?? ''
    if (parts.length !== 2 || !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(key) || !value) {
      throw new Error(`参数格式有误：${entry.trim() || '空参数'}，请使用 key=value。`)
    }
    if (Object.hasOwn(values, key)) throw new Error(`参数 ${key} 重复，请只保留一个值。`)
    values[key] = value
  }
  return values
}

export function serializeParams(values: Record<string, string>): string {
  return Object.entries(values).map(([key, value]) => `${key}=${value}`).join(',')
}

export function validateParams(text: string): string | null {
  if (new TextEncoder().encode(text).byteLength > DEVICE_PARAMS_MAX_BYTES) {
    return `设备参数总长度不能超过 ${DEVICE_PARAMS_MAX_BYTES.toLocaleString('en-US')} 字节。`
  }
  let values: Record<string, string>
  try {
    values = parseParams(text)
  } catch (error) {
    return error instanceof Error ? error.message : '参数格式不正确。'
  }
  for (const field of PARAM_FIELDS) {
    if (!values[field.key]?.trim()) return `请填写${field.group}的${field.label}（${field.key}）。`
  }
  const knownFields = [...PARAM_FIELDS, ...ALARM_FIELDS]
  for (const [key, value] of Object.entries(values)) {
    const number = Number(value)
    if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
      || !Number.isFinite(number) || !Number.isFinite(Math.fround(number))) {
      const field = knownFields.find((candidate) => candidate.key === key)
      return field ? `${field.label}（${key}）必须是有限数值。` : `扩展参数（${key}）必须是有限数值。`
    }
  }
  const n = (key: string) => Math.fround(Number(values[key]))
  for (const key of ['frame_width', 'frame_height', 'fps', 'frame_step', 'channel_bottom_width']) {
    if (n(key) <= 0) return `${PARAM_FIELDS.find((field) => field.key === key)?.label}（${key}）必须大于 0。`
  }
  if (n('alpha') < 0 || n('alpha') > 1) return '流量折算系数 alpha 必须在 0 到 1 之间。'
  for (const prefix of ['gauge_roi', 'flow_roi']) {
    const label = prefix === 'gauge_roi' ? '水尺识别区域' : '流速识别区域'
    if (['x1', 'y1', 'x2', 'y2'].some((suffix) => n(`${prefix}_${suffix}`) < 0 || n(`${prefix}_${suffix}`) > 640)) {
      return `${label}坐标须在 0–640 的模型画布范围内。`
    }
    if (n(`${prefix}_x1`) >= n(`${prefix}_x2`) || n(`${prefix}_y1`) >= n(`${prefix}_y2`)) {
      return `${label}右下角坐标须大于左上角坐标。`
    }
  }
  if (n('scale_top_y') < 0 || n('scale_bottom_y') <= n('scale_top_y')) {
    return '水尺定标点纵坐标须非负，且下定标点须大于上定标点。'
  }
  if (n('scale_top_value') === n('scale_bottom_value')) return '上下定标点的水尺读数不能相同。'
  for (const key of ['channel_side_slope_1', 'channel_side_slope_2']) {
    if (n(key) < 0) return '渠道边坡系数不能为负数。'
  }
  for (const field of ALARM_FIELDS) {
    if (!Object.hasOwn(values, field.key)) continue
    if (n(field.key) < 0) return `${field.label}不能为负数。`
    if (['full_consec_count', 'occ_consec_frames', 'debris_min_count', 'debris_consec_frames'].includes(field.key)) {
      const count = Number(values[field.key])
      if (!Number.isInteger(count) || count < 1 || count > FLOAT32_EXACT_INTEGER_MAX) {
        return `${field.label}必须是 1 到 ${FLOAT32_EXACT_INTEGER_MAX.toLocaleString('en-US')} 之间的整数。`
      }
    }
    if (['occ_min_mean', 'occ_max_mean'].includes(field.key) && n(field.key) > 255) return `${field.label}须在 0 到 255 之间。`
    if (field.key === 'debris_min_conf' && n(field.key) > 1) return '漂浮物置信度须在 0 到 1 之间。'
  }
  if (Math.fround(Number(values.occ_min_mean ?? 15)) >= Math.fround(Number(values.occ_max_mean ?? 240))) {
    return '灰度均值下限须小于上限（未填写时分别使用 15、240）。'
  }
  return null
}
