import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError, fmtHour, fmtTime, toApiDateTime, todayISO } from '../api/client'
import type { BookingResponse, BookingStatus, DirectoryEntry } from '../api/types'

interface TimeOption {
  time: string
  availableRooms: number
}

interface TimeSlotsResponse {
  startTimes: TimeOption[]
  endTimes: TimeOption[]
}

function isoToDate(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function plusDaysISO(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export default function MyBookingsPage() {
  const [status, setStatus] = useState<BookingStatus>('ACTIVE')
  const [history, setHistory] = useState(false)
  const [bookings, setBookings] = useState<BookingResponse[]>([])
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<BookingResponse | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [attendees, setAttendees] = useState('')
  const [date, setDate] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [startOptions, setStartOptions] = useState<TimeOption[]>([])
  const [endOptions, setEndOptions] = useState<TimeOption[]>([])
  const [directory, setDirectory] = useState<DirectoryEntry[]>([])
  const [participants, setParticipants] = useState<string[]>([])

  useEffect(() => {
    api.get<DirectoryEntry[]>('/users/directory').then(setDirectory).catch(() => {})
  }, [])

  // Same pickers as the create flow, scoped to the booking's room and ignoring the booking
  // itself, so its current slot stays selectable.
  useEffect(() => {
    if (!editing || !date) return
    const params = new URLSearchParams({ date, roomId: editing.roomId, excludeBookingId: editing.id })
    api
      .get<TimeSlotsResponse>(`/rooms/time-slots?${params}`)
      .then((r) => {
        setStartOptions(r.startTimes)
        setStart((cur) => (r.startTimes.some((o) => o.time === cur) ? cur : ''))
      })
      .catch((e) => {
        setStartOptions([])
        setError(describeError(e))
      })
  }, [editing, date])

  useEffect(() => {
    if (!editing || !date || !start) {
      setEndOptions([])
      return
    }
    const params = new URLSearchParams({
      date,
      start,
      roomId: editing.roomId,
      excludeBookingId: editing.id,
    })
    api
      .get<TimeSlotsResponse>(`/rooms/time-slots?${params}`)
      .then((r) => {
        setEndOptions(r.endTimes)
        setEnd((cur) => (r.endTimes.some((o) => o.time === cur) ? cur : ''))
      })
      .catch((e) => {
        setEndOptions([])
        setError(describeError(e))
      })
  }, [editing, date, start])

  const load = useCallback(async () => {
    try {
      const params = new URLSearchParams({ status })
      if (history) params.set('from', toApiDateTime('2020-01-01', '00:00'))
      setBookings(await api.get<BookingResponse[]>(`/bookings/me?${params}`))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }, [status, history])

  useEffect(() => {
    void load()
  }, [load])

  function openEdit(b: BookingResponse) {
    setEditing(b)
    setTitle(b.title)
    setDescription(b.description ?? '')
    setAttendees(b.attendeeCount ? String(b.attendeeCount) : '')
    setDate(isoToDate(b.startTime))
    setStart(b.startTime)
    setEnd(b.endTime)
    setParticipants(b.participants.map((p) => p.id))
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      await api.put(`/bookings/${editing.id}`, {
        title,
        description: description || null,
        attendeeCount: attendees ? Number(attendees) : null,
        startTime: start,
        endTime: end,
        participantIds: participants,
      })
      setEditing(null)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function cancel(b: BookingResponse) {
    if (!confirm(`Hủy booking "${b.title}"?`)) return
    try {
      await api.post(`/bookings/${b.id}/cancel`)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Booking của tôi</h1>
      <p className="sub">Mặc định hiển thị các booking sắp tới. Sửa/hủy chỉ khi booking còn ACTIVE.</p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Trạng thái</label>
            <select value={status} onChange={(e) => setStatus(e.target.value as BookingStatus)}>
              <option value="ACTIVE">ACTIVE</option>
              <option value="CANCELLED">CANCELLED</option>
            </select>
          </div>
          <div className="field">
            <label>Phạm vi</label>
            <select value={history ? '1' : '0'} onChange={(e) => setHistory(e.target.value === '1')}>
              <option value="0">Sắp tới</option>
              <option value="1">Cả lịch sử</option>
            </select>
          </div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Tiêu đề</th>
              <th>Phòng</th>
              <th>Thời gian</th>
              <th>Tham gia</th>
              <th>Trạng thái</th>
              <th>Google event</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {bookings.map((b) => (
              <tr key={b.id}>
                <td>
                  <strong>{b.title}</strong>
                  {b.description && <div className="muted">{b.description}</div>}
                </td>
                <td>{b.roomName}</td>
                <td>
                  {fmtTime(b.startTime)}
                  <br />→ {fmtTime(b.endTime)}
                </td>
                <td className="muted">
                  {b.participants.length === 0 ? '—' : b.participants.map((p) => p.fullName).join(', ')}
                </td>
                <td>
                  <span className={`badge ${b.status.toLowerCase()}`}>{b.status}</span>
                </td>
                <td className="mono">{b.googleEventId ?? '—'}</td>
                <td>
                  {b.status === 'ACTIVE' && (
                    <div className="row" style={{ gap: 6 }}>
                      <button className="small ghost" onClick={() => openEdit(b)}>
                        Sửa
                      </button>
                      <button className="small danger" onClick={() => cancel(b)}>
                        Hủy
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {bookings.length === 0 && <p className="muted">Không có booking nào.</p>}
      </div>

      {editing && (
        <div className="modal-backdrop" onClick={() => setEditing(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>Sửa booking · {editing.roomName}</h2>
            <p className="hint">Không đổi được phòng: hủy rồi đặt phòng khác.</p>
            <form onSubmit={saveEdit}>
              <div className="field">
                <label>Tiêu đề *</label>
                <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
              </div>
              <div className="field">
                <label>Mô tả</label>
                <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
              </div>
              <div className="row">
                <div className="field">
                  <label>Ngày</label>
                  <input
                    type="date"
                    value={date}
                    min={todayISO()}
                    max={plusDaysISO(90)}
                    onChange={(e) => {
                      const v = e.target.value
                      setDate(v && v < todayISO() ? todayISO() : v)
                    }}
                    required
                  />
                </div>
                <div className="field">
                  <label>Giờ bắt đầu</label>
                  <select value={start} onChange={(e) => setStart(e.target.value)} required>
                    <option value="">-- chọn --</option>
                    {startOptions.map((o) => (
                      <option key={o.time} value={o.time}>
                        {fmtHour(o.time)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>Giờ kết thúc</label>
                  <select value={end} onChange={(e) => setEnd(e.target.value)} disabled={!start} required>
                    <option value="">-- chọn --</option>
                    {endOptions.map((o) => (
                      <option key={o.time} value={o.time}>
                        {fmtHour(o.time)}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="field">
                <label>Số người tham dự</label>
                <input type="number" min={1} value={attendees} onChange={(e) => setAttendees(e.target.value)} />
              </div>
              <div className="field">
                <label>Người tham gia (thay đổi sẽ gửi email)</label>
                <div className="participant-list">
                  {directory.map((u) => (
                    <label key={u.id} className="dropdown-item">
                      <input
                        type="checkbox"
                        checked={participants.includes(u.id)}
                        onChange={() =>
                          setParticipants((cur) =>
                            cur.includes(u.id) ? cur.filter((x) => x !== u.id) : [...cur, u.id],
                          )
                        }
                      />
                      {u.fullName} <span className="muted">({u.email})</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="actions">
                <button type="button" className="ghost" onClick={() => setEditing(null)}>
                  Đóng
                </button>
                <button>Lưu</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
