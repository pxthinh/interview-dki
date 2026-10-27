import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, describeError, fmtTime } from '../api/client'
import type { GoogleIntegrationStatus } from '../api/types'
import { useAuth } from '../auth/AuthContext'

export default function AccountPage() {
  const { user } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [google, setGoogle] = useState<GoogleIntegrationStatus | null>(null)
  const [googleMsg, setGoogleMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()

  const loadGoogle = useCallback(async () => {
    try {
      setGoogle(await api.get<GoogleIntegrationStatus>('/integrations/google'))
    } catch {
      setGoogle(null)
    }
  }, [])

  useEffect(() => {
    void loadGoogle()
  }, [loadGoogle])

  // result of the OAuth round-trip: the backend callback redirects to /account?google=...
  useEffect(() => {
    const result = searchParams.get('google')
    if (!result) return
    setGoogleMsg(
      result === 'connected'
        ? { ok: true, text: 'Đã kết nối Google Calendar cá nhân. Các cuộc họp sắp tới sẽ được đồng bộ trong giây lát.' }
        : result === 'scope'
          ? { ok: false, text: 'Bạn chưa cấp quyền Google Calendar. Hãy kết nối lại và tích chọn quyền xem/sửa lịch.' }
          : { ok: false, text: 'Kết nối Google thất bại, vui lòng thử lại.' },
    )
    setSearchParams({}, { replace: true })
  }, [searchParams, setSearchParams])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setSuccess('')
    try {
      await api.post('/users/me/password', { currentPassword: current, newPassword: next })
      setSuccess('Đã đổi mật khẩu. Dùng mật khẩu mới cho lần đăng nhập sau.')
      setCurrent('')
      setNext('')
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function connectGoogle() {
    try {
      // XHR carries the Bearer token; the browser navigation to Google needs none
      const { authUrl } = await api.post<{ authUrl: string }>('/integrations/google/connect')
      window.location.href = authUrl
    } catch (err) {
      setGoogleMsg({ ok: false, text: describeError(err) })
    }
  }

  async function disconnectGoogle() {
    if (!confirm('Ngắt kết nối Google? Lịch họp đã tạo trên calendar cá nhân vẫn giữ nguyên.')) return
    try {
      await api.del('/integrations/google')
      setGoogleMsg({ ok: true, text: 'Đã ngắt kết nối Google.' })
      await loadGoogle()
    } catch (err) {
      setGoogleMsg({ ok: false, text: describeError(err) })
    }
  }

  return (
    <div className="page">
      <h1>Tài khoản</h1>
      <div className="card">
        <h2>Thông tin</h2>
        <table>
          <tbody>
            <tr>
              <th>Email</th>
              <td>{user?.email}</td>
            </tr>
            <tr>
              <th>Họ tên</th>
              <td>{user?.fullName}</td>
            </tr>
            <tr>
              <th>Vai trò</th>
              <td>{user?.role}</td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* outside the card: the OAuth result must show even if the status call fails */}
      {googleMsg && !google?.enabled && <div className={googleMsg.ok ? 'success' : 'error'}>{googleMsg.text}</div>}
      {google?.enabled && (
        <div className="card" style={{ maxWidth: 520 }}>
          <h2>Google Calendar cá nhân</h2>
          <p className="hint">
            Kết nối tài khoản Google để mọi cuộc họp bạn đặt hoặc được mời tự xuất hiện trên lịch Google của bạn
            (bản sao một chiều; sửa trên Google không đồng bộ ngược).
          </p>
          {googleMsg && <div className={googleMsg.ok ? 'success' : 'error'}>{googleMsg.text}</div>}
          {google.connected ? (
            <>
              <p>
                Đã kết nối: <strong>{google.googleEmail}</strong>{' '}
                <span className={`badge ${google.status === 'CONNECTED' ? 'active' : 'pending'}`}>
                  {google.status}
                </span>
                {google.connectedAt && <span className="muted"> · từ {fmtTime(google.connectedAt)}</span>}
              </p>
              {google.status === 'EXPIRED' && (
                <p className="error">Quyền truy cập Google đã bị thu hồi hoặc hết hạn — hãy kết nối lại.</p>
              )}
              <div className="row" style={{ gap: 8 }}>
                {google.status === 'EXPIRED' && <button onClick={connectGoogle}>Kết nối lại</button>}
                <button className="danger ghost" onClick={disconnectGoogle}>
                  Ngắt kết nối
                </button>
              </div>
            </>
          ) : (
            <button onClick={connectGoogle}>Kết nối Google Calendar</button>
          )}
        </div>
      )}

      <div className="card" style={{ maxWidth: 420 }}>
        <h2>Đổi mật khẩu</h2>
        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div className="field">
            <label>Mật khẩu hiện tại</label>
            <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
          </div>
          <div className="field">
            <label>Mật khẩu mới (≥ 8 ký tự)</label>
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={8} />
          </div>
          {error && <div className="error">{error}</div>}
          {success && <div className="success">{success}</div>}
          <button>Đổi mật khẩu</button>
        </form>
      </div>
    </div>
  )
}
