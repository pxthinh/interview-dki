import { useState, type FormEvent } from 'react'
import { api, describeError, toApiDateTime, todayISO } from '../api/client'
import type { BookingResponse, RoomResponse } from '../api/types'

export default function AvailabilityPage() {
  const [date, setDate] = useState(todayISO())
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('10:00')
  const [minCapacity, setMinCapacity] = useState('')
  const [amenities, setAmenities] = useState('')
  const [rooms, setRooms] = useState<RoomResponse[] | null>(null)
  const [error, setError] = useState('')
  const [booking, setBooking] = useState<RoomResponse | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [attendees, setAttendees] = useState('')
  const [success, setSuccess] = useState('')

  async function search(e?: FormEvent) {
    e?.preventDefault()
    setError('')
    setSuccess('')
    try {
      const params = new URLSearchParams({
        start: toApiDateTime(date, start),
        end: toApiDateTime(date, end),
      })
      if (minCapacity) params.set('minCapacity', minCapacity)
      amenities
        .split(',')
        .map((a) => a.trim())
        .filter(Boolean)
        .forEach((a) => params.append('amenities', a))
      setRooms(await api.get<RoomResponse[]>(`/rooms/availability?${params}`))
    } catch (err) {
      setRooms(null)
      setError(describeError(err))
    }
  }

  async function book(e: FormEvent) {
    e.preventDefault()
    if (!booking) return
    setError('')
    try {
      const res = await api.post<BookingResponse>('/bookings', {
        roomId: booking.id,
        title,
        description: description || null,
        attendeeCount: attendees ? Number(attendees) : null,
        startTime: toApiDateTime(date, start),
        endTime: toApiDateTime(date, end),
      })
      setSuccess(`Đã đặt phòng ${res.roomName}: "${res.title}" (${start}–${end} ngày ${date}).`)
      setBooking(null)
      setTitle('')
      setDescription('')
      setAttendees('')
      await search()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Tìm phòng trống & đặt phòng</h1>
      <p className="sub">
        Quy tắc: lưới 15 phút, dài 15 phút–8 giờ, trong 07:00–20:00 cùng ngày, tối đa 90 ngày tới.
      </p>
      <div className="card">
        <form className="row" onSubmit={search}>
          <div className="field">
            <label>Ngày</label>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
          </div>
          <div className="field">
            <label>Từ</label>
            <input type="time" step={900} value={start} onChange={(e) => setStart(e.target.value)} required />
          </div>
          <div className="field">
            <label>Đến</label>
            <input type="time" step={900} value={end} onChange={(e) => setEnd(e.target.value)} required />
          </div>
          <div className="field">
            <label>Số người (tối thiểu)</label>
            <input type="number" min={1} value={minCapacity} onChange={(e) => setMinCapacity(e.target.value)} />
          </div>
          <div className="field">
            <label>Tiện nghi (phẩy, vd projector,whiteboard)</label>
            <input value={amenities} onChange={(e) => setAmenities(e.target.value)} />
          </div>
          <button>Tìm phòng trống</button>
        </form>
      </div>

      {error && <div className="error">{error}</div>}
      {success && <div className="success">{success}</div>}

      {rooms && (
        <div className="card">
          <h2>{rooms.length} phòng trống (phòng vừa đủ xếp trước)</h2>
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
          {rooms.length === 0 && <p className="muted">Không còn phòng trống khung giờ này.</p>}
        </div>
      )}

      {booking && (
        <div className="modal-backdrop" onClick={() => setBooking(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>
              Đặt {booking.name} · {date} {start}–{end}
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
