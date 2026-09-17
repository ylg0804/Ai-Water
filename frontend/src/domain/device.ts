import type { Device } from './models'

export const DEVICE_INTEGER_MAX = 2_147_483_647
export const DEVICE_PARAMS_MAX_BYTES = 65_535
export const FLOAT32_EXACT_INTEGER_MAX = 16_777_216

export function isStandardDefinitionOnly(serial: string): boolean {
  return serial.trim().toUpperCase() === 'G04690603'
}

export function effectiveDeviceHd(serial: string, hd: 0 | 1): 0 | 1 {
  return isStandardDefinitionOnly(serial) ? 0 : hd
}

export function deviceDisplayName(device: Pick<Device, 'name' | 'serial'>): string {
  return device.name.trim() || device.serial
}

export function deviceSecondaryLabel(device: Pick<Device, 'name' | 'serial'>): string {
  const name = device.name.trim()
  return name && name !== device.serial.trim() ? device.serial : ''
}
