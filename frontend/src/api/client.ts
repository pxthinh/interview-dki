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
  let json: any = undefined
  if (text) {
    try {
      json = JSON.parse(text)
    } catch {
      if (!res.ok) {
        throw new RequestError({
          status: res.status,
          detail: `Máy chủ phản hồi HTTP ${res.status} (${res.statusText || 'Error'}). Backend có thể đang khởi động hoặc gặp sự cố.`,
        })
      }
    }
  }
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

/** Business time zone of the backend: every "day" the UI sends is evaluated in this zone. */
export const BUSINESS_TZ = 'Asia/Ho_Chi_Minh'

// en-CA formats as YYYY-MM-DD, which is exactly the <input type="date"> / API date format.
const businessDateFmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TZ, year: 'numeric', month: '2-digit', day: '2-digit',
})

/** Calendar day of an instant in the business time zone (not the browser's), as YYYY-MM-DD. */
export function businessDateISO(d: Date): string {
  return businessDateFmt.format(d)
}

const businessTimeFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: BUSINESS_TZ, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

/** Fractional hour of day (e.g. 13.5 for 13:30) of an instant in the business time zone. */
export function businessHourOfDay(d: Date): number {
  const [h, m] = businessTimeFmt.format(d).split(':').map(Number)
  return h + m / 60
}

export function todayISO(): string {
  return businessDateISO(new Date())
}

/** Business day `days` from now, as YYYY-MM-DD (for date picker bounds). */
export function plusDaysISO(days: number): string {
  return businessDateISO(new Date(Date.now() + days * 86_400_000))
}
