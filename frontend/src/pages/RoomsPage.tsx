import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError, fmtHour, todayISO } from '../api/client'
import type { BookingResponse, Page, RoomResponse, UserResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'

const emptyForm = { name: '', location: '', capacity: '8', amenities: '', googleCalendarId: '', adminUserId: '' }

export default function RoomsPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [rooms, setRooms] = useState<RoomResponse[]>([])
  const [onlyActive, setOnlyActive] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<RoomResponse | 'new' | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [scheduleRoom, setScheduleRoom] = useState<RoomResponse | null>(null)
  const [schedule, setSchedule] = useState<BookingResponse[]>([])
  const [scheduleDate, setScheduleDate] = useState(todayISO())
  const [users, setUsers] = useState<UserResponse[]>([])

  // admin-only: user list for the room-admin column and select
  useEffect(() => {
    if (!isAdmin) return
    api.get<Page<UserResponse>>('/users?size=200').then((p) => setUsers(p.content)).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin])

  const userEmail = (id: string | null) => users.find((u) => u.id === id)?.email ?? '—'

  const load = useCallback(async () => {
    try {
      setRooms(await api.get<RoomResponse[]>(`/rooms${onlyActive ? '?active=true' : ''}`))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }, [onlyActive])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!scheduleRoom) return
    api
      .get<BookingResponse[]>(`/rooms/${scheduleRoom.id}/schedule?from=${scheduleDate}&to=${scheduleDate}`)
      .then(setSchedule)
      .catch((e) => setError(describeError(e)))
  }, [scheduleRoom, scheduleDate])

  function openEdit(r: RoomResponse | 'new') {
    setEditing(r)
    setForm(
      r === 'new'
        ? emptyForm
        : {
            name: r.name,
            location: r.location ?? '',
            capacity: String(r.capacity),
            amenities: r.amenities.join(', '),
            googleCalendarId: r.googleCalendarId ?? '',
            adminUserId: r.adminUserId ?? '',
          },
    )
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    const body = {
      name: form.name,
      location: form.location || null,
      capacity: Number(form.capacity),
      amenities: form.amenities.split(',').map((a) => a.trim()).filter(Boolean),
      googleCalendarId: form.googleCalendarId || null,
      adminUserId: form.adminUserId || null,
    }
    try {
      if (editing === 'new') await api.post('/rooms', body)
      else if (editing) await api.put(`/rooms/${editing.id}`, body)
      setEditing(null)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function toggleActive(r: RoomResponse) {
    try {
      await api.patch(`/rooms/${r.id}/status`, { active: !r.active })
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Phòng họp</h1>
      <p className="sub">
        Danh sách phòng, lịch từng phòng{isAdmin && ', quản trị phòng (tạo phòng mới sẽ tự tạo Google Calendar khi sync bật)'}.
      </p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Hiển thị</label>
            <select value={onlyActive ? '1' : '0'} onChange={(e) => setOnlyActive(e.target.value === '1')}>
              <option value="1">Đang hoạt động</option>
              <option value="0">Tất cả</option>
            </select>
          </div>
          {isAdmin && <button onClick={() => openEdit('new')}>+ Thêm phòng</button>}
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Phòng</th>
              <th>Vị trí</th>
              <th>Sức chứa</th>
              <th>Tiện nghi</th>
              <th>Google Calendar</th>
              {isAdmin && <th>Admin phòng</th>}
              <th>Trạng thái</th>
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
                <td className="mono">{r.googleCalendarId ? r.googleCalendarId.slice(0, 18) + '…' : '—'}</td>
                {isAdmin && <td>{userEmail(r.adminUserId)}</td>}
                <td>
                  <span className={`badge ${r.active ? 'active' : 'off'}`}>{r.active ? 'Hoạt động' : 'Tắt'}</span>
                </td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <button className="small ghost" onClick={() => setScheduleRoom(r)}>
                      Lịch
                    </button>
                    {isAdmin && (
                      <>
                        <button className="small ghost" onClick={() => openEdit(r)}>
                          Sửa
                        </button>
                        <button className={`small ${r.active ? 'danger' : ''}`} onClick={() => toggleActive(r)}>
                          {r.active ? 'Tắt' : 'Bật'}
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing && (
        <div className="modal-backdrop" onClick={() => setEditing(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>{editing === 'new' ? 'Thêm phòng' : `Sửa phòng ${editing.name}`}</h2>
            <form onSubmit={save}>
              <div className="field">
                <label>Tên phòng *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
              </div>
              <div className="field">
                <label>Vị trí</label>
                <input value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />
              </div>
              <div className="field">
                <label>Sức chứa *</label>
                <input
                  type="number"
                  min={1}
                  value={form.capacity}
                  onChange={(e) => setForm({ ...form, capacity: e.target.value })}
                  required
                />
              </div>
              <div className="field">
                <label>Tiện nghi (phẩy)</label>
                <input value={form.amenities} onChange={(e) => setForm({ ...form, amenities: e.target.value })} />
              </div>
              <div className="field">
                <label>Google Calendar ID (trống = tự tạo khi sync bật)</label>
                <input
                  value={form.googleCalendarId}
                  onChange={(e) => setForm({ ...form, googleCalendarId: e.target.value })}
                />
              </div>
              <div className="field">
                <label>Admin phòng (booking sync về không rõ người đặt sẽ gán cho người này)</label>
                <select value={form.adminUserId} onChange={(e) => setForm({ ...form, adminUserId: e.target.value })}>
                  <option value="">{editing === 'new' ? 'Mặc định: tôi' : 'Giữ nguyên'}</option>
                  {users.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.email}
                    </option>
                  ))}
                </select>
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

      {scheduleRoom && (
        <div className="modal-backdrop" onClick={() => setScheduleRoom(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>Lịch phòng {scheduleRoom.name}</h2>
            <div className="field">
              <label>Ngày</label>
              <input type="date" value={scheduleDate} onChange={(e) => setScheduleDate(e.target.value)} />
            </div>
            <table>
              <thead>
                <tr>
                  <th>Giờ</th>
                  <th>Cuộc họp</th>
                  <th>Người đặt</th>
                </tr>
              </thead>
              <tbody>
                {schedule.map((b) => (
                  <tr key={b.id}>
                    <td>
                      {fmtHour(b.startTime)}–{fmtHour(b.endTime)}
                    </td>
                    <td>{b.title}</td>
                    <td>{b.userEmail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {schedule.length === 0 && <p className="muted">Trống cả ngày.</p>}
          </div>
        </div>
      )}
    </div>
  )
}
