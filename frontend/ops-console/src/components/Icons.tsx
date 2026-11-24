// Stroke icons from the design export (24×24, currentColor).
import type { ReactNode } from 'react'

function Icon({ children, size = 16, width = 1.8 }: { children: ReactNode; size?: number; width?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export const Icons = {
  overview: () => (
    <Icon>
      <rect x="3" y="3" width="7" height="7" rx="1" />
      <rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" />
      <rect x="14" y="14" width="7" height="7" rx="1" />
    </Icon>
  ),
  payments: () => (
    <Icon>
      <path d="M4 7h16l-4-4M20 17H4l4 4" />
    </Icon>
  ),
  sanctions: () => (
    <Icon>
      <path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z" />
    </Icon>
  ),
  settlement: () => (
    <Icon>
      <path d="M12 3l9 5-9 5-9-5z" />
      <path d="M3 13l9 5 9-5" />
    </Icon>
  ),
  recon: () => (
    <Icon>
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <path d="M8 12l3 3 5-6" />
    </Icon>
  ),
  create: () => (
    <Icon>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v8M8 12h8" />
    </Icon>
  ),
  ledger: () => (
    <Icon>
      <path d="M3 10l9-6 9 6M5 10v8M10 10v8M14 10v8M19 10v8M3 21h18" />
    </Icon>
  ),
  refdata: () => (
    <Icon>
      <path d="M4 4h11a3 3 0 013 3v13H7a3 3 0 01-3-3z" />
      <path d="M4 17a3 3 0 013-3h11" />
    </Icon>
  ),
  chevron: () => (
    <Icon size={14} width={2}>
      <path d="M6 9l6 6 6-6" />
    </Icon>
  ),
  sun: () => (
    <Icon>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Icon>
  ),
  moon: () => (
    <Icon>
      <path d="M20 14.5A8 8 0 019.5 4a8 8 0 1010.5 10.5z" />
    </Icon>
  ),
  logo: () => (
    <Icon size={16} width={2.4}>
      <path d="M4 17l5-10 3 6 3-6 5 10" />
    </Icon>
  ),
}
