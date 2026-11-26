import { useEffect, useMemo, useState } from 'react'
import { Button, Card, CardHeader, Chip, Segmented, type Tone } from '../components/ui'
import {
  ApiError,
  newIdempotencyKey,
  useBankInfo,
  useChangePriority,
  useClearingParticipants,
  useCloseCycle,
  useDnsCycles,
  useInstant,
  useMoveUp,
  useRtgs,
} from '../lib/api'
import { useBank } from '../lib/banks'
import { reasonText } from '../lib/iso'
import { formatAmount, formatMoney, isNegative } from '../lib/money'
import type { ClearingParticipant, DnsCycle, Money, Transfer } from '../lib/types'

/**
 * Settlement (M7, ADR-008): how this bank's obligations become final, in the three systems the simulated
 * central bank runs. The data is the operator's (as a bank sees T2 through T2's own screens), asked for by
 * this bank's BIC.
 */

type Mode = 'rtgs' | 'dns' | 'instant'

const MODES: { value: Mode; label: string; sub: string }[] = [
  { value: 'rtgs', label: 'RTGS', sub: 'real-time gross' },
  { value: 'dns', label: 'Deferred net', sub: 'batch netting' },
  { value: 'instant', label: 'Instant', sub: 'live feed' },
]

const bic8 = (b: string) => b.slice(0, 8)

function time(iso: string | null | undefined, tz: string, seconds = false) {
  if (!iso) return ''
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}),
  }).format(new Date(iso))
}

function zoneAbbrev(tz: string) {
  const part = new Intl.DateTimeFormat('en-GB', { timeZone: tz, timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((p) => p.type === 'timeZoneName')
  return part?.value ?? tz
}

function useTick(ms: number, paused = false) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (paused) return
    const t = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(t)
  }, [ms, paused])
  return now
}

/** Amounts inside server text ("needs EUR 2400000.00") with thousands separators, as everywhere else. */
const grouped = (text: string | null) => (text ?? '').replace(/\d+\.\d{2}\b/g, (a) => formatAmount(a))

/** Money as a plain number for charts and bar widths only (never for amounts shown or added up). */
const n = (m: Money | null | undefined) => (m ? Number(m.amount) : 0)

function Tile({ k, v, note, tone }: { k: string; v: string; note?: string; tone?: 'err' | 'ok' }) {
  return (
    <div className={`card flex flex-col gap-1 px-4 py-3 ${tone === 'err' ? '!border-err' : ''}`}>
      <span className="lbl">{k}</span>
      <span
        className="mono text-[17px] font-semibold"
        style={{ color: tone === 'err' ? 'var(--err-fg)' : tone === 'ok' ? 'var(--ok-fg)' : 'var(--fg)' }}
      >
        {v}
      </span>
      {note && <span className="hint">{note}</span>}
    </div>
  )
}

function NotParticipant({ bankName }: { bankName: string }) {
  return (
    <Card className="px-4 py-4">
      <div className="flex flex-col gap-1">
        <span className="font-semibold">{bankName} has no account at the euro-area central bank</span>
        <span className="text-muted">
          A bank in Kuala Lumpur is not a T2 or MiniPay Instant participant. Its EUR payments go to Bank B, its correspondent,
          which settles them onwards in T2 or MiniPay Net: indirect participation. Switch to Bank B or Bank C to see their
          accounts and queues.
        </span>
      </div>
    </Card>
  )
}

export function SettlementPage() {
  const { bank } = useBank()
  const info = useBankInfo()
  const [mode, setMode] = useState<Mode>('rtgs')
  const participants = useClearingParticipants()
  const me = participants.data?.find((p) => bic8(p.bic) === bic8(bank.bic))
  const names = useMemo(() => {
    const m = new Map<string, string>()
    participants.data?.forEach((p) => m.set(bic8(p.bic), p.name))
    return m
  }, [participants.data])
  const nameOf = (b: string) => names.get(bic8(b)) ?? ''
  const tz = info.data?.timeZone ?? 'UTC'

  return (
    <main className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Settlement</h1>
          <p className="mt-0.5 mb-0 text-muted">
            How {bank.name}’s obligations become final: three settlement models side by side, run by the central bank (MNCBDEFFXXX)
          </p>
        </div>
        <div className="seg h-9" role="tablist" aria-label="Settlement model">
          {MODES.map((m) => (
            <button
              key={m.value}
              role="tab"
              aria-selected={mode === m.value}
              className={mode === m.value ? 'on' : ''}
              onClick={() => setMode(m.value)}
              style={{ height: 34, padding: '0 16px', display: 'inline-flex', gap: 8, alignItems: 'center' }}
            >
              <span className="font-semibold">{m.label}</span>
              <span className="text-[11px] opacity-80">{m.sub}</span>
            </button>
          ))}
        </div>
      </div>

      {participants.isError ? (
        <Card className="px-4 py-4">
          <span className="font-semibold">The central bank is not running.</span>{' '}
          <span className="text-muted">
            Start it with the operator profile (port 8084, its database on 5436):{' '}
            <code className="mono">./mvnw -pl bank-app spring-boot:run -Dspring-boot.run.profiles=operator</code>
          </span>
        </Card>
      ) : mode === 'dns' ? (
        <DnsPanel me={me} nameOf={nameOf} tz={tz} />
      ) : !participants.data ? (
        <Card className="px-4 py-4 text-muted">Loading…</Card>
      ) : !me ? (
        <NotParticipant bankName={bank.name} />
      ) : mode === 'rtgs' ? (
        <RtgsPanel me={me} nameOf={nameOf} tz={tz} />
      ) : (
        <InstantPanel me={me} nameOf={nameOf} tz={tz} />
      )}
    </main>
  )
}

// ---------------------------------------------------------------- RTGS

function RtgsPanel({ me, nameOf, tz }: { me: ClearingParticipant; nameOf: (b: string) => string; tz: string }) {
  const rtgs = useRtgs(me.bic, true)
  const moveUp = useMoveUp()
  const priority = useChangePriority()
  const [error, setError] = useState<string | null>(null)
  const now = useTick(30_000)
  const r = rtgs.data
  if (!r) return <Card className="px-4 py-4 text-muted">{rtgs.isError ? 'Cannot read the RTGS view.' : 'Loading…'}</Card>

  const ccy = r.available.currency
  const cutoffIn = Math.round((new Date(r.cutoffAt).getTime() - now) / 60_000)
  const act = (p: Promise<unknown>) => p.then(() => setError(null)).catch((e) => setError(e instanceof ApiError ? e.message : String(e)))

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Segmented label="RTGS system" options={[{ value: 'T2', label: `${r.systemName} · ${ccy}` }]} value="T2" onChange={() => {}} />
        <span className="text-xs text-muted">
          Direct participant · account {me.rtgsAccount} · cut-off {r.cutoff.slice(0, 5)} {r.timeZone} ({time(r.cutoffAt, tz)} {zoneAbbrev(tz)}) ·
          business date {r.businessDate}
        </span>
      </div>

      <section aria-label="Liquidity" className="grid grid-cols-5 gap-3">
        <Tile k={`Available on ${r.systemName}`} v={formatMoney(r.available)} note="what queued payments can use" />
        <Tile k="Queued outgoing" v={formatMoney(r.queuedOut)} note={`${r.queuedOutCount} payment${r.queuedOutCount === 1 ? '' : 's'}`} />
        <Tile k="Expected incoming" v={formatMoney(r.queuedIn)} note={`queued at other participants (${r.queuedInCount})`} />
        <Tile
          k="Projected end of day"
          v={formatMoney(r.projected)}
          note={isNegative(r.projected) ? 'shortfall · fund before the cut-off' : 'if everything queued settles'}
          tone={isNegative(r.projected) ? 'err' : undefined}
        />
        <Tile
          k="Cut-off"
          v={cutoffIn > 0 ? `${Math.floor(cutoffIn / 60)}h ${String(cutoffIn % 60).padStart(2, '0')}m` : 'passed'}
          note="queued then: rejected AM04"
          tone={cutoffIn <= 0 ? 'err' : undefined}
        />
      </section>

      <div className="grid grid-cols-5 gap-4">
        <Card className="col-span-2 min-w-0">
          <CardHeader>
            <div>
              <h2 className="card-t">Intraday balance</h2>
              <span className="text-xs text-muted">{ccy} · after each booking today ({r.timeZone})</span>
            </div>
          </CardHeader>
          <div className="px-4 py-3">
            <BalanceChart points={r.intraday} date={r.businessDate} zone={r.timeZone} now={now} />
          </div>
        </Card>

        <Card className="col-span-3 min-w-0 overflow-hidden">
          <CardHeader>
            <div>
              <h2 className="card-t">Payment queue</h2>
              <span className="text-xs text-muted">
                Urgent first, then queue order · urgent payments are FIFO, normal ones may be overtaken by smaller ones
              </span>
            </div>
          </CardHeader>
          {error && <div className="border-b border-line2 bg-err-bg px-4 py-2 text-err-fg">{error}</div>}
          {r.queue.length === 0 ? (
            <div className="px-4 py-6 text-muted">Nothing queued: every payment settled the moment it arrived.</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="tbl dense">
                <thead>
                  <tr>
                    <th className="!text-right">#</th>
                    <th>Reference</th>
                    <th>Counterparty</th>
                    <th className="!text-right">Amount</th>
                    <th>Priority</th>
                    <th>Queued</th>
                    <th>Why it waits</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>
                <tbody>
                  {r.queue.map((q, i) => (
                    <tr key={q.id}>
                      <td className="num mono text-muted">{i + 1}</td>
                      <td className="mono" title={q.msgId}>
                        …{q.msgId.slice(-10)}
                      </td>
                      <td>
                        <div className="mono">{q.payeeBic}</div>
                        <div className="text-[11px] text-muted">{nameOf(q.payeeBic)}</div>
                      </td>
                      <td className="num mono font-medium">{formatMoney(q.amount)}</td>
                      <td>
                        <Chip small tone={q.priority === 'HIGH' ? 'warn' : 'neutral'}>
                          {q.priority === 'HIGH' ? 'Urgent' : 'Normal'}
                        </Chip>
                      </td>
                      <td className="mono text-muted">{time(q.receivedAt, tz)}</td>
                      <td className="min-w-[180px] text-xs !whitespace-normal">{grouped(q.waiting)}</td>
                      <td className="whitespace-nowrap">
                        <Button
                          className="!h-[26px] !px-2"
                          aria-label="Move up"
                          title="Move up the queue"
                          disabled={moveUp.isPending}
                          onClick={() => act(moveUp.mutateAsync({ id: q.id, participant: me.bic, key: newIdempotencyKey('up') }))}
                        >
                          ↑
                        </Button>{' '}
                        <Button
                          className="!h-[26px] !px-2"
                          disabled={priority.isPending}
                          onClick={() =>
                            act(
                              priority.mutateAsync({
                                id: q.id,
                                participant: me.bic,
                                priority: q.priority === 'HIGH' ? 'NORM' : 'HIGH',
                                key: newIdempotencyKey('prio'),
                              }),
                            )
                          }
                        >
                          {q.priority === 'HIGH' ? 'Make normal' : 'Make urgent'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Card className="overflow-hidden">
        <CardHeader>
          <div>
            <h2 className="card-t">Settled and rejected today</h2>
            <span className="text-xs text-muted">final: settled in central bank money, or rejected with nothing moved</span>
          </div>
        </CardHeader>
        <TransferTable rows={r.recent} me={me.bic} nameOf={nameOf} tz={tz} empty="No T2 payments today yet." />
      </Card>
    </div>
  )
}

function BalanceChart({ points, date, zone, now }: { points: { at: string; balance: Money }[]; date: string; zone: string; now: number }) {
  const X0 = 46, X1 = 400, Y0 = 8, Y1 = 180
  const start = useMemo(() => {
    // Midnight of the business date in the system's zone, as an epoch: the chart's x axis is that day's 24 hours.
    const guess = new Date(`${date}T00:00:00Z`).getTime()
    const offset = (iso: number) => {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
        .formatToParts(new Date(iso))
        .reduce<Record<string, string>>((a, p) => ({ ...a, [p.type]: p.value }), {})
      const asUtc = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour % 24, +parts.minute)
      return asUtc - iso
    }
    return guess - offset(guess)
  }, [date, zone])
  const series = [...points.map((p) => ({ t: new Date(p.at).getTime(), v: n(p.balance) })), { t: Math.min(now, start + 864e5), v: n(points[points.length - 1]?.balance) }]
  const vs = series.map((s) => s.v)
  let lo = Math.min(...vs, 0), hi = Math.max(...vs)
  if (hi === lo) hi = lo + 1
  const pad = (hi - lo) * 0.1
  lo = Math.max(0, lo - pad)
  hi += pad
  const x = (t: number) => X0 + ((X1 - X0) * Math.min(Math.max(t - start, 0), 864e5)) / 864e5
  const y = (v: number) => Y1 - ((Y1 - Y0) * (v - lo)) / (hi - lo)
  // A balance holds until the next booking: a step line.
  let d = ''
  series.forEach((s, i) => {
    d += i === 0 ? `M${x(s.t).toFixed(1)},${y(s.v).toFixed(1)}` : ` H${x(s.t).toFixed(1)} V${y(s.v).toFixed(1)}`
  })
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => lo + (hi - lo) * f)
  const last = series[series.length - 1]
  const short = (v: number) => (Math.abs(v) >= 1e6 ? `${(v / 1e6).toFixed(1)}M` : Math.abs(v) >= 1e3 ? `${(v / 1e3).toFixed(0)}K` : v.toFixed(0))
  return (
    <svg viewBox="0 0 400 200" width="100%" role="img" aria-label={`Balance today, now ${last ? formatAmount(last.v.toFixed(2)) : ''}`} style={{ display: 'block', overflow: 'visible' }}>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={X0} x2={X1} y1={y(t)} y2={y(t)} stroke="var(--border2)" strokeWidth={1} />
          <text x={X0 - 6} y={y(t) + 3} textAnchor="end" fontSize={10} fill="var(--muted)">
            {short(t)}
          </text>
        </g>
      ))}
      {[0, 6, 12, 18, 24].map((h) => (
        <text key={h} x={X0 + ((X1 - X0) * h) / 24} y={196} textAnchor="middle" fontSize={10} fill="var(--muted)">
          {String(h).padStart(2, '0')}:00
        </text>
      ))}
      <path d={d} fill="none" stroke="var(--accent)" strokeWidth={2} strokeLinejoin="round" />
      {last && <circle cx={x(last.t)} cy={y(last.v)} r={4} fill="var(--accent)" stroke="var(--surface)" strokeWidth={2} />}
    </svg>
  )
}

const STATUS: Record<Transfer['status'], { label: string; tone: Tone }> = {
  QUEUED: { label: 'QUEUED', tone: 'warn' },
  ACCEPTED: { label: 'IN CYCLE', tone: 'info' },
  PENDING: { label: 'PENDING', tone: 'info' },
  SETTLED: { label: 'SETTLED', tone: 'ok' },
  REJECTED: { label: 'RJCT', tone: 'err' },
}

function TransferTable({ rows, me, nameOf, tz, empty }: { rows: Transfer[]; me: string; nameOf: (b: string) => string; tz: string; empty: string }) {
  if (rows.length === 0) return <div className="px-4 py-6 text-muted">{empty}</div>
  return (
    <div className="overflow-x-auto">
      <table className="tbl dense">
        <thead>
          <tr>
            <th>Received</th>
            <th>Message</th>
            <th>Direction</th>
            <th>Counterparty</th>
            <th className="!text-right">Amount</th>
            <th>Settled</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => {
            const out = bic8(t.payerBic) === bic8(me)
            const other = out ? t.payeeBic : t.payerBic
            return (
              <tr key={t.id}>
                <td className="mono text-muted">{time(t.receivedAt, tz, true)}</td>
                <td>
                  <div className="mono">{t.msgId}</div>
                  <div className="text-[11px] text-muted">
                    {t.messageType}
                    {t.priority === 'HIGH' ? ' · urgent' : ''}
                  </div>
                </td>
                <td>
                  <span className={`dir ${out ? 'out' : 'in'}`}>{out ? '↗' : '↙'}</span>
                </td>
                <td>
                  <div className="mono">{other}</div>
                  <div className="text-[11px] text-muted">{nameOf(other)}</div>
                </td>
                <td className="num mono font-medium">{formatMoney(t.amount)}</td>
                <td className="mono text-muted">{time(t.settledAt, tz, true)}</td>
                <td title={t.reasonDetail ?? undefined}>
                  <Chip small tone={STATUS[t.status].tone}>
                    {STATUS[t.status].label}
                    {t.reasonCode ? ` ${t.reasonCode}` : ''}
                  </Chip>
                  {t.reasonCode && <div className="mt-0.5 text-[11px] text-muted">{reasonText(t.reasonCode)}</div>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ---------------------------------------------------------------- DNS

function DnsPanel({ me, nameOf, tz }: { me: ClearingParticipant | undefined; nameOf: (b: string) => string; tz: string }) {
  const cycles = useDnsCycles()
  const [selected, setSelected] = useState<string | null>(null)
  const [view, setView] = useState<'gross' | 'bilateral'>('gross')
  const close = useCloseCycle()
  const [error, setError] = useState<string | null>(null)
  const list = cycles.data ?? []
  const c = list.find((x) => x.id === selected) ?? list[0]
  // One key per cycle: a double click on "Close now" settles it once.
  const closeKey = useMemo(() => (c ? newIdempotencyKey(`close-${c.id}`) : ''), [c?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  if (cycles.isError) return <Card className="px-4 py-4 text-muted">Cannot read MiniPay Net.</Card>
  if (!cycles.data) return <Card className="px-4 py-4 text-muted">Loading…</Card>
  if (!c) {
    return (
      <Card className="px-4 py-4">
        <span className="font-semibold">No MiniPay Net cycle today yet.</span>{' '}
        <span className="text-muted">
          The first normal EUR payment routed through the ACH opens one; it settles at the next cycle time (09:00, 12:00, 15:00,
          17:00 Frankfurt), or when it is closed early.
        </span>
      </Card>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <Card className="flex flex-wrap items-center gap-6 px-4 py-3.5">
        <Field k="Scheme" v={`${c.systemName} · EUR`} />
        <div className="flex flex-col gap-0.5">
          <span className="lbl">Cycle</span>
          {list.length > 1 ? (
            <select className="sel mono !h-7" value={c.id} onChange={(e) => setSelected(e.target.value)} aria-label="Cycle">
              {list.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.id} · {x.status === 'OPEN' ? 'open' : 'settled'}
                </option>
              ))}
            </select>
          ) : (
            <span className="mono">{c.id}</span>
          )}
        </div>
        <Field k={c.status === 'OPEN' ? 'Closes' : 'Settled'} v={`${time(c.status === 'OPEN' ? c.scheduledClose : c.settledAt, tz)} ${zoneAbbrev(tz)}`} mono />
        <Field k="Settles" v="net positions on T2" />
        <Field k="Payments" v={String(c.payments)} mono />
        {c.rejected > 0 && <Field k="Unwound" v={String(c.rejected)} mono />}
        <span className="ml-auto flex items-center gap-2">
          {c.status === 'OPEN' ? (
            <>
              <Chip tone="info">Collecting · nothing has moved yet</Chip>
              <Button
                primary
                disabled={close.isPending}
                onClick={() =>
                  close
                    .mutateAsync({ key: closeKey })
                    .then(() => setError(null))
                    .catch((e) => setError(e instanceof ApiError ? e.message : String(e)))
                }
              >
                Close and settle now
              </Button>
            </>
          ) : c.unwound.length > 0 ? (
            <Chip tone="warn">Settled after unwinding {c.unwound.join(', ')}</Chip>
          ) : (
            <Chip tone="ok">Settled · final</Chip>
          )}
        </span>
      </Card>
      {error && <Card className="bg-err-bg px-4 py-2 text-err-fg">{error}</Card>}

      <div className="flex flex-wrap items-start gap-4">
        <Card className="min-w-0 flex-[999_1_640px] overflow-hidden">
          <CardHeader>
            <div>
              <h2 className="card-t">Obligations matrix</h2>
              <span className="text-xs text-muted">Row pays column · EUR · hover a cell for the payer and payee</span>
            </div>
            <Segmented<'gross' | 'bilateral'>
              label="Matrix view"
              options={[
                { value: 'gross', label: 'Gross' },
                { value: 'bilateral', label: 'Bilateral net' },
              ]}
              value={view}
              onChange={setView}
            />
          </CardHeader>
          <Matrix cycle={c} view={view} me={me?.bic} nameOf={nameOf} />
        </Card>
        <div className="flex min-w-0 flex-[1_1_360px] flex-col gap-4">
          <NetPositions cycle={c} me={me?.bic} nameOf={nameOf} />
          <Efficiency cycle={c} />
          <Instructions cycle={c} nameOf={nameOf} />
        </div>
      </div>
    </div>
  )
}

function Field({ k, v, mono }: { k: string; v: string; mono?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="lbl">{k}</span>
      <span className={mono ? 'mono' : 'font-semibold'}>{v}</span>
    </div>
  )
}

const label = (bic: string, nameOf: (b: string) => string) => nameOf(bic).split(' · ')[0] || bic

/** Cents arithmetic on decimal strings, so totals are exact (amounts here have 2 decimals). */
const cents = (m: Money) => BigInt(m.amount.replace('.', '').replace(/^(-?)0+(?=\d)/, '$1'))
const fromCents = (c: bigint) => {
  const neg = c < 0n
  const s = (neg ? -c : c).toString().padStart(3, '0')
  return (neg ? '-' : '') + s.slice(0, -2) + '.' + s.slice(-2)
}

function Matrix({ cycle, view, me, nameOf }: { cycle: DnsCycle; view: 'gross' | 'bilateral'; me?: string; nameOf: (b: string) => string }) {
  const banks = cycle.participants
  const g = cycle.gross.map((r) => r.cells.map(cents))
  const m = view === 'gross' ? g : g.map((row, i) => row.map((v, j) => (v - g[j][i] > 0n ? v - g[j][i] : 0n)))
  const max = m.flat().reduce((a, b) => (b > a ? b : a), 1n)
  const pays = m.map((row) => row.reduce((a, b) => a + b, 0n))
  const receives = banks.map((_, j) => m.reduce((a, row) => a + row[j], 0n))
  const net = banks.map((_, i) => g.reduce((a, row) => a + row[i], 0n) - g[i].reduce((a, b) => a + b, 0n))
  return (
    <div className="overflow-x-auto px-2 pt-2 pb-3">
      <table className="tbl" style={{ textAlign: 'right' }}>
        <thead>
          <tr>
            <th className="!text-left">Payer ↓ · Payee →</th>
            {banks.map((b) => (
              <th key={b} className="!text-right">
                <div className="text-xs tracking-normal text-fg normal-case">{label(b, nameOf)}</div>
                <div className="mono font-normal normal-case">{b}</div>
              </th>
            ))}
            <th className="!text-right" style={{ borderLeft: '1px solid var(--border)' }}>
              Pays
            </th>
          </tr>
        </thead>
        <tbody>
          {banks.map((payer, i) => (
            <tr key={payer} style={me && bic8(me) === bic8(payer) ? { outline: '1px solid var(--accent)' } : undefined}>
              <td className="!text-left">
                <span className="font-semibold">{label(payer, nameOf)}</span> <span className="mono text-muted">{payer}</span>
              </td>
              {banks.map((payee, j) =>
                i === j ? (
                  <td key={payee} className="num mono" style={{ background: 'var(--surface2)', color: 'var(--muted)' }}>
                    —
                  </td>
                ) : (
                  <td
                    key={payee}
                    className="num mono"
                    title={`${label(payer, nameOf)} → ${label(payee, nameOf)}: EUR ${formatAmount(fromCents(m[i][j]))}`}
                    style={{
                      background: m[i][j] > 0n ? `color-mix(in srgb, var(--accent) ${Math.round(8 + (40 * Number(m[i][j])) / Number(max))}%, transparent)` : 'transparent',
                    }}
                  >
                    {m[i][j] > 0n ? formatAmount(fromCents(m[i][j])) : '·'}
                  </td>
                ),
              )}
              <td className="num mono font-semibold" style={{ borderLeft: '1px solid var(--border)' }}>
                {formatAmount(fromCents(pays[i]))}
              </td>
            </tr>
          ))}
          <tr>
            <td className="!text-left font-semibold" style={{ borderTop: '1px solid var(--border)' }}>
              Receives
            </td>
            {receives.map((v, j) => (
              <td key={j} className="num mono font-semibold" style={{ borderTop: '1px solid var(--border)' }}>
                {formatAmount(fromCents(v))}
              </td>
            ))}
            <td className="num mono font-semibold" style={{ borderTop: '1px solid var(--border)', borderLeft: '1px solid var(--border)' }}>
              {formatAmount(fromCents(pays.reduce((a, b) => a + b, 0n)))}
            </td>
          </tr>
          <tr>
            <td className="!text-left font-semibold">Multilateral net</td>
            {net.map((v, j) => (
              <td key={j} className="num mono font-semibold" style={{ color: v > 0n ? 'var(--ok-fg)' : v < 0n ? 'var(--err-fg)' : 'var(--muted)' }}>
                {v > 0n ? '+' : ''}
                {formatAmount(fromCents(v))}
              </td>
            ))}
            <td className="num mono text-muted" style={{ borderLeft: '1px solid var(--border)' }}>
              Σ 0.00
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}

function NetPositions({ cycle, me, nameOf }: { cycle: DnsCycle; me?: string; nameOf: (b: string) => string }) {
  const max = Math.max(1, ...cycle.positions.map((p) => Math.abs(n(p.net))))
  return (
    <Card>
      <CardHeader>
        <h2 className="card-t">Net positions</h2>
        <span className="text-xs text-muted">receives − pays</span>
      </CardHeader>
      <div className="flex flex-col gap-2.5 px-4 py-3.5">
        {cycle.positions.map((p) => {
          const v = n(p.net)
          return (
            <div key={p.bic} className="grid items-center text-xs" style={{ gridTemplateColumns: '64px minmax(0,1fr) minmax(0,1fr) 96px' }}>
              <span className={`font-semibold ${me && bic8(me) === bic8(p.bic) ? 'text-accent' : ''}`}>{label(p.bic, nameOf)}</span>
              <div className="flex h-3.5 justify-end border-r border-line">
                <span style={{ height: 14, width: `${v < 0 ? (-v / max) * 100 : 0}%`, background: 'var(--err)', borderRadius: '4px 0 0 4px' }} />
              </div>
              <div className="flex h-3.5">
                <span style={{ height: 14, width: `${v > 0 ? (v / max) * 100 : 0}%`, background: 'var(--ok)', borderRadius: '0 4px 4px 0' }} />
              </div>
              <span className="mono text-right" style={{ color: v > 0 ? 'var(--ok-fg)' : v < 0 ? 'var(--err-fg)' : 'var(--muted)' }}>
                {v > 0 ? '+' : ''}
                {formatAmount(p.net.amount)}
              </span>
            </div>
          )
        })}
        <div className="flex justify-between text-[11px] text-muted" style={{ padding: '0 96px 0 64px' }}>
          <span>← net payer</span>
          <span>net receiver →</span>
        </div>
      </div>
    </Card>
  )
}

function Efficiency({ cycle }: { cycle: DnsCycle }) {
  const gross = n(cycle.grossTotal)
  const rows = [
    { k: 'Gross (every payment)', m: cycle.grossTotal, color: 'var(--muted)' },
    { k: 'Bilateral net', m: cycle.bilateralTotal, color: 'var(--info)' },
    { k: 'Multilateral net', m: cycle.multilateralTotal, color: 'var(--accent)' },
  ]
  const saving = gross > 0 ? (1 - n(cycle.multilateralTotal) / gross) * 100 : 0
  return (
    <Card>
      <CardHeader>
        <h2 className="card-t">Netting efficiency</h2>
      </CardHeader>
      <div className="flex flex-col gap-2.5 px-4 py-3.5">
        {rows.map((r) => (
          <div key={r.k} className="flex flex-col gap-1">
            <div className="flex justify-between text-xs">
              <span>{r.k}</span>
              <span className="mono font-medium">{formatMoney(r.m, true)}</span>
            </div>
            <div className="h-2 rounded bg-track">
              <div className="h-2 rounded" style={{ width: `${gross > 0 ? (n(r.m) / gross) * 100 : 0}%`, background: r.color }} />
            </div>
          </div>
        ))}
        <div className="text-xs text-muted">
          Liquidity needed falls by <b className="text-fg">{saving.toFixed(1)}%</b> versus settling every payment gross.
        </div>
      </div>
    </Card>
  )
}

function Instructions({ cycle, nameOf }: { cycle: DnsCycle; nameOf: (b: string) => string }) {
  const moves = cycle.positions.filter((p) => !/^-?0*\.?0*$/.test(p.net.amount))
  return (
    <Card>
      <CardHeader>
        <h2 className="card-t">Settlement instructions on T2</h2>
        <span className="text-xs text-muted">{cycle.status === 'OPEN' ? 'if it settled now' : 'booked as one entry'}</span>
      </CardHeader>
      {moves.length === 0 ? (
        <div className="px-4 py-3 text-xs text-muted">Nothing to move: every position nets to zero.</div>
      ) : (
        <ul className="m-0 list-none py-1.5 pl-0">
          {moves.map((p) => (
            <li key={p.bic} className="flex justify-between gap-2.5 px-4 py-1.5 text-xs">
              <span>
                {isNegative(p.net) ? `Debit ${label(p.bic, nameOf)} (${p.bic})` : `Credit ${label(p.bic, nameOf)} (${p.bic})`}
              </span>
              <span className="mono font-medium">EUR {formatAmount(p.net.amount.replace('-', ''))}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

// ---------------------------------------------------------------- Instant

function InstantPanel({ me, nameOf, tz }: { me: ClearingParticipant; nameOf: (b: string) => string; tz: string }) {
  const [paused, setPaused] = useState(false)
  const inst = useInstant(me.bic, true, paused)
  const now = useTick(250, paused)
  const v = inst.data
  if (!v) return <Card className="px-4 py-4 text-muted">{inst.isError ? 'Cannot read MiniPay Instant.' : 'Loading…'}</Card>
  const pct = v.today > 0 ? ((v.accepted / v.today) * 100).toFixed(1) : '—'
  const secs = (ms: number | null) => (ms == null ? '—' : `${(ms / 1000).toFixed(1)} s`)
  const timeout = v.timeoutSeconds * 1000

  return (
    <div className="flex flex-col gap-4">
      <section aria-label="Instant figures" className="grid grid-cols-5 gap-3">
        <div className="card px-4 py-3">
          <span className="lbl">Scheme</span>
          <div className="mt-1 font-semibold">{v.systemName} · EUR</div>
          <span className="hint">24/7 · {v.timeoutSeconds} s timeout</span>
        </div>
        <Tile k="Today" v={String(v.today)} note={`${pct}% accepted · ${v.rejected} rejected`} />
        <Tile k="Median time" v={secs(v.medianMillis)} note={`p99 ${secs(v.p99Millis)}`} />
        <Tile k="Timeouts" v={String(v.timedOut)} note="AB05 · funds released" tone={v.timedOut > 0 ? 'err' : undefined} />
        <Tile k="Prefunded position" v={formatMoney(v.available, true)} note={`${formatMoney(v.reserved)} reserved for pending payments · ${me.instantAccount}`} />
      </section>

      <Card className="overflow-hidden">
        <CardHeader>
          <div className="flex items-center gap-2.5">
            <span className="size-2 rounded-full" style={{ background: paused ? 'var(--muted)' : 'var(--ok)' }} />
            <h2 className="card-t">Live feed</h2>
            <span className="text-xs text-muted">newest first · the bar fills toward the {v.timeoutSeconds} s timeout</span>
          </div>
          <Button onClick={() => setPaused(!paused)}>{paused ? 'Resume' : 'Pause'}</Button>
        </CardHeader>
        {v.feed.length === 0 ? (
          <div className="px-4 py-6 text-muted">No instant payments today yet. Create one with service level Instant.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="tbl dense">
              <thead>
                <tr>
                  <th>Started</th>
                  <th>UETR</th>
                  <th>Direction</th>
                  <th>Counterparty</th>
                  <th className="!text-right">Amount EUR</th>
                  <th style={{ width: 260 }}>Time to timeout</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {v.feed.map((t) => {
                  const out = bic8(t.payerBic) === bic8(me.bic)
                  const other = out ? t.payeeBic : t.payerBic
                  const started = new Date(t.receivedAt).getTime()
                  const end = t.settledAt ? new Date(t.settledAt).getTime() : t.status === 'PENDING' ? now : t.deadline ? new Date(t.deadline).getTime() : started
                  const el = Math.max(0, Math.min(end - started, timeout))
                  const timedOut = t.reasonCode === 'AB05'
                  const pending = t.status === 'PENDING'
                  const color = pending ? (el < timeout / 2 ? 'var(--info)' : el < timeout * 0.8 ? 'var(--warn)' : 'var(--err)') : t.status === 'SETTLED' ? 'var(--ok)' : 'var(--err)'
                  const status = pending ? 'PENDING' : t.status === 'SETTLED' ? 'ACSC' : `RJCT ${t.reasonCode ?? ''}`
                  const tone: Tone = pending ? 'info' : t.status === 'SETTLED' ? 'ok' : 'err'
                  return (
                    <tr key={t.id}>
                      <td className="mono text-muted">{time(t.receivedAt, tz, true)}</td>
                      <td className="mono">{t.uetr.slice(0, 8)}…{t.uetr.slice(-4)}</td>
                      <td>
                        <span className={`dir ${out ? 'out' : 'in'}`}>{out ? '↗' : '↙'}</span>
                      </td>
                      <td>
                        <div className="mono">{other}</div>
                        <div className="text-[11px] text-muted">{nameOf(other)}</div>
                      </td>
                      <td className="num mono font-medium">{formatMoney(t.amount)}</td>
                      <td>
                        <div className="flex items-center gap-2.5">
                          <div className="relative h-2 flex-1 rounded bg-track">
                            <div className="h-2 rounded" style={{ width: `${(el / timeout) * 100}%`, background: color }} />
                          </div>
                          <span className="mono w-16 text-right text-[11px]" style={{ color: pending && el >= timeout * 0.8 ? 'var(--err-fg)' : 'var(--muted)' }}>
                            {pending ? `${((timeout - el) / 1000).toFixed(1)} s left` : timedOut ? 'timed out' : `${(el / 1000).toFixed(1)} s`}
                          </span>
                        </div>
                      </td>
                      <td title={t.reasonDetail ?? undefined}>
                        <Chip small tone={tone}>
                          {status}
                        </Chip>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}
