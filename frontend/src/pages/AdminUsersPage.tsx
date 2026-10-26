import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError } from '../api/client'
import type { Page, Role, UserResponse } from '../api/types'

export default function AdminUsersPage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<UserResponse> | null>(null)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [form, setForm] = useState({ email: '', fullName: '', password: '', role: 'USER' as Role })

  const load = useCallback(async () => {
    try {
      setData(await api.get<Page<UserResponse>>(`/users?page=${page}&size=10&sort=createdAt,desc`))
      setError('')
    } catch (e) {
      setError(describeError(e))
    }
  }, [page])

  useEffect(() => {
    void load()
  }, [load])

  async function create(e: FormEvent) {
    e.preventDefault()
    try {
      await api.post('/users', form)
      setCreating(false)
      setForm({ email: '', fullName: '', password: '', role: 'USER' })
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function patch(u: UserResponse, body: Partial<{ role: Role; active: boolean }>) {
    try {
      await api.patch(`/users/${u.id}`, body)
      await load()
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Người dùng (ADMIN)</h1>
      <p className="sub">Không có self-signup: admin cấp tài khoản. Khóa tài khoản có hiệu lực ngay với token đang dùng.</p>
      <div className="card">
        <button onClick={() => setCreating(true)}>+ Tạo người dùng</button>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Email</th>
              <th>Họ tên</th>
              <th>Vai trò</th>
              <th>Trạng thái</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map((u) => (
              <tr key={u.id}>
                <td>{u.email}</td>
                <td>{u.fullName}</td>
                <td>
                  <span className={`badge ${u.role === 'ADMIN' ? 'admin' : 'off'}`}>{u.role}</span>
                </td>
                <td>
                  <span className={`badge ${u.active ? 'active' : 'off'}`}>{u.active ? 'Hoạt động' : 'Khóa'}</span>
                </td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <button
                      className="small ghost"
                      onClick={() => patch(u, { role: u.role === 'ADMIN' ? 'USER' : 'ADMIN' })}
                    >
                      {u.role === 'ADMIN' ? 'Hạ USER' : 'Lên ADMIN'}
                    </button>
                    <button className={`small ${u.active ? 'danger' : ''}`} onClick={() => patch(u, { active: !u.active })}>
                      {u.active ? 'Khóa' : 'Mở khóa'}
                    </button>
                  </div>
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
              Trang {data.page.number + 1}/{Math.max(1, data.page.totalPages)} · {data.page.totalElements} người
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

      {creating && (
        <div className="modal-backdrop" onClick={() => setCreating(false)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>Tạo người dùng</h2>
            <form onSubmit={create}>
              <div className="field">
                <label>Email *</label>
                <input
                  type="email"
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                  required
                />
              </div>
              <div className="field">
                <label>Họ tên *</label>
                <input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} required />
              </div>
              <div className="field">
                <label>Mật khẩu tạm (≥ 8 ký tự) *</label>
                <input
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  required
                  minLength={8}
                />
              </div>
              <div className="field">
                <label>Vai trò</label>
                <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
                  <option value="USER">USER</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </div>
              <div className="actions">
                <button type="button" className="ghost" onClick={() => setCreating(false)}>
                  Đóng
                </button>
                <button>Tạo</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
