import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type Tone = 'ok' | 'info' | 'warn' | 'err' | 'neutral'

const toneClass: Record<Tone, string> = {
  ok: 'bg-ok-bg text-ok-fg',
  info: 'bg-info-bg text-info-fg',
  warn: 'bg-warn-bg text-warn-fg',
  err: 'bg-err-bg text-err-fg',
  neutral: 'bg-surface2 text-muted border border-line2',
}

/** Status pill with a leading dot (design: `.chip`). */
export function Chip({ tone = 'neutral', small, children }: { tone?: Tone; small?: boolean; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 font-semibold tracking-[.02em] whitespace-nowrap leading-[18px] ${
        small ? 'text-[10px]' : 'text-[11px]'
      } ${toneClass[tone]}`}
    >
      <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />
      {children}
    </span>
  )
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-lg border border-line bg-surface ${className}`}>{children}</section>
}

export function CardHeader({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`flex flex-wrap items-center justify-between gap-3 border-b border-line2 px-4 py-3 ${className}`}>
      {children}
    </div>
  )
}

export function Button({
  primary,
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { primary?: boolean }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-md border px-3 font-medium whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-50 ${
        primary
          ? 'border-accent bg-accent text-on-accent'
          : 'border-line bg-surface text-fg hover:bg-surface2'
      } ${className}`}
    />
  )
}

/** Segmented control (design: `.seg`). */
export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex overflow-hidden rounded-md border border-line bg-surface">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={`h-[30px] cursor-pointer px-3 font-medium ${
            o.value === value ? 'bg-accent-bg text-accent' : 'text-muted hover:text-fg'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
