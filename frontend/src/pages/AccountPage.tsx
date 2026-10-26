import { useState, type FormEvent } from 'react'
import { api, describeError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export default function AccountPage() {
  const { user } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

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
