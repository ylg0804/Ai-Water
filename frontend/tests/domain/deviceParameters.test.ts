import { describe, expect, it } from 'vitest'
import { ALARM_FIELDS, PARAM_FIELDS, emptyParameterValues, parseParams, serializeParams, validateParams } from '../../src/domain/deviceParameters'

const cameraParams = 'gauge_roi_x1=90.0,gauge_roi_y1=340.0,gauge_roi_x2=220.0,gauge_roi_y2=490.0,flow_roi_x1=260.0,flow_roi_y1=300.0,flow_roi_x2=420.0,flow_roi_y2=380.0,scale_top_y=0.0,scale_top_value=0.5,scale_bottom_y=260.0,scale_bottom_value=1.5,gauge_base_height=0.0,frame_width=640.0,frame_height=360.0,fps=12.5,frame_step=6.25,channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85'

describe('device parameters', () => {
  it('preserves coordinate precision and unknown extension values when another field changes', () => {
    const values = parseParams('gauge_roi_x1=206.666672,fps=12.5000,frame_step=6.25,future_gain=0.12345678901234567890,future_mode=precise')
    values.fps = '25'
    expect(serializeParams(values)).toBe('gauge_roi_x1=206.666672,fps=25,frame_step=6.25,future_gain=0.12345678901234567890,future_mode=precise')
  })

  it('accepts fractional frame intervals and coordinates on the 640px canvas beyond source height', () => {
    expect(validateParams(cameraParams)).toBeNull()
  })

  it('starts every known parameter blank and rejects its serialized incomplete value', () => {
    const values = emptyParameterValues()
    expect(values).toEqual(Object.fromEntries([...PARAM_FIELDS, ...ALARM_FIELDS].map((field) => [field.key, ''])))
    expect(validateParams(serializeParams(values))).not.toBeNull()
  })

  it.each([
    ['frame_step=6.25', 'frame_step=0'],
    ['fps=12.5', 'fps=Infinity'],
    ['fps=12.5', 'fps=0x10'],
    ['frame_width=640.0', 'frame_width=-1'],
    ['alpha=0.85', 'alpha=1.01'],
    ['gauge_roi_x2=220.0', 'gauge_roi_x2=80'],
    ['flow_roi_y2=380.0', 'flow_roi_y2=641'],
    ['scale_bottom_y=260.0', 'scale_bottom_y=0'],
  ])('rejects invalid physical parameters: %s → %s', (before, after) => {
    expect(validateParams(cameraParams.replace(before, after))).not.toBeNull()
  })

  it('rejects missing required parameters and ambiguous duplicate keys', () => {
    expect(validateParams(cameraParams.replace('fps=12.5,', ''))).not.toBeNull()
    expect(validateParams(`${cameraParams},fps=25`)).not.toBeNull()
    expect(validateParams(`${cameraParams},broken`)).not.toBeNull()
  })

  it('validates optional alarm thresholds without rejecting numeric extension keys', () => {
    expect(validateParams(`${cameraParams},channel_full_level=1.4,full_consec_count=3,future_gain=0.125`)).toBeNull()
    expect(validateParams(`${cameraParams},full_consec_count=1.5`)).not.toBeNull()
    expect(validateParams(`${cameraParams},occ_min_mean=250,occ_max_mean=240`)).not.toBeNull()
  })

  it('rejects extension values the backend float parser cannot consume', () => {
    expect(validateParams(`${cameraParams},future_mode=precise`)).not.toBeNull()
    expect(validateParams(`${cameraParams},future_gain=1e100`)).not.toBeNull()
  })

  it('rejects parameter strings larger than the backend TEXT column', () => {
    expect(validateParams(`${cameraParams},future_gain=${'1'.repeat(65_536)}`)).toContain('65,535')
  })

  it('rejects alarm counts larger than the backend integer range', () => {
    expect(validateParams(`${cameraParams},full_consec_count=2147483648`)).not.toBeNull()
    expect(validateParams(`${cameraParams},occ_consec_frames=2147483648`)).not.toBeNull()
    expect(validateParams(`${cameraParams},debris_min_count=2147483648`)).not.toBeNull()
    expect(validateParams(`${cameraParams},debris_consec_frames=2147483648`)).not.toBeNull()
  })

  it('validates physical constraints after backend Float32 conversion', () => {
    expect(validateParams(cameraParams.replace('fps=12.5', 'fps=1e-50'))).not.toBeNull()
    expect(validateParams(cameraParams
      .replace('scale_top_y=0.0', 'scale_top_y=1.00000001')
      .replace('scale_bottom_y=260.0', 'scale_bottom_y=1.00000002'))).not.toBeNull()
  })

  it('validates alarm relationships and counts after backend Float32 conversion', () => {
    expect(validateParams(`${cameraParams},occ_min_mean=1.00000001,occ_max_mean=1.00000002`)).not.toBeNull()
    expect(validateParams(`${cameraParams},full_consec_count=16777217`)).not.toBeNull()
  })
})
