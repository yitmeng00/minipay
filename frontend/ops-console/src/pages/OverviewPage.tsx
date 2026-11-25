import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardHeader, Chip, type Tone } from '../components/ui'
import { useAccounts, useBankInfo, useCutoffs, useOverview } from '../lib/api'
import { useBank } from '../lib/banks'
import { REASON } from '../lib/iso'
import { formatAmount, formatMoney } from '../lib/money'
import type { Cutoff, DailyOverview, Money, PaymentStatus } from '../lib/types'

/** Re-render every `ms`, for "time left" and the hour in progress. */
function useNow(ms: number) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(t)
  }, [ms])
  return now
}

const sum = (o: DailyOverview | undefined, statuses: PaymentStatus[]) =>
  o ? statuses.reduce((n, s) => n + (o.byStatus[s] ?? 0), 0) : 0

function pct(part: number, whole: number) {
  return whole ? `${((part / whole) * 100).toFixed(1)}%` : '—'
}

/** Short human form of an amount string for a KPI tile (1,234,567.89 → 1.23M). Display only. */
function compact(amount: string) {
  const int = amount.split('.')[0].replace('-', '')
  if (int.length > 9) return `${int.slice(0, int.length - 9)}.${int.slice(int.length - 9, int.length - 7)}B`
  if (int.length > 6) return `${int.slice(0, int.length - 6)}.${int.slice(int.length - 6, int.length - 4)}M`
  if (int.length > 3) return `${int.slice(0, int.length - 3)}.${int.slice(int.length - 3, int.length - 2)}K`
  return formatAmount(amount)
}

function duration(ms: number) {
  const m = Math.max(0, Math.round(ms / 60_000))
  return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`
}

// ---------------------------------------------------------------- KPI tiles

function Kpi({ label, value, unit, tag, tone, note }: { label: string; value: ReactNode; unit?: string; tag: string; tone: Tone; note: ReactNode }) {
  return (
    <Card className="flex min-w-0 flex-col gap-1.5 px-4 py-3.5">
      <span className="lbl">{label}</span>
      <span className="text-2xl font-semibold tracking-[-0.02em] whitespace-nowrap tabular-nums">
        {value}
        {unit && <span className="ml-1 text-[13px] font-medium text-muted">{unit}</span>}
      </span>
      <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted">
        <Chip tone={tone}>{tag}</Chip>
        {note}
      </span>
    </Card>
  )
}

function Kpis({ o }: { o: DailyOverview }) {
  const sent = sum(o, ['SENT', 'ACKED', 'SETTLED', 'RETURNED'])
  const inFlight = sum(o, ['SENT', 'ACKED'])
  const rejected = o.byStatus.REJECTED ?? 0
  const returned = o.byStatus.RETURNED ?? 0
  // Currencies are not added up or ranked against each other: there is no FX conversion in the simulator.
  const [top, ...rest] = o.valueSent
  return (
    <section aria-label="Key figures" className="grid grid-cols-[repeat(auto-fit,minmax(170px,1fr))] gap-3">
      <Kpi label="Payments today" value={o.total} tag={`${sent} sent`} tone="ok" note={`${o.warehoused} warehoused for later`} />
      <Kpi
        label="Value sent"
        value={top ? compact(top.amount) : '0'}
        unit={top?.currency ?? ''}
        tag={rest.length ? `+${rest.length} ccy` : 'per currency'}
        tone="ok"
        note={rest.length ? rest.map((m) => `${m.currency} ${compact(m.amount)}`).join(' · ') : 'no FX conversion in the simulator'}
      />
      <Kpi
        label="In flight"
        value={inFlight}
        tag={`${o.stuckInSent} stuck`}
        tone={o.stuckInSent ? 'warn' : 'ok'}
        note={`SENT > ${o.stuckAfterMinutes} min without ACK`}
      />
      <Kpi
        label="Rejected"
        value={rejected}
        tag={pct(rejected, o.total)}
        tone={rejected ? 'err' : 'ok'}
        note={o.rejectedByReason[0] ? `top ${o.rejectedByReason[0].reasonCode} · ${returned} returned` : `${returned} returned`}
      />
      <Kpi label="Sanctions hits" value="—" tag="M6" tone="neutral" note="screening arrives with compliance" />
      <Kpi label="Recon breaks" value="—" tag="M8" tone="neutral" note="arrives with reconciliation" />
    </section>
  )
}

// ---------------------------------------------------------------- throughput

function niceMax(n: number) {
  if (n <= 4) return 4
  const pow = 10 ** Math.floor(Math.log10(n))
  const step = [1, 2, 2.5, 5, 10].map((s) => s * pow).find((s) => s * 4 >= n) ?? 10 * pow
  return step * 4
}

function Throughput({ o, now, tz }: { o: DailyOverview; now: number; tz: string }) {
  const hourNow = Number(new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', hourCycle: 'h23' }).format(now))
  const todayInZone = new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(now)
  const isToday = o.date === todayInZone
  const max = niceMax(Math.max(...o.perHour))
  const elapsed = isToday ? hourNow + 1 : 24
  const peak = o.perHour.reduce((best, v, i) => (v > o.perHour[best] ? i : best), 0)
  const mean = elapsed ? o.perHour.slice(0, elapsed).reduce((a, b) => a + b, 0) / elapsed : 0
  const avg = mean < 10 ? mean.toFixed(1) : Math.round(mean)
  const zone = new Intl.DateTimeFormat('en-GB', { timeZone: tz, timeZoneName: 'short' }).formatToParts(now).find((p) => p.type === 'timeZoneName')?.value
  return (
    <Card className="min-w-0 lg:col-span-2">
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Throughput — payments per hour</h2>
          <span className="text-xs text-muted">
            Created on {o.date} · {zone}
            {isToday ? ` · ${String(hourNow).padStart(2, '0')}:00 hour in progress` : ''}
          </span>
        </div>
      </CardHeader>
      <div className="flex gap-2 px-4 pt-4 pb-3">
        <div className="-mt-1.5 flex h-[200px] w-7 flex-col justify-between text-right text-[11px] text-muted" aria-hidden="true">
          {[4, 3, 2, 1, 0].map((i) => (
            <span key={i}>{(max / 4) * i}</span>
          ))}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div
            className="relative flex h-[188px] items-end gap-1 border-b border-line bg-[linear-gradient(var(--border2)_1px,transparent_1px)] bg-[length:100%_47px]"
            role="img"
            aria-label={`Bar chart of payments per hour, peaking at ${o.perHour[peak]} in the ${String(peak).padStart(2, '0')}:00 hour`}
          >
            {o.perHour.map((v, i) => {
              const future = isToday && i > hourNow
              const partial = isToday && i === hourNow
              return (
                <div
                  key={i}
                  className={`bar ${future ? 'future' : partial ? 'partial' : ''}`}
                  style={{ height: future ? 0 : Math.max(v ? 2 : 0, Math.round((v / max) * 188)) }}
                  title={`${String(i).padStart(2, '0')}:00–${String(i + 1).padStart(2, '0')}:00 · ${future ? 'not started' : `${v} payments${partial ? ' (in progress)' : ''}`}`}
                />
              )
            })}
          </div>
          <div className="flex gap-1 text-[10px] text-muted" aria-hidden="true">
            {o.perHour.map((_, i) => (
              <span key={i} className="min-w-0 flex-[1_1_0] text-center">
                {i % 2 === 0 ? String(i).padStart(2, '0') : ''}
              </span>
            ))}
          </div>
        </div>
      </div>
      <div className="flex flex-wrap gap-6 px-4 pb-3.5 pl-[52px] text-xs text-muted">
        <span>
          Peak <b className="font-semibold text-fg">{o.perHour[peak]}</b> at {String(peak).padStart(2, '0')}:00
        </span>
        <span>
          Avg <b className="font-semibold text-fg">{avg}</b> / h
        </span>
        <span>
          Total <b className="font-semibold text-fg">{o.total}</b>
        </span>
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- status breakdown

function StatusBreakdown({ o }: { o: DailyOverview }) {
  const settled = sum(o, ['SETTLED'])
  const progress = sum(o, ['RECEIVED', 'VALIDATED', 'SCREENED', 'SENT', 'ACKED'])
  const pending = sum(o, ['FUNDS_RESERVED', 'HELD_COMPLIANCE'])
  const failed = sum(o, ['REJECTED', 'RETURNED'])
  const rows: { label: string; tone: Tone; n: number; sub?: string; color: string }[] = [
    { label: 'Settled', tone: 'ok', n: settled, color: 'bg-ok' },
    {
      label: 'In progress',
      tone: 'info',
      n: progress,
      sub: `SENT awaiting ACK ${o.byStatus.SENT ?? 0} · ACKED ${o.byStatus.ACKED ?? 0}`,
      color: 'bg-info',
    },
    {
      label: 'Pending / held',
      tone: 'warn',
      n: pending,
      sub: `Funds reserved ${o.byStatus.FUNDS_RESERVED ?? 0} · compliance hold ${o.byStatus.HELD_COMPLIANCE ?? 0}`,
      color: 'bg-warn',
    },
    {
      label: 'Rejected / returned',
      tone: 'err',
      n: failed,
      sub: `RJCT ${o.byStatus.REJECTED ?? 0} · RTND ${o.byStatus.RETURNED ?? 0}${o.rejectedByReason[0] ? ` · top reason ${o.rejectedByReason[0].reasonCode}` : ''}`,
      color: 'bg-err',
    },
  ]
  return (
    <Card className="min-w-0">
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Status breakdown</h2>
          <span className="text-xs text-muted">{o.total} payments today</span>
        </div>
      </CardHeader>
      <div className="flex flex-col gap-3.5 p-4">
        <div className="flex h-3.5 gap-0.5" role="img" aria-label={rows.map((r) => `${r.label} ${pct(r.n, o.total)}`).join(', ')}>
          {o.total === 0 ? (
            <div className="flex-1 rounded bg-track" />
          ) : (
            rows
              .filter((r) => r.n > 0)
              .map((r) => (
                <div
                  key={r.label}
                  className={`${r.color} min-w-1.5 first:rounded-l last:rounded-r`}
                  style={{ flex: `${r.n} 1 0` }}
                  title={`${r.label} · ${r.n}`}
                />
              ))
          )}
        </div>
        <table className="tbl -mx-4 !w-[calc(100%+32px)]">
          <tbody>
            {rows.map((r) => (
              <tr key={r.label}>
                <td className="!whitespace-normal">
                  <Chip tone={r.tone}>{r.label}</Chip>
                  {r.sub && <div className="mt-[3px] text-[11px] text-muted">{r.sub}</div>}
                </td>
                <td className="num">{r.n}</td>
                <td className="num text-muted">{pct(r.n, o.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- liquidity

function Liquidity() {
  const nostros = (useAccounts('NOSTRO').data ?? []).filter((a) => a.status === 'ACTIVE')
  return (
    <Card className="min-w-0 lg:col-span-2">
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Liquidity position by currency</h2>
          <span className="text-xs text-muted">Nostro and settlement accounts · minimum balances and funding alerts arrive with M7</span>
        </div>
        <Link to="/accounts" className="text-xs font-medium text-accent no-underline">
          Accounts &amp; ledger →
        </Link>
      </CardHeader>
      <div className="overflow-x-auto">
        <table className="tbl">
          <thead>
            <tr>
              <th>Ccy</th>
              <th>Account</th>
              <th className="!text-right">Booked</th>
              <th className="!text-right">Reserved</th>
              <th className="!text-right">Available</th>
            </tr>
          </thead>
          <tbody>
            {[...nostros]
              .sort((a, b) => a.currency.localeCompare(b.currency))
              .map((a) => (
                <tr key={a.id}>
                  <td className="mono font-medium">{a.currency}</td>
                  <td>
                    <div className="font-medium">{a.ownerName}</div>
                    <div className="mono text-[11px] text-muted">
                      {a.counterpartyBic} · {a.accountNumber}
                    </div>
                  </td>
                  <td className="num mono">{formatAmount(a.booked.amount)}</td>
                  <td className="num mono text-muted">{formatAmount(a.reserved.amount)}</td>
                  <td className="num mono font-medium">{formatAmount(a.available.amount)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- alerts (derived from real figures, nothing invented)

type Alert = { title: string; sev: 'Warning' | 'Info'; body: string; action?: { to: string; label: string } }

function alertsFrom(o: DailyOverview, cutoffs: Cutoff[], currencies: Set<string>, now: number, tz: string): Alert[] {
  const out: Alert[] = []
  if (o.stuckInSent) {
    out.push({
      title: `${o.stuckInSent} payment${o.stuckInSent > 1 ? 's' : ''} stuck in SENT`,
      sev: 'Warning',
      body: `No acknowledgement from the next bank after ${o.stuckAfterMinutes} min. Chase them, or wait for the network (M5).`,
      action: { to: '/payments?tab=progress', label: 'View payments' },
    })
  }
  for (const c of cutoffs.filter((c) => currencies.has(c.currency))) {
    const q = o.queued.find((x) => x.currency === c.currency)
    const left = new Date(c.cutoff).getTime() - now
    const closesToday = c.settlementDate === new Intl.DateTimeFormat('en-CA', { timeZone: c.timeZone }).format(now)
    if (q && q.count && closesToday && left < 3 * 3600_000) {
      out.push({
        title: `${c.schemeName} (${c.currency}) cut-off in ${duration(left)}`,
        sev: 'Warning',
        body: `${q.count} ${c.currency} payment${q.count > 1 ? 's' : ''} (${formatMoney(q.amount, true)}) due and not yet sent. After the cut-off they settle on the next business day.`,
      })
    }
    if (!c.openToday) {
      out.push({
        title: `${c.schemeName} closed today`,
        sev: 'Info',
        body: `${c.holidayToday ?? 'Not a business day'} — ${c.currency} payments settle on ${c.settlementDate}.`,
      })
    }
  }
  if (o.rejectedByReason.length) {
    const r = o.rejectedByReason[0]
    out.push({
      title: `${o.byStatus.REJECTED} payment${o.byStatus.REJECTED > 1 ? 's' : ''} rejected today`,
      sev: 'Info',
      body: `Most often ${r.reasonCode}${REASON[r.reasonCode] ? ` (${REASON[r.reasonCode]})` : ''}, ${r.count}×. ${o.rejectedByReason
        .slice(1, 4)
        .map((x) => `${x.reasonCode} ${x.count}×`)
        .join(' · ')}`,
      action: { to: '/payments?tab=rejected', label: 'Review' },
    })
  }
  if (o.warehoused) {
    out.push({
      title: `${o.warehoused} warehoused payment${o.warehoused > 1 ? 's' : ''}`,
      sev: 'Info',
      body: `Funds reserved, waiting for a later execution date (${tz}).`,
      action: { to: '/payments?tab=pending', label: 'Open queue' },
    })
  }
  return out
}

function Alerts({ alerts }: { alerts: Alert[] }) {
  return (
    <Card className="flex min-w-0 flex-col">
      <CardHeader>
        <h2 className="m-0 text-sm font-semibold">
          Alerts <span className="font-medium text-muted">· {alerts.length} open</span>
        </h2>
        <Link to="/payments" className="text-xs font-medium text-accent no-underline">
          All payments →
        </Link>
      </CardHeader>
      {alerts.length === 0 ? (
        <div className="p-6 text-center text-muted">Nothing needs attention.</div>
      ) : (
        <ul className="m-0 list-none p-0">
          {alerts.map((a) => (
            <li key={a.title} className="flex gap-2.5 border-b border-line2 px-4 py-[11px] last:border-b-0">
              <span className={`w-[3px] flex-[0_0_3px] rounded-sm ${a.sev === 'Warning' ? 'bg-warn' : 'bg-info'}`} aria-hidden="true" />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold">{a.title}</span>
                  <Chip tone={a.sev === 'Warning' ? 'warn' : 'info'} small>
                    {a.sev}
                  </Chip>
                </div>
                <span className="text-xs text-muted">{a.body}</span>
                {a.action && (
                  <Link to={a.action.to} className="mt-0.5 self-end text-[11px] font-medium text-accent no-underline">
                    {a.action.label}
                  </Link>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

// ---------------------------------------------------------------- cut-offs

function Cutoffs({ cutoffs, o, now, tz }: { cutoffs: Cutoff[]; o: DailyOverview; now: number; tz: string }) {
  const zone = new Intl.DateTimeFormat('en-GB', { timeZone: tz, timeZoneName: 'short' }).formatToParts(now).find((p) => p.type === 'timeZoneName')?.value
  const bankToday = new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(now)
  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Cut-off times</h2>
          <span className="text-xs text-muted">Local {zone} · customer payments · queue = due and not yet sent</span>
        </div>
      </CardHeader>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))]">
        {cutoffs.map((c) => {
          const at = new Date(c.cutoff)
          const left = at.getTime() - now
          // Progress through the scheme's settlement day, from local midnight to the cut-off.
          const dayStart = at.getTime() - (Number(c.cutoffTime.slice(0, 2)) * 60 + Number(c.cutoffTime.slice(3, 5))) * 60_000
          const share = Math.min(100, Math.max(0, ((now - dayStart) / (at.getTime() - dayStart)) * 100))
          const tone: Tone = !c.openToday ? 'neutral' : left < 2 * 3600_000 ? 'warn' : left < 12 * 3600_000 ? 'info' : 'ok'
          const bar = { ok: 'bg-ok', info: 'bg-info', warn: 'bg-warn', err: 'bg-err', neutral: 'bg-muted' }[tone]
          const localTime = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit' }).format(at)
          const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(at)
          const plus = Math.round((Date.parse(localDate) - Date.parse(bankToday)) / 86_400_000)
          const q = o.queued.find((x) => x.currency === c.currency)
          return (
            <div key={c.currency} className="flex flex-col gap-1.5 border-r border-line2 px-4 py-3.5">
              <div className="flex items-center justify-between">
                <span className="font-semibold">
                  {c.schemeName} <span className="mono font-normal text-muted">{c.currency}</span>
                </span>
                <Chip tone={tone}>{c.openToday ? duration(left) : `Closed · ${c.holidayToday}`}</Chip>
              </div>
              <div className="h-1.5 rounded-[3px] bg-track">
                <div className={`h-1.5 rounded-[3px] ${bar}`} style={{ width: `${c.openToday ? share : 0}%` }} />
              </div>
              <div className="flex justify-between text-xs text-muted">
                <span>
                  Closes{' '}
                  <span className="mono text-fg">
                    {localTime}
                    {plus > 0 ? ` +${plus}` : ''}
                  </span>
                  {c.settlementDate !== bankToday && <span> · for {c.settlementDate}</span>}
                </span>
                <span>{q ? `${q.count} queued` : '0 queued'}</span>
              </div>
            </div>
          )
        })}
      </div>
    </Card>
  )
}

export function OverviewPage() {
  const { bank } = useBank()
  const info = useBankInfo().data
  const overview = useOverview().data
  const cutoffs = useCutoffs().data ?? []
  const accounts = useAccounts().data ?? []
  const now = useNow(30_000)
  if (!overview || !info) return <div className="text-muted">Loading overview…</div>

  // Cut-offs that matter to this bank: currencies it actually holds accounts in.
  const currencies = new Set(accounts.map((a) => a.currency))
  const relevant = cutoffs.filter((c) => currencies.has(c.currency))
  const alerts = alertsFrom(overview, relevant, currencies, now, info.timeZone)
  const queuedValue: Money[] = overview.queued.map((q) => q.amount)

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Overview</h1>
          <p className="mt-0.5 mb-0 text-muted">
            {bank.name} · <span className="mono">{bank.bic}</span> · business date {overview.date} · values per currency
            {queuedValue.length > 0 && <> · queued {queuedValue.map((m) => formatMoney(m, true)).join(', ')}</>}
          </p>
        </div>
      </div>
      <Kpis o={overview} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Throughput o={overview} now={now} tz={info.timeZone} />
        <StatusBreakdown o={overview} />
        <Liquidity />
        <Alerts alerts={alerts} />
      </div>
      <Cutoffs cutoffs={relevant} o={overview} now={now} tz={info.timeZone} />
    </>
  )
}
