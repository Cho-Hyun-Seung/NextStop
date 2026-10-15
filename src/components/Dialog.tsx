import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'
import { X } from 'lucide-react'

export function Dialog({ title, onClose, children, className = '' }: { title: string; onClose: () => void; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    const opener = document.activeElement
    if (dialog && !dialog.open) dialog.showModal()
    return () => {
      if (dialog?.open) dialog.close()
      const destination = opener instanceof HTMLElement && opener.isConnected ? opener : document.getElementById('main-content')
      destination?.focus({ preventScroll: true })
    }
  }, [])
  return (
    <dialog ref={ref} className={`dialog ${className}`} aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); onClose() }} onClick={(event) => { if (event.target === event.currentTarget) { const bounds = event.currentTarget.getBoundingClientRect(); if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose() } }}>
      <div className="dialog-header"><h2 id={titleId}>{title}</h2><button type="button" className="icon-button" aria-label="닫기" onClick={onClose}><X size={21} /></button></div>
      {children}
    </dialog>
  )
}
