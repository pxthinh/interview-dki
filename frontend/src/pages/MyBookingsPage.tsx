import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError, fmtTime, toApiDateTime } from '../api/client'
import type { BookingResponse, BookingStatus } from '../api/types'

function isoToParts(iso: string): { date: string; time: string } {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  }
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
    const s = isoToParts(b.startTime)
    const e = isoToParts(b.endTime)
    setDate(s.date)
    setStart(s.time)
    setEnd(e.time)
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      await api.put(`/bookings/${editing.id}`, {
        title,
        description: description || null,
        attendeeCount: attendees ? Number(attendees) : null,
        startTime: toApiDateTime(date, start),
        endTime: toApiDateTime(date, end),
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
              </div>
              <div className="field">
                <label>Số người tham dự</label>
                <input type="number" min={1} value={attendees} onChange={(e) => setAttendees(e.target.value)} />
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
