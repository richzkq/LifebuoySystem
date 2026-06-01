/**
 * 认证相关 API
 */
import http from './http'
import { API } from '@/config'

export function login(username, password) {
  return http.post(API.USER_LOGIN, { username, password })
}

export function getInfo() {
  return http.get(API.USER_INFO)
}
