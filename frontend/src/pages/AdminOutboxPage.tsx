import { useCallback, useEffect, useState } from 'react'
import { api, describeError, fmtTime } from '../api/client'
import type { OutboxResponse, OutboxStatus } from '../api/types'

export default function AdminOutboxPage() {
  const [status, setStatus] = useState<OutboxStatus>('FAILED')
  const [items, setItems] = useState<OutboxResponse[]>([])
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setItems(await api.get<OutboxResponse[]>(`/admin/calendar-outbox?status=${status}`))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }, [status])

  useEffect(() => {
    void load()
    const t = setInterval(load, 10000)
    return () => clearInterval(t)
  }, [load])

  async function retry(id: number) {
    try {
      await api.post(`/admin/calendar-outbox/${id}/retry`)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Google Calendar sync (ADMIN)</h1>
      <p className="sub">
        Transactional outbox: mỗi thao tác booking sinh một bản ghi, processor đẩy lên Google 10s/lần với retry +
        backoff (tối đa 6 lần rồi FAILED). Trang tự refresh 10s.
      </p>
      <div className="card">
        <div className="row">
          <div className="field">
            <label>Trạng thái</label>
            <select value={status} onChange={(e) => setStatus(e.target.value as OutboxStatus)}>
              <option value="FAILED">FAILED</option>
              <option value="PENDING">PENDING</option>
              <option value="DONE">DONE</option>
            </select>
          </div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Hành động</th>
              <th>Booking</th>
              <th>Lần thử</th>
              <th>Trạng thái</th>
              <th>Lỗi gần nhất</th>
              <th>Xử lý lúc</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items.map((o) => (
              <tr key={o.id}>
                <td>{o.id}</td>
                <td>{o.action}</td>
                <td className="mono">{o.bookingId.slice(0, 8)}…</td>
                <td>{o.attempts}</td>
                <td>
                  <span className={`badge ${o.status.toLowerCase()}`}>{o.status}</span>
                </td>
                <td className="mono" style={{ maxWidth: 320, whiteSpace: 'pre-wrap' }}>
                  {o.lastError ? o.lastError.slice(0, 160) : '—'}
                </td>
                <td>{o.processedAt ? fmtTime(o.processedAt) : '—'}</td>
                <td>
                  {o.status === 'FAILED' && (
                    <button className="small" onClick={() => retry(o.id)}>
                      Retry
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {items.length === 0 && <p className="muted">Không có bản ghi {status}.</p>}
      </div>
    </div>
  )
}
