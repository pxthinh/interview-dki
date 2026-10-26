export type Role = 'USER' | 'ADMIN'
export type BookingStatus = 'ACTIVE' | 'CANCELLED'
export type OutboxStatus = 'PENDING' | 'DONE' | 'FAILED'

export interface UserResponse {
  id: string
  email: string
  fullName: string
  role: Role
  active: boolean
  createdAt: string
}

export interface LoginResponse {
  accessToken: string
  expiresInSeconds: number
  user: UserResponse
}

export interface RoomResponse {
  id: string
  name: string
  location: string | null
  capacity: number
  amenities: string[]
  active: boolean
  googleCalendarId: string | null
  adminUserId: string | null
  bufferEnabled: boolean
  createdAt: string
}

export interface BookingResponse {
  id: string
  roomId: string
  roomName: string
  userId: string
  userEmail: string
  title: string
  description: string | null
  attendeeCount: number | null
  startTime: string
  endTime: string
  status: BookingStatus
  cancelledAt: string | null
  googleEventId: string | null
  bufferMinutes: number
  createdAt: string
}

export interface RoomScheduleResponse {
  room: RoomResponse
  bookings: BookingResponse[]
}

export interface OutboxResponse {
  id: number
  bookingId: string
  action: 'CREATE' | 'UPDATE' | 'CANCEL'
  status: OutboxStatus
  attempts: number
  nextAttemptAt: string | null
  lastError: string | null
  createdAt: string
  processedAt: string | null
}

export interface Page<T> {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

export interface ConflictInfo {
  bookingId: string
  title: string
  startTime: string
  endTime: string
}

/** RFC 7807 ProblemDetail body produced by the backend. */
export interface ApiError {
  status: number
  detail?: string
  errorCode?: string
  conflict?: ConflictInfo
  errors?: { field: string; message: string }[]
}
