import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, describeError, fmtHour, fmtTime, todayISO } from '../api/client'

function plusDaysISO(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
import type { BookingResponse, DirectoryEntry, RecurringBookingResponse, RoomResponse, SettingsResponse } from '../api/types'
import ParticipantPicker from '../components/ParticipantPicker'

interface TimeOption {
  time: string
  availableRooms: number
}

interface TimeSlotsResponse {
  startTimes: TimeOption[]
  endTimes: TimeOption[]
}

export default function AvailabilityPage() {
  const [date, setDate] = useState(todayISO())
  const [minCapacity, setMinCapacity] = useState('')
  // Amenities are toggle chips built from the real room data, never free text: a half-typed
  // word used to produce a filter no room matched, wiping the option lists mid-keystroke.
  const [allAmenities, setAllAmenities] = useState<string[]>([])
  const [amenities, setAmenities] = useState<string[]>([])
  // Debounced copy of the capacity input so typing "12" does not reload at "1".
  const [capacityFilter, setCapacityFilter] = useState('')
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setCapacityFilter(minCapacity), 400)
    return () => clearTimeout(t)
  }, [minCapacity])

  const [limits, setLimits] = useState<SettingsResponse>({
    maxDaysAhead: 90, maxRecurringWeeks: 26, calendarSyncEnabled: true, mailEnabled: true,
  })

  useEffect(() => {
    // amenity chips come from the admin-managed catalog
    api.get<string[]>('/amenities').then(setAllAmenities).catch(() => {})
    api.get<SettingsResponse>('/settings').then(setLimits).catch(() => {})
  }, [])
  const [startOptions, setStartOptions] = useState<TimeOption[]>([])
  const [endOptions, setEndOptions] = useState<TimeOption[]>([])
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [rooms, setRooms] = useState<RoomResponse[] | null>(null)
  const [error, setError] = useState('')
  const [booking, setBooking] = useState<RoomResponse | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [attendees, setAttendees] = useState('')
  const [success, setSuccess] = useState('')
  const [recurring, setRecurring] = useState(false)
  const [weeks, setWeeks] = useState('4')
  const [skippedInfo, setSkippedInfo] = useState<string[]>([])
  const [amenityOpen, setAmenityOpen] = useState(false)
  const [directory, setDirectory] = useState<DirectoryEntry[]>([])
  const [participants, setParticipants] = useState<string[]>([])

  useEffect(() => {
    api.get<DirectoryEntry[]>('/users/directory').then(setDirectory).catch(() => {})
  }, [])

  const filterParams = useCallback(() => {
    const params = new URLSearchParams({ date })
    if (capacityFilter) params.set('minCapacity', capacityFilter)
    amenities.forEach((a) => params.append('amenities', a))
    return params
  }, [date, capacityFilter, amenities])

  // Start times come from the API: grid, working hours, 90-day horizon, the 15-minute
  // break and "every room taken" are evaluated server-side; full times never show up.
  // A chosen start survives a reload as long as it is still offered.
  useEffect(() => {
    api
      .get<TimeSlotsResponse>(`/rooms/time-slots?${filterParams()}`)
      .then((r) => {
        setStartOptions(r.startTimes)
        setStart((cur) => (r.startTimes.some((o) => o.time === cur) ? cur : ''))
        setError('')
      })
      .catch((e) => {
        setStartOptions([])
        setStart('')
        setError(describeError(e))
      })
  }, [filterParams, refresh])

  // Once a start is picked, the valid end times for it come from the same API.
  // A chosen end survives a reload as long as it is still offered.
  useEffect(() => {
    if (!start) {
      setEndOptions([])
      setEnd('')
      return
    }
    const params = filterParams()
    params.set('start', start)
    api
      .get<TimeSlotsResponse>(`/rooms/time-slots?${params}`)
      .then((r) => {
        setEndOptions(r.endTimes)
        setEnd((cur) => (r.endTimes.some((o) => o.time === cur) ? cur : ''))
        setError('')
      })
      .catch((e) => {
        setEndOptions([])
        setEnd('')
        setError(describeError(e))
      })
  }, [start, filterParams, refresh])

  // Start + end chosen -> list the free rooms for that window.
  useEffect(() => {
    setRooms(null)
    if (!start || !end) return
    const params = filterParams()
    params.delete('date')
    params.set('start', start)
    params.set('end', end)
    api
      .get<RoomResponse[]>(`/rooms/availability?${params}`)
      .then((r) => {
        setRooms(r)
        setError('')
      })
      .catch((e) => setError(describeError(e)))
  }, [start, end, filterParams, refresh])

  async function book(e: FormEvent) {
    e.preventDefault()
    if (!booking || !start || !end) return
    setError('')
    setSkippedInfo([])
    try {
      const payload = {
        roomId: booking.id,
        title,
        description: description || null,
        attendeeCount: attendees ? Number(attendees) : null,
        startTime: start,
        endTime: end,
        participantIds: participants,
      }
      if (recurring) {
        const res = await api.post<RecurringBookingResponse>('/bookings/recurring', {
          ...payload,
          weeks: Number(weeks),
        })
        setSuccess(
          `Đã đặt chuỗi hàng tuần tại ${booking.name}: ${res.created.length} buổi` +
            (res.skipped.length > 0 ? `, ${res.skipped.length} buổi bị trùng (bỏ qua).` : '.'),
        )
        setSkippedInfo(
          res.skipped.map(
            (s) => `${fmtTime(s.startTime)}: trùng "${s.conflict.title}" (${fmtHour(s.conflict.startTime)}–${fmtHour(s.conflict.endTime)})`,
          ),
        )
      } else {
        const res = await api.post<BookingResponse>('/bookings', payload)
        setSuccess(
          `Đã đặt phòng ${res.roomName}: "${res.title}" (${fmtHour(res.startTime)}–${fmtHour(res.endTime)} ngày ${date}).`,
        )
      }
      setBooking(null)
      setTitle('')
      setDescription('')
      setAttendees('')
      setParticipants([])
      setRefresh((n) => n + 1) // re-pull options; selections stay while still bookable
    } catch (err) {
      setError(describeError(err))
    }
  }

  return (
    <div className="page">
      <h1>Đặt phòng họp</h1>
      <p className="sub">
        Chọn giờ bắt đầu rồi giờ kết thúc — hai danh sách đều do API trả về, chỉ gồm các mốc 15 phút còn đặt được
        (đã tính giờ nghỉ 15 phút giữa hai cuộc họp; mốc giờ kín toàn bộ phòng không xuất hiện).
      </p>
      <div className="card filters">
        <div className="row">
          <div className="field">
            <label>Ngày (hôm nay đến {limits.maxDaysAhead} ngày tới)</label>
            <input
              type="date"
              value={date}
              min={todayISO()}
              max={plusDaysISO(limits.maxDaysAhead)}
              onChange={(e) => {
                // the picker blocks past dates, but typed input can still produce one: clamp it
                const v = e.target.value
                setDate(v && v < todayISO() ? todayISO() : v)
              }}
              required
            />
          </div>
          <div className="field">
            <label>Giờ bắt đầu</label>
            <select value={start} onChange={(e) => setStart(e.target.value)}>
              <option value="">-- chọn --</option>
              {startOptions.map((o) => (
                <option key={o.time} value={o.time}>
                  {fmtHour(o.time)} ({o.availableRooms} phòng)
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Giờ kết thúc</label>
            <select value={end} onChange={(e) => setEnd(e.target.value)} disabled={!start}>
              <option value="">-- chọn --</option>
              {endOptions.map((o) => (
                <option key={o.time} value={o.time}>
                  {fmtHour(o.time)} ({o.availableRooms} phòng)
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Số người (tối thiểu)</label>
            <input type="number" min={1} value={minCapacity} onChange={(e) => setMinCapacity(e.target.value)} />
          </div>
          <div className="field dropdown">
            <label>Tiện nghi</label>
            <button type="button" className="ghost dropdown-toggle" onClick={() => setAmenityOpen((o) => !o)}>
              {amenities.length === 0 ? 'Tất cả' : amenities.join(', ')} ▾
            </button>
            {amenityOpen && (
              <>
                <div className="dropdown-backdrop" onClick={() => setAmenityOpen(false)} />
                <div className="dropdown-panel">
                  {allAmenities.map((a) => (
                    <label key={a} className="dropdown-item">
                      <input
                        type="checkbox"
                        checked={amenities.includes(a)}
                        onChange={() =>
                          setAmenities((cur) => (cur.includes(a) ? cur.filter((x) => x !== a) : [...cur, a]))
                        }
                      />
                      {a}
                    </label>
                  ))}
                  {amenities.length > 0 && (
                    <button type="button" className="small ghost" style={{ marginTop: 6, width: '100%' }}
                            onClick={() => setAmenities([])}>
                      Bỏ chọn tất cả
                    </button>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
        {startOptions.length === 0 && !error && (
          <p className="muted" style={{ marginTop: 10 }}>
            Không còn giờ bắt đầu nào đặt được với bộ lọc này.
          </p>
        )}
      </div>

      {error && <div className="error">{error}</div>}
      {success && <div className="success">{success}</div>}
      {skippedInfo.length > 0 && (
        <div className="card">
          <strong>Các buổi bị trùng lịch (không đặt):</strong>
          <ul>
            {skippedInfo.map((s) => (
              <li key={s} className="muted">
                {s}
              </li>
            ))}
          </ul>
        </div>
      )}

      {start && end && rooms && (
        <div className="card">
          <h2>
            {rooms.length} phòng trống lúc {fmtHour(start)}–{fmtHour(end)} (phòng vừa đủ xếp trước)
          </h2>
          <table>
            <thead>
              <tr>
                <th>Phòng</th>
                <th>Vị trí</th>
                <th>Sức chứa</th>
                <th>Tiện nghi</th>
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
                  <td>
                    <button className="small" onClick={() => setBooking(r)}>
                      Đặt phòng này
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {booking && start && end && (
        <div className="modal-backdrop" onClick={() => setBooking(null)}>
          <div className="card modal" onClick={(e) => e.stopPropagation()}>
            <h2>
              Đặt {booking.name} · {date} {fmtHour(start)}–{fmtHour(end)}
            </h2>
            <form onSubmit={book}>
              <div className="field">
                <label>Tiêu đề cuộc họp *</label>
                <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
              </div>
              <div className="field">
                <label>Mô tả</label>
                <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
              </div>
              <div className="field">
                <label>Số người tham dự (≤ {booking.capacity})</label>
                <input type="number" min={1} value={attendees} onChange={(e) => setAttendees(e.target.value)} />
              </div>
              <div className="field">
                <label>Mời tham gia (gửi email thông báo)</label>
                <ParticipantPicker directory={directory} selected={participants} onChange={setParticipants} />
              </div>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={recurring}
                    onChange={(e) => setRecurring(e.target.checked)}
                    style={{ marginRight: 6 }}
                  />
                  Lặp hàng tuần (buổi trùng lịch sẽ bị bỏ qua và báo lại)
                </label>
                {recurring && (
                  <input
                    type="number"
                    min={2}
                    max={limits.maxRecurringWeeks}
                    value={weeks}
                    onChange={(e) => setWeeks(e.target.value)}
                    placeholder={`Số tuần (2–${limits.maxRecurringWeeks})`}
                    required
                    style={{ marginTop: 6 }}
                  />
                )}
              </div>
              {error && <div className="error">{error}</div>}
              <div className="actions">
                <button type="button" className="ghost" onClick={() => setBooking(null)}>
                  Hủy
                </button>
                <button>Xác nhận đặt</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
