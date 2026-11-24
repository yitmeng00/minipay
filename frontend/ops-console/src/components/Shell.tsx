import { useEffect, useRef, useState, type ReactNode } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { useBankInfo } from '../lib/api'
import { BANKS, useBank } from '../lib/banks'
import { useTheme } from '../lib/theme'
import { Icons } from './Icons'
import { Chip } from './ui'

type NavItem = { label: string; icon: () => ReactNode; to?: string; milestone?: string }

// Screens from the design. Only those with a `to` are powered by a delivered milestone;
// the rest are shown so the information architecture is visible, but are not clickable yet.
const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: 'Monitor',
    items: [
      { label: 'Overview', icon: Icons.overview, milestone: 'M3' },
      { label: 'Payments', icon: Icons.payments, to: '/payments' },
      { label: 'Sanctions queue', icon: Icons.sanctions, milestone: 'M6' },
      { label: 'Settlement', icon: Icons.settlement, milestone: 'M7' },
      { label: 'Reconciliation', icon: Icons.recon, milestone: 'M8' },
    ],
  },
  {
    section: 'Operate',
    items: [
      { label: 'Create payment', icon: Icons.create, to: '/payments/new' },
      { label: 'Accounts & ledger', icon: Icons.ledger, to: '/accounts' },
      { label: 'Reference data', icon: Icons.refdata, milestone: 'M3' },
    ],
  },
]

function Sidebar() {
  const { pathname } = useLocation()
  return (
    <aside className="flex max-w-full flex-[1_1_232px] flex-col gap-1 border-r border-line bg-surface px-3 py-4 box-border">
      <div className="flex items-center gap-2.5 px-2.5 pt-1 pb-3">
        <div className="flex size-7 items-center justify-center rounded-[7px] bg-accent text-surface">
          <Icons.logo />
        </div>
        <div className="flex flex-col">
          <span className="text-sm font-bold tracking-[-0.01em]">MiniPay</span>
          <span className="text-[11px] text-muted">Operations console</span>
        </div>
      </div>
      <nav aria-label="Main" className="flex flex-col gap-0.5">
        {NAV.map((group) => (
          <div key={group.section} className="flex flex-col gap-0.5">
            <div className="px-2.5 pt-3.5 pb-1.5 text-[11px] font-semibold tracking-[.06em] text-muted uppercase">
              {group.section}
            </div>
            {group.items.map((item) =>
              item.to ? (
                <NavLink
                  key={item.label}
                  to={item.to}
                  // "Payments" covers the list and every payment detail, but not "Create payment".
                  end={item.to === '/payments/new'}
                  className={({ isActive }) =>
                    `flex items-center gap-2.5 rounded-md px-2.5 py-[7px] font-medium no-underline ${
                      isActive && !(item.to === '/payments' && pathname === '/payments/new')
                        ? 'bg-accent-bg text-accent'
                        : 'text-muted hover:bg-surface2 hover:text-fg'
                    }`
                  }
                >
                  <item.icon />
                  {item.label}
                </NavLink>
              ) : (
                <span
                  key={item.label}
                  aria-disabled="true"
                  title={`Arrives with milestone ${item.milestone}`}
                  className="flex cursor-default items-center gap-2.5 rounded-md px-2.5 py-[7px] font-medium text-muted opacity-60"
                >
                  <item.icon />
                  {item.label}
                  <span className="mono ml-auto text-[10px]">{item.milestone}</span>
                </span>
              ),
            )}
          </div>
        ))}
      </nav>
      <div className="mt-auto flex flex-col gap-2.5 border-t border-line2 px-2.5 pt-3 pb-1">
        <span className="self-start">
          <Chip tone="warn">SIMULATION</Chip>
        </span>
      </div>
    </aside>
  )
}

function BankSwitcher() {
  const { bank, setBank } = useBank()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const info = useBankInfo()

  useEffect(() => {
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [])

  const dot = info.isError ? 'bg-err' : 'bg-ok'
  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label="Switch bank"
        className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-md border border-line bg-surface px-3 font-medium hover:bg-surface2"
      >
        <span className={`size-2 rounded-full ${dot}`} title={info.isError ? 'Backend unreachable' : 'Connected'} />
        <span className="font-semibold">{bank.name}</span>
        <span className="mono text-muted">{bank.bic}</span>
        <Icons.chevron />
      </button>
      {open && (
        <div className="absolute top-[38px] left-0 z-10 w-[280px] rounded-lg border border-line bg-surface p-1 shadow-[0_8px_24px_rgba(0,0,0,.14)]">
          {BANKS.map((b) => (
            <button
              key={b.id}
              type="button"
              onClick={() => {
                setBank(b)
                setOpen(false)
              }}
              className="flex w-full cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 text-left hover:bg-surface2"
            >
              <span className="size-2 rounded-full bg-ok" />
              <span className="flex flex-col leading-[1.3]">
                <span className="font-semibold">
                  {b.name} · {b.city}
                </span>
                <span className="mono text-muted">
                  {b.bic} · {b.role} · :{b.port}
                </span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** Bank-local wall clock + business date. Timestamps are UTC in the backend; display is local to the bank. */
function BankClock() {
  const info = useBankInfo()
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  if (!info.data) {
    return <span className="text-xs text-muted">{info.isError ? 'Backend not reachable' : 'Connecting…'}</span>
  }
  const tz = info.data.timeZone
  const time = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(now)
  const zone = new Intl.DateTimeFormat('en-GB', { timeZone: tz, timeZoneName: 'short' })
    .formatToParts(now)
    .find((p) => p.type === 'timeZoneName')?.value
  const businessDate = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(info.data.businessDate + 'T00:00:00Z'),
  )
  return (
    <span className="text-xs text-muted">
      <span className="mono text-fg">{time}</span> {zone} · Business date {businessDate}
    </span>
  )
}

function ThemeToggle() {
  const [theme, toggle] = useTheme()
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label="Toggle light or dark mode"
      className="inline-flex size-8 cursor-pointer items-center justify-center rounded-md border border-line bg-surface hover:bg-surface2"
    >
      {theme === 'dark' ? <Icons.sun /> : <Icons.moon />}
    </button>
  )
}

export function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-wrap bg-bg">
      <Sidebar />
      <div className="flex min-w-0 flex-[999_1_560px] flex-col">
        <header className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-6 py-2.5">
          <BankSwitcher />
          <div className="ml-auto flex items-center gap-3.5">
            <BankClock />
            <ThemeToggle />
          </div>
        </header>
        <main className="box-border flex w-full flex-col gap-4 p-6">{children}</main>
      </div>
    </div>
  )
}
