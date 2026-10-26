import { useState, type FormEvent } from 'react'
import { describeError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('alice@dki.local')
  const [password, setPassword] = useState('User@123')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await login(email, password)
    } catch (err) {
      setError(describeError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="card login-card">
        <h1>DKI Rooms</h1>
        <p className="sub" style={{ textAlign: 'center' }}>
          Hệ thống đặt phòng họp
        </p>
        <form onSubmit={submit}>
          <div className="field">
            <label>Email</label>
            <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" required />
          </div>
          <div className="field">
            <label>Mật khẩu</label>
            <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" required />
          </div>
          {error && <div className="error">{error}</div>}
          <button disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
          <p className="hint">
            Tài khoản mẫu: admin@dki.local / Admin@123 · alice@dki.local / User@123
          </p>
        </form>
      </div>
    </div>
  )
}
