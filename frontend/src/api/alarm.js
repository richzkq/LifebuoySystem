/**
 * 报警记录 API
 */
import http from './http'
import { API } from '@/config'

export function getAlarmList() {
  return http.get(API.ALARM_LIST)
}
