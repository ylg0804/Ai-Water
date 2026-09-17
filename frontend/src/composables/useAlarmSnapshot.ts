import { getAlarmSnapshot } from '../services/api/alarms'
import { useObjectUrl } from './useObjectUrl'

export function useAlarmSnapshot() {
  return useObjectUrl<string>({
    loadBlob: (alarmId, signal) => getAlarmSnapshot(alarmId, undefined, signal),
  })
}
