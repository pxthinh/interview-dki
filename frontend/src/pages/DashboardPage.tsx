import { useEffect, useState } from 'react'
import { api, businessHourOfDay, describeError, fmtHour, todayISO } from '../api/client'
import type { RoomScheduleResponse } from '../api/types'

const DAY_START = 7
const DAY_END = 20
const SPAN = DAY_END - DAY_START

/** Timeline bar 07:00-20:00 for one room's day. */
function SlotBar({ schedule }: { schedule: RoomScheduleResponse }) {
  return (
    <div className="schedule-room">
      <div className="room-head">
        <strong>{schedule.room.name}</strong>
        <span className="muted">
          {schedule.room.location} · {schedule.room.capacity} chỗ
          {schedule.room.amenities.length > 0 && <> · {schedule.room.amenities.join(', ')}</>}
        </span>
      </div>
      <div className="slotbar">
        {Array.from({ length: SPAN - 1 }, (_, i) => (
          <div key={i} className="tick" style={{ left: `${((i + 1) / SPAN) * 100}%` }} />
        ))}
        {schedule.bookings.map((b) => {
          // Positioned on the business-zone clock so the bar matches the hour labels in any browser.
          const from = Math.max(0, businessHourOfDay(new Date(b.startTime)) - DAY_START)
          const to = Math.min(SPAN, businessHourOfDay(new Date(b.endTime)) - DAY_START)
          const bufTo = Math.min(SPAN, to + b.bufferMinutes / 60)
          return (
            <span key={b.id}>
              {b.bufferMinutes > 0 && bufTo > to && (
                <div
                  className="slot buffer"
                  style={{ left: `${(to / SPAN) * 100}%`, width: `${((bufTo - to) / SPAN) * 100}%` }}
                  title={`Giờ nghỉ ${b.bufferMinutes} phút sau "${b.title}"`}
                />
              )}
              <div
                className="slot"
                style={{ left: `${(from / SPAN) * 100}%`, width: `${((to - from) / SPAN) * 100}%` }}
                title={`${b.title} · ${fmtHour(b.startTime)}-${fmtHour(b.endTime)} · ${b.userEmail}`}
              >
                {b.title}
              </div>
            </span>
          )
        })}
      </div>
      <div className="ticklabels">
        {Array.from({ length: SPAN + 1 }, (_, i) => (
          <span key={i} style={{ left: `${(i / SPAN) * 100}%` }}>
            {DAY_START + i}h
          </span>
        ))}
      </div>
    </div>
  )
}

export default function DashboardPage() {
  const [date, setDate] = useState(todayISO())
  const [schedules, setSchedules] = useState<RoomScheduleResponse[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    api
      .get<RoomScheduleResponse[]>(`/schedule?date=${date}`)
      .then((s) => {
        setSchedules(s)
        setError('')
      })
      .catch((e) => setError(describeError(e)))
  }, [date])

  return (
    <div className="page">
      <h1>Lịch phòng họp</h1>
      <p className="sub">
        Mọi phòng đang hoạt động và các booking ACTIVE trong ngày (07:00–20:00, giờ Việt Nam).{' '}
        <span className="legend-chip booked" /> đã đặt · <span className="legend-chip buffer" /> giờ nghỉ (buffer).
      </p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Ngày</label>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        {schedules.map((s) => (
          <SlotBar key={s.room.id} schedule={s} />
        ))}
        {schedules.length === 0 && !error && <p className="muted">Không có phòng nào.</p>}
      </div>
    </div>
  )
}
