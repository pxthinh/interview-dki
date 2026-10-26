import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import AvailabilityPage from './pages/AvailabilityPage'
import MyBookingsPage from './pages/MyBookingsPage'
import RoomsPage from './pages/RoomsPage'
import AdminUsersPage from './pages/AdminUsersPage'
import AdminBookingsPage from './pages/AdminBookingsPage'
import AdminOutboxPage from './pages/AdminOutboxPage'
import AccountPage from './pages/AccountPage'
import FlowPage from './pages/FlowPage'

export default function App() {
  const { user, loading, logout } = useAuth()

  if (loading) return <div className="login-wrap">Đang tải…</div>
  if (!user) return <LoginPage />

  const isAdmin = user.role === 'ADMIN'
  return (
    <div className="layout">
      <nav className="nav">
        <span className="brand">DKI Rooms</span>
        <NavLink to="/">Lịch hôm nay</NavLink>
        <NavLink to="/book">Đặt phòng</NavLink>
        <NavLink to="/my-bookings">Booking của tôi</NavLink>
        {isAdmin && <NavLink to="/rooms">Phòng</NavLink>}
        {isAdmin && <NavLink to="/admin/users">Người dùng</NavLink>}
        {isAdmin && <NavLink to="/admin/bookings">Mọi booking</NavLink>}
        {isAdmin && <NavLink to="/admin/outbox">Calendar sync</NavLink>}
        <NavLink to="/flow">Luồng hoạt động</NavLink>
        <span className="spacer" />
        <span className="who">
          {user.fullName} {isAdmin && <span className="badge admin">ADMIN</span>}
        </span>
        <NavLink to="/account">Tài khoản</NavLink>
        <button className="small ghost" style={{ color: '#c7d4f2', borderColor: '#44598c' }} onClick={logout}>
          Đăng xuất
        </button>
      </nav>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/book" element={<AvailabilityPage />} />
        <Route path="/my-bookings" element={<MyBookingsPage />} />
        {isAdmin && <Route path="/rooms" element={<RoomsPage />} />}
        <Route path="/account" element={<AccountPage />} />
        <Route path="/flow" element={<FlowPage />} />
        {isAdmin && <Route path="/admin/users" element={<AdminUsersPage />} />}
        {isAdmin && <Route path="/admin/bookings" element={<AdminBookingsPage />} />}
        {isAdmin && <Route path="/admin/outbox" element={<AdminOutboxPage />} />}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  )
}
