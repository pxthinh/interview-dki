import type { ApiError } from './types'

const BASE = '/api/v1'
const TOKEN_KEY = 'roombooking.token'

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* private mode */
  }
}

export class RequestError extends Error {
  readonly problem: ApiError

  constructor(problem: ApiError) {
    super(problem.detail ?? `HTTP ${problem.status}`)
    this.problem = problem
  }
}

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (res.status === 401 && !path.startsWith('/auth/login')) {
    setToken(null)
    onUnauthorized?.()
  }
  if (res.status === 204) return undefined as T

  const text = await res.text()
  const json = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    throw new RequestError({ status: res.status, ...(json ?? {}) })
  }
  return json as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
}

/** Human-readable message for a failed call, including conflict details on 409. */
export function describeError(e: unknown): string {
  if (e instanceof RequestError) {
    const p = e.problem
    if (p.errorCode === 'BOOKING_CONFLICT' && p.conflict) {
      const c = p.conflict
      return `Trùng lịch với "${c.title}" (${fmtTime(c.startTime)} - ${fmtTime(c.endTime)})`
    }
    if (p.errors?.length) {
      return p.errors.map((x) => `${x.field}: ${x.message}`).join('; ')
    }
    return p.detail ?? `Lỗi ${p.status}`
  }
  return String(e)
}

export function fmtTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

export function fmtHour(iso: string): string {
  return new Date(iso).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
}

/** Date + "HH:mm" in local (VN) time -> ISO string with the local offset, for the API. */
export function toApiDateTime(date: string, time: string): string {
  const d = new Date(`${date}T${time}:00`)
  const off = -d.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const hh = String(Math.floor(Math.abs(off) / 60)).padStart(2, '0')
  const mm = String(Math.abs(off) % 60).padStart(2, '0')
  return `${date}T${time}:00${sign}${hh}:${mm}`
}

export function todayISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
