import { useEffect, useState, type FormEvent } from 'react'
import { api, describeError } from '../api/client'
import type { SettingsResponse } from '../api/types'

export default function AdminSettingsPage() {
  const [maxDays, setMaxDays] = useState('')
  const [maxWeeks, setMaxWeeks] = useState('')
  const [syncOn, setSyncOn] = useState(true)
  const [mailOn, setMailOn] = useState(true)
  const [amenities, setAmenities] = useState<string[]>([])
  const [newAmenity, setNewAmenity] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  // values as last loaded/saved: only fields that differ are sent, so saving the limits never
  // silently flips a toggle another admin changed in the meantime
  const [loaded, setLoaded] = useState<SettingsResponse | null>(null)

  async function load() {
    try {
      const s = await api.get<SettingsResponse>('/settings')
      setLoaded(s)
      setMaxDays(String(s.maxDaysAhead))
      setMaxWeeks(String(s.maxRecurringWeeks))
      setSyncOn(s.calendarSyncEnabled)
      setMailOn(s.mailEnabled)
      setAmenities(await api.get<string[]>('/amenities'))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function saveLimits(e: FormEvent) {
    e.preventDefault()
    setSuccess('')
    const changes: Partial<SettingsResponse> = {}
    if (!loaded || Number(maxDays) !== loaded.maxDaysAhead) changes.maxDaysAhead = Number(maxDays)
    if (!loaded || Number(maxWeeks) !== loaded.maxRecurringWeeks) changes.maxRecurringWeeks = Number(maxWeeks)
    if (!loaded || syncOn !== loaded.calendarSyncEnabled) changes.calendarSyncEnabled = syncOn
    if (!loaded || mailOn !== loaded.mailEnabled) changes.mailEnabled = mailOn
    if (Object.keys(changes).length === 0) {
      setSuccess('Không có thay đổi nào.')
      return
    }
    try {
      setLoaded(await api.put<SettingsResponse>('/admin/settings', changes))
      setSuccess('Đã lưu cấu hình. Booking đã đặt trước đó giữ nguyên; luật mới áp cho đặt/sửa từ bây giờ.')
      setError('')
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function addAmenity(e: FormEvent) {
    e.preventDefault()
    const name = newAmenity.trim()
    if (!name) return
    try {
      await api.post('/admin/amenities', { name })
      setNewAmenity('')
      setAmenities(await api.get<string[]>('/amenities'))
      setError('')
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function renameAmenity(name: string) {
    const newName = prompt(`Tên mới cho tiện nghi "${name}" (áp cho cả các phòng đang gắn):`, name)
    if (!newName || newName.trim() === name) return
    try {
      await api.put('/admin/amenities', { name, newName: newName.trim() })
      setAmenities(await api.get<string[]>('/amenities'))
      setError('')
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function removeAmenity(name: string) {
    if (!confirm(`Xóa tiện nghi "${name}" khỏi danh mục? Phòng đang gắn tiện nghi này giữ nguyên.`)) return
    try {
      await api.del(`/admin/amenities?name=${encodeURIComponent(name)}`)
      setAmenities(await api.get<string[]>('/amenities'))
      setError('')
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Cấu hình hệ thống</h1>
      <p className="sub">Giới hạn đặt phòng và danh mục tiện nghi, áp cho toàn hệ thống.</p>
      {error && <div className="error">{error}</div>}
      {success && <div className="success">{success}</div>}

      <div className="card">
        <h2>Giới hạn đặt phòng</h2>
        <form onSubmit={saveLimits}>
          <div className="row">
            <div className="field">
              <label>Đặt trước tối đa (ngày, 1–365)</label>
              <input type="number" min={1} max={365} value={maxDays}
                     onChange={(e) => setMaxDays(e.target.value)} required />
            </div>
            <div className="field">
              <label>Chuỗi hàng tuần tối đa (tuần, 2–52)</label>
              <input type="number" min={2} max={52} value={maxWeeks}
                     onChange={(e) => setMaxWeeks(e.target.value)} required />
            </div>
          </div>
          <div className="row" style={{ marginTop: 10 }}>
            <div className="field">
              <label>
                <input type="checkbox" checked={syncOn} onChange={(e) => setSyncOn(e.target.checked)}
                       style={{ marginRight: 6 }} />
                Đồng bộ Google Calendar (tắt = tạm dừng đẩy/kéo sự kiện; hàng chờ giữ nguyên, bật lại chạy tiếp)
              </label>
            </div>
          </div>
          <div className="row">
            <div className="field">
              <label>
                <input type="checkbox" checked={mailOn} onChange={(e) => setMailOn(e.target.checked)}
                       style={{ marginRight: 6 }} />
                Gửi email thông báo (tắt = không gửi thư mời/hủy/thu hồi)
              </label>
            </div>
          </div>
          <p className="hint">
            Toggle đồng bộ áp cho cả lịch phòng (GOOGLE_CALENDAR_ENABLED) lẫn lịch cá nhân (GOOGLE_OAUTH_ENABLED);
            toggle email áp khi MAIL_ENABLED bật.
          </p>
          <div className="actions">
            <button>Lưu cấu hình</button>
          </div>
        </form>
      </div>

      <div className="card">
        <h2>Danh mục tiện nghi</h2>
        <p className="hint">Nguồn cho form phòng và bộ lọc tìm phòng. Xóa khỏi danh mục không ảnh hưởng phòng đang gắn.</p>
        <form onSubmit={addAmenity} className="row" style={{ alignItems: 'flex-end' }}>
          <div className="field">
            <label>Tiện nghi mới</label>
            <input value={newAmenity} onChange={(e) => setNewAmenity(e.target.value)} maxLength={100} />
          </div>
          <button className="small">Thêm</button>
        </form>
        <table>
          <tbody>
            {amenities.map((a) => (
              <tr key={a}>
                <td>{a}</td>
                <td style={{ textAlign: 'right' }}>
                  <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                    <button className="small ghost" onClick={() => renameAmenity(a)}>
                      Sửa
                    </button>
                    <button className="small danger ghost" onClick={() => removeAmenity(a)}>
                      Xóa
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {amenities.length === 0 && <p className="muted">Danh mục trống.</p>}
      </div>
    </div>
  )
}
