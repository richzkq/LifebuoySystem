/**
 * 报警记录 API
 */
import http from './http'
import { API } from '@/config'

export function getAlarmList() {
  return http.get(API.ALARM_LIST)
}

/** 确认报警完成 */
export function acknowledgeAlarm(id) {
  return http.post(`/api/alarm/${id}/acknowledge`)
}
