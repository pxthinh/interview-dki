import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError, fmtHour, todayISO } from '../api/client'
import type { BookingResponse, RoomResponse } from '../api/types'

interface TimeSlot {
  startTime: string
  endTime: string
  availableRooms: number
}

const DURATIONS = [
  { v: 15, label: '15 phút' },
  { v: 30, label: '30 phút' },
  { v: 45, label: '45 phút' },
  { v: 60, label: '1 giờ' },
  { v: 90, label: '1,5 giờ' },
  { v: 120, label: '2 giờ' },
  { v: 180, label: '3 giờ' },
  { v: 240, label: '4 giờ' },
  { v: 480, label: '8 giờ' },
]

export default function AvailabilityPage() {
  const [date, setDate] = useState(todayISO())
  const [duration, setDuration] = useState(60)
  const [minCapacity, setMinCapacity] = useState('')
  const [amenities, setAmenities] = useState('')
  const [slots, setSlots] = useState<TimeSlot[]>([])
  const [slot, setSlot] = useState<TimeSlot | null>(null)
  const [rooms, setRooms] = useState<RoomResponse[] | null>(null)
  const [error, setError] = useState('')
  const [booking, setBooking] = useState<RoomResponse | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [attendees, setAttendees] = useState('')
  const [success, setSuccess] = useState('')

  const filterParams = useCallback(() => {
    const params = new URLSearchParams()
    if (minCapacity) params.set('minCapacity', minCapacity)
    amenities
      .split(',')
      .map((a) => a.trim())
      .filter(Boolean)
      .forEach((a) => params.append('amenities', a))
    return params
  }, [minCapacity, amenities])

  // Valid start times come from the API: grid, working hours, 90-day horizon, 15-minute
  // break and "every room taken" are all evaluated server-side. Full slots never appear.
  useEffect(() => {
    const params = filterParams()
    params.set('date', date)
    params.set('durationMinutes', String(duration))
    setSlot(null)
    setRooms(null)
    api
      .get<TimeSlot[]>(`/rooms/time-slots?${params}`)
      .then((s) => {
        setSlots(s)
        setError('')
      })
      .catch((e) => {
        setSlots([])
        setError(describeError(e))
      })
  }, [date, duration, filterParams])

  async function pickSlot(s: TimeSlot) {
    setSlot(s)
    setSuccess('')
    setError('')
    try {
      const params = filterParams()
      params.set('start', s.startTime)
      params.set('end', s.endTime)
      setRooms(await api.get<RoomResponse[]>(`/rooms/availability?${params}`))
    } catch (e) {
      setRooms(null)
      setError(describeError(e))
    }
  }

  async function book(e: FormEvent) {
    e.preventDefault()
    if (!booking || !slot) return
    setError('')
    try {
      const res = await api.post<BookingResponse>('/bookings', {
        roomId: booking.id,
        title,
        description: description || null,
        attendeeCount: attendees ? Number(attendees) : null,
        startTime: slot.startTime,
        endTime: slot.endTime,
      })
      setSuccess(
        `Đã đặt phòng ${res.roomName}: "${res.title}" (${fmtHour(res.startTime)}–${fmtHour(res.endTime)} ngày ${date}).`,
      )
      setBooking(null)
      setTitle('')
      setDescription('')
      setAttendees('')
      await pickSlot(slot)
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Đặt phòng họp</h1>
      <p className="sub">
        Chọn ngày và thời lượng — hệ thống chỉ hiển thị các giờ bắt đầu còn đặt được (đã tính giờ nghỉ 15 phút giữa
        hai cuộc họp; khung giờ kín toàn bộ phòng sẽ không xuất hiện).
      </p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Ngày (tối đa 90 ngày tới)</label>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
          </div>
          <div className="field">
            <label>Thời lượng</label>
            <select value={duration} onChange={(e) => setDuration(Number(e.target.value))}>
              {DURATIONS.map((d) => (
                <option key={d.v} value={d.v}>
                  {d.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Số người (tối thiểu)</label>
            <input type="number" min={1} value={minCapacity} onChange={(e) => setMinCapacity(e.target.value)} />
          </div>
          <div className="field">
            <label>Tiện nghi (phẩy, vd projector,whiteboard)</label>
            <input value={amenities} onChange={(e) => setAmenities(e.target.value)} />
          </div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}
      {success && <div className="success">{success}</div>}

      <div className="card">
        <h2>Giờ bắt đầu còn trống · {date}</h2>
        {slots.length === 0 && <p className="muted">Không còn khung giờ nào đặt được với bộ lọc này.</p>}
        <div className="row" style={{ gap: 8 }}>
          {slots.map((s) => (
            <button
              key={s.startTime}
              className={slot?.startTime === s.startTime ? 'small' : 'small ghost'}
              onClick={() => pickSlot(s)}
              title={`${s.availableRooms} phòng trống`}
            >
              {fmtHour(s.startTime)}
              <span style={{ opacity: 0.75 }}> ({s.availableRooms})</span>
            </button>
          ))}
        </div>
      </div>

      {slot && rooms && (
        <div className="card">
          <h2>
            {rooms.length} phòng trống lúc {fmtHour(slot.startTime)}–{fmtHour(slot.endTime)} (phòng vừa đủ xếp trước)
          </h2>
          <table>
            <thead>
              <tr>
                <th>Phòng</th>
                <th>Vị trí</th>
                <th>Sức chứa</th>
                <th>Tiện nghi</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {rooms.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.name}</strong>
                  </td>
                  <td>{r.location}</td>
                  <td>{r.capacity}</td>
                  <td>{r.amenities.join(', ')}</td>
                  <td>
                    <button className="small" onClick={() => setBooking(r)}>
                      Đặt phòng này
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {booking && slot && (
        <div className="modal-backdrop" onClick={() => setBooking(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>
              Đặt {booking.name} · {date} {fmtHour(slot.startTime)}–{fmtHour(slot.endTime)}
            </h2>
            <form onSubmit={book}>
              <div className="field">
                <label>Tiêu đề cuộc họp *</label>
                <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
              </div>
              <div className="field">
                <label>Mô tả</label>
                <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
              </div>
              <div className="field">
                <label>Số người tham dự (≤ {booking.capacity})</label>
                <input type="number" min={1} value={attendees} onChange={(e) => setAttendees(e.target.value)} />
              </div>
              <div className="actions">
                <button type="button" className="ghost" onClick={() => setBooking(null)}>
                  Hủy
                </button>
                <button>Xác nhận đặt</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
