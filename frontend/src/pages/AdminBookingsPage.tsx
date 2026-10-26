import { useCallback, useEffect, useState } from 'react'
import { api, describeError, fmtTime } from '../api/client'
import type { BookingResponse, Page, RoomResponse } from '../api/types'

export default function AdminBookingsPage() {
  const [rooms, setRooms] = useState<RoomResponse[]>([])
  const [roomId, setRoomId] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<BookingResponse> | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api.get<RoomResponse[]>('/rooms').then(setRooms).catch(() => {})
  }, [])

  const load = useCallback(async () => {
    try {
      const params = new URLSearchParams({ page: String(page), size: '10', sort: 'startTime,desc' })
      if (roomId) params.set('roomId', roomId)
      if (status) params.set('status', status)
      setData(await api.get<Page<BookingResponse>>(`/bookings?${params}`))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }, [page, roomId, status])

  useEffect(() => {
    void load()
  }, [load])

  async function cancel(b: BookingResponse) {
    if (!confirm(`Hủy booking "${b.title}" của ${b.userEmail}?`)) return
    try {
      await api.post(`/bookings/${b.id}/cancel`)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Mọi booking (ADMIN)</h1>
      <p className="sub">Tìm kiếm có filter và phân trang. Admin hủy được booking của bất kỳ ai.</p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Phòng</label>
            <select
              value={roomId}
              onChange={(e) => {
                setRoomId(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Tất cả</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Trạng thái</label>
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Tất cả</option>
              <option value="ACTIVE">ACTIVE</option>
              <option value="CANCELLED">CANCELLED</option>
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
              <th>Người đặt</th>
              <th>Thời gian</th>
              <th>Trạng thái</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map((b) => (
              <tr key={b.id}>
                <td>
                  <strong>{b.title}</strong>
                </td>
                <td>{b.roomName}</td>
                <td>{b.userEmail}</td>
                <td>
                  {fmtTime(b.startTime)}
                  <br />→ {fmtTime(b.endTime)}
                </td>
                <td>
                  <span className={`badge ${b.status.toLowerCase()}`}>{b.status}</span>
                </td>
                <td>
                  {b.status === 'ACTIVE' && (
                    <button className="small danger" onClick={() => cancel(b)}>
                      Hủy
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {data && (
          <div className="pager">
            <button className="small ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
              ← Trước
            </button>
            <span>
              Trang {data.page.number + 1}/{Math.max(1, data.page.totalPages)} · {data.page.totalElements} booking
            </span>
            <button
              className="small ghost"
              disabled={page + 1 >= data.page.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Sau →
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
