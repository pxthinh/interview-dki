import { useMemo, useState } from 'react'
import type { DirectoryEntry } from '../api/types'

interface Props {
  directory: DirectoryEntry[]
  selected: string[]
  onChange: (ids: string[]) => void
}

/** Search-and-add picker: type to filter the company directory, click to add, chip to remove. */
export default function ParticipantPicker({ directory, selected, onChange }: Props) {
  const [query, setQuery] = useState('')

  const selectedUsers = useMemo(
    () => selected.map((id) => directory.find((u) => u.id === id)).filter((u): u is DirectoryEntry => !!u),
    [selected, directory],
  )

  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    return directory
      .filter((u) => !selected.includes(u.id))
      .filter((u) => !q || u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
      .slice(0, 8)
  }, [query, directory, selected])

  return (
    <div className="picker">
      {selectedUsers.length > 0 && (
        <div className="picker-chips">
          {selectedUsers.map((u) => (
            <span key={u.id} className="picker-chip">
              {u.fullName}
              <button
                type="button"
                aria-label={`Bỏ ${u.fullName}`}
                onClick={() => onChange(selected.filter((id) => id !== u.id))}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
      <input
        placeholder="Tìm theo tên hoặc email…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {(query || selectedUsers.length === 0) && (
        <div className="picker-results">
          {results.map((u) => (
            <button
              key={u.id}
              type="button"
              className="picker-row"
              onClick={() => {
                onChange([...selected, u.id])
                setQuery('')
              }}
            >
              <strong>{u.fullName}</strong> <span className="muted">{u.email}</span>
              <span className="picker-add">+ Thêm</span>
            </button>
          ))}
          {results.length === 0 && <p className="muted" style={{ margin: 6 }}>Không tìm thấy ai.</p>}
        </div>
      )}
    </div>
  )
}
