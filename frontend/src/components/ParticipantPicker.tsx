import { useMemo, useRef, useState } from 'react'
import type { DirectoryEntry } from '../api/types'

interface Props {
  directory: DirectoryEntry[]
  selected: string[]
  onChange: (ids: string[]) => void
}

/**
 * Combobox: click/focus opens the full directory, typing filters it, Enter or click adds,
 * chips remove with × (or Backspace on an empty query). The list stays open while adding
 * several people and closes on outside click.
 */
export default function ParticipantPicker({ directory, selected, onChange }: Props) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const selectedUsers = useMemo(
    () => selected.map((id) => directory.find((u) => u.id === id)).filter((u): u is DirectoryEntry => !!u),
    [selected, directory],
  )

  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    return directory
      .filter((u) => !selected.includes(u.id))
      .filter((u) => !q || u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
  }, [query, directory, selected])

  function add(u: DirectoryEntry) {
    onChange([...selected, u.id])
    setQuery('')
    inputRef.current?.focus()
  }

  return (
    <div className="picker dropdown">
      <div className="picker-box" onClick={() => inputRef.current?.focus()}>
        {selectedUsers.map((u) => (
          <span key={u.id} className="picker-chip" title={u.email}>
            {u.fullName}
            <button
              type="button"
              aria-label={`Bỏ ${u.fullName}`}
              onClick={(e) => {
                e.stopPropagation()
                onChange(selected.filter((id) => id !== u.id))
              }}
            >
              ×
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="picker-input"
          placeholder={selectedUsers.length === 0 ? 'Bấm để chọn, gõ để tìm…' : 'Thêm người…'}
          value={query}
          onFocus={() => setOpen(true)}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              if (results.length > 0) add(results[0])
            } else if (e.key === 'Backspace' && query === '' && selected.length > 0) {
              onChange(selected.slice(0, -1))
            } else if (e.key === 'Escape') {
              setOpen(false)
            }
          }}
        />
      </div>
      {open && (
        <>
          <div className="dropdown-backdrop" onClick={() => setOpen(false)} />
          <div className="dropdown-panel picker-panel">
            {results.map((u, i) => (
              <button key={u.id} type="button" className={`picker-row${i === 0 ? ' first' : ''}`} onClick={() => add(u)}>
                <strong>{u.fullName}</strong> <span className="muted">{u.email}</span>
                <span className="picker-add">{i === 0 && query ? '↵ Thêm' : '+ Thêm'}</span>
              </button>
            ))}
            {results.length === 0 && (
              <p className="muted" style={{ margin: 8 }}>
                {directory.length === selected.length ? 'Đã chọn tất cả mọi người.' : 'Không tìm thấy ai.'}
              </p>
            )}
          </div>
        </>
      )}
    </div>
  )
}
