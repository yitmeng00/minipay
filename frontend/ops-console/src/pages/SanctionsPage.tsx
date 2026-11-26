import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, Card, CardHeader, Chip, Segmented, type Tone } from '../components/ui'
import {
  ApiError,
  decisionsCsvUrl,
  newIdempotencyKey,
  useBankInfo,
  useCases,
  useComplianceSettings,
  useComplianceStats,
  useDecide,
  useSanctionsLists,
  type DecisionBody,
} from '../lib/api'
import { useBank } from '../lib/banks'
import { formatMoney } from '../lib/money'
import { useOperator } from '../lib/operator'
import type { CaseStatus, ComplianceCase, DecisionAction, DecisionReason, Hit, NameToken } from '../lib/types'

// ---------------------------------------------------------------- formatting

const STATUS: Record<CaseStatus, { label: string; tone: Tone }> = {
  OPEN: { label: 'HELD', tone: 'warn' },
  ESCALATED: { label: 'ESCALATED · MLRO', tone: 'warn' },
  PENDING_APPROVAL: { label: 'AWAITING 2ND APPROVER', tone: 'info' },
  RELEASED: { label: 'RELEASED', tone: 'ok' },
  REJECTED: { label: 'REJECTED', tone: 'err' },
}

const MATCH_TONE: Record<string, Tone> = { Yes: 'err', Strong: 'err', Partial: 'warn', Weak: 'info', No: 'ok', 'n/a': 'neutral' }

const ROLE: Record<string, string> = {
  DEBTOR: 'Debtor',
  CREDITOR: 'Creditor',
  DEBTOR_AGENT: 'Debtor agent',
  CREDITOR_AGENT: 'Creditor agent',
  INSTRUCTING_AGENT: 'Instructing agent',
  INSTRUCTED_AGENT: 'Instructed agent',
  REIMBURSEMENT_AGENT: 'Reimbursement agent',
}

const REASONS: { value: DecisionReason; label: string }[] = [
  { value: 'FALSE_POSITIVE', label: 'False positive — different entity' },
  { value: 'TRUE_MATCH', label: 'True match — sanctioned party' },
  { value: 'INSUFFICIENT_INFORMATION', label: 'Insufficient information — escalate to MLRO' },
]

const scoreColor = (s: number) => (s >= 90 ? 'var(--err)' : s >= 85 ? 'var(--warn)' : 'var(--info)')
const factorColor = (p: number) => (p >= 80 ? 'var(--err)' : p >= 50 ? 'var(--warn)' : 'var(--muted)')

function minutesBetween(from: string, to: Date) {
  return Math.max(0, Math.round((to.getTime() - new Date(from).getTime()) / 60_000))
}

function duration(min: number) {
  return min < 60 ? `${min}m` : `${Math.floor(min / 60)}h ${String(min % 60).padStart(2, '0')}m`
}

function clock(iso: string, tz: string) {
  return new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(iso))
}

function useNow(everyMs = 30_000) {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), everyMs)
    return () => clearInterval(t)
  }, [everyMs])
  return now
}

function Tokens({ tokens }: { tokens: NameToken[] }) {
  return (
    <>
      {tokens.map((t, i) => (
        <span key={i}>{t.matched ? <mark>{t.text}</mark> : t.text} </span>
      ))}
    </>
  )
}

// ---------------------------------------------------------------- page

/**
 * Payments held by sanctions screening, and the decision on each. The
 * queue is this bank's: every bank on a route screens for itself.
 */
export function SanctionsPage() {
  const { bank } = useBank()
  const { caseId } = useParams()
  const navigate = useNavigate()
  const tz = useBankInfo().data?.timeZone ?? 'UTC'
  const businessDate = useBankInfo().data?.businessDate
  const [view, setView] = useState<'open' | 'closed'>('open')
  const [list, setList] = useState<string>('')
  const [showRules, setShowRules] = useState(false)
  const stats = useComplianceStats().data
  const settings = useComplianceSettings().data
  const lists = useSanctionsLists().data ?? []
  const open = useCases('open').data ?? []
  const closed = useCases('closed').data ?? []
  const shown = (view === 'open' ? open : closed).filter((c) => !list || c.hits.some((h) => h.listCode === list))
  const selected = [...open, ...closed].find((c) => c.id === caseId) ?? shown[0]
  const now = useNow()

  const snapshot = lists.map((l) => `${l.code} ${l.snapshotDate}`).join(', ')
  const oldest = stats?.oldestHeldAt ? minutesBetween(stats.oldestHeldAt, now) : null
  const counts = (cases: ComplianceCase[], code?: string) => cases.filter((c) => !code || c.hits.some((h) => h.listCode === code)).length
  const source = view === 'open' ? open : closed

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Sanctions screening queue</h1>
          <p className="mt-0.5 mb-0 text-muted">
            Payments held at screening · lists are simulated snapshots ({snapshot || '…'}) · release is four-eyes for scores ≥{' '}
            {settings?.fourEyesThreshold ?? 90}
          </p>
        </div>
        <div className="flex gap-2">
          <Button onClick={() => setShowRules(!showRules)} aria-expanded={showRules}>
            Screening rules
          </Button>
          {businessDate && (
            <a
              className="inline-flex h-8 items-center rounded-md border border-line bg-surface px-3 font-medium text-fg no-underline hover:bg-surface2"
              href={decisionsCsvUrl(bank.id, businessDate, businessDate)}
            >
              Export decisions
            </a>
          )}
        </div>
      </div>

      {showRules && settings && (
        <Card className="flex flex-col gap-2 px-4 py-3 text-xs">
          <span className="lbl">Screening rules</span>
          <span>
            Every payment is screened before any money moves: the debtor's and creditor's names (fuzzy, word order and spelling
            tolerant) and every bank's BIC on the route (exact). A score ≥ <b>{settings.holdThreshold}</b> holds the payment;
            releasing a hit ≥ <b>{settings.fourEyesThreshold}</b> needs a second person. Decisions are due within{' '}
            <b>{duration(settings.slaMinutes)}</b>. A rejection is sent as ISO reason <span className="mono">RR04</span> (regulatory
            reason) and nothing more, so the listed party is not tipped off.
          </span>
          <span className="flex flex-wrap gap-4">
            {lists.map((l) => (
              <span key={l.code}>
                <Chip tone="info" small>
                  {l.code}
                </Chip>{' '}
                {l.name} · snapshot {l.snapshotDate} · {l.activeEntries} entries in force
              </span>
            ))}
          </span>
        </Card>
      )}

      <section aria-label="Queue figures" className="grid grid-cols-[repeat(auto-fit,minmax(160px,1fr))] gap-3">
        <Figure label="Pending review" value={stats?.pending ?? '—'}>
          {stats && (
            <span className="text-xs text-muted">
              {stats.awaitingApproval} awaiting approval · {stats.escalated} escalated
            </span>
          )}
        </Figure>
        <Figure label="Oldest hold" value={oldest === null ? '—' : duration(oldest)}>
          {stats && (
            <Chip tone={oldest !== null && oldest >= stats.slaMinutes ? 'err' : oldest !== null && oldest >= stats.slaMinutes / 2 ? 'warn' : 'ok'}>
              SLA {duration(stats.slaMinutes)}
            </Chip>
          )}
        </Figure>
        <Figure label="Released today" value={stats?.releasedToday ?? '—'} />
        <Figure label="Rejected today" value={stats?.rejectedToday ?? '—'} />
        <Figure label="Median decision time" value={stats?.medianDecisionMinutes == null ? '—' : duration(stats.medianDecisionMinutes)} />
      </section>

      <div className="flex flex-wrap items-start gap-4">
        <Card className="max-w-[460px] min-w-0 flex-[1_1_380px] overflow-hidden">
          <CardHeader>
            <Segmented<'open' | 'closed'>
              label="Queue"
              value={view}
              onChange={setView}
              options={[
                { value: 'open', label: `Held ${open.length}` },
                { value: 'closed', label: `Decided today ${closed.length}` },
              ]}
            />
            <Segmented<string>
              label="List source"
              value={list}
              onChange={setList}
              options={[{ value: '', label: `All ${counts(source)}` }, ...lists.map((l) => ({ value: l.code, label: `${l.code} ${counts(source, l.code)}` }))]}
            />
          </CardHeader>
          <div role="listbox" aria-label="Held payments">
            {shown.length === 0 && (
              <div className="p-7 text-center text-muted">
                {view === 'open' ? 'Nothing held: every payment screened so far was clear.' : 'No decisions yet today.'}
              </div>
            )}
            {shown.map((c) => (
              <QueueItem key={c.id} c={c} on={c.id === selected?.id} now={now} onPick={() => navigate(`/sanctions/${c.id}`)} />
            ))}
          </div>
        </Card>

        {selected ? (
          <Review key={selected.id} c={selected} tz={tz} now={now} />
        ) : (
          <Card className="flex-[999_1_640px] p-10 text-center text-muted">Pick a held payment to review it.</Card>
        )}
      </div>
    </>
  )
}

function Figure({ label, value, children }: { label: string; value: React.ReactNode; children?: React.ReactNode }) {
  return (
    <Card className="flex flex-col gap-1 px-4 py-3">
      <span className="lbl">{label}</span>
      <div className="flex flex-wrap items-center gap-2 text-[22px] font-semibold tabular-nums">{value}</div>
      {children}
    </Card>
  )
}

function QueueItem({ c, on, now, onPick }: { c: ComplianceCase; on: boolean; now: Date; onPick: () => void }) {
  const top = c.hits[0]
  const age = minutesBetween(c.heldAt, now)
  const late = now > new Date(c.slaDeadline)
  const closed = c.status === 'RELEASED' || c.status === 'REJECTED'
  return (
    <button type="button" role="option" aria-selected={on} className={`qitem ${on ? 'on' : ''}`} onClick={onPick}>
      <span className="truncate font-semibold">{top?.partyName}</span>
      <span className="mono text-xs">{formatMoney(c.amount, true)}</span>
      <span className="truncate text-xs text-muted">≈ {top?.matchedName}</span>
      <span className="inline-flex items-center gap-1.5">
        <span className="h-1.5 w-[54px] overflow-hidden rounded-[3px] bg-track">
          <span className="block h-1.5" style={{ width: `${c.bestScore}%`, background: scoreColor(c.bestScore) }} />
        </span>
        <span className="mono text-xs font-semibold">{c.bestScore}</span>
      </span>
      <span className="flex items-center gap-1.5 text-[11px] text-muted">
        <Chip tone="info" small>
          {top?.listCode}
        </Chip>
        <span>{ROLE[top?.role] ?? top?.role}</span>
        {c.hits.length > 1 && <span>· +{c.hits.length - 1} hit{c.hits.length > 2 ? 's' : ''}</span>}
        <span>· {c.direction === 'OUTGOING' ? 'outbound' : 'inbound'}</span>
      </span>
      <span className="text-right text-[11px]">
        {closed ? (
          <Chip tone={STATUS[c.status].tone} small>
            {c.status === 'RELEASED' ? 'Released' : 'Rejected'}
          </Chip>
        ) : c.status !== 'OPEN' ? (
          <Chip tone={STATUS[c.status].tone} small>
            {c.status === 'PENDING_APPROVAL' ? 'Awaiting 2nd' : 'Escalated'}
          </Chip>
        ) : (
          <span style={{ color: late ? 'var(--err-fg)' : age >= 60 ? 'var(--warn-fg)' : 'var(--muted)' }}>held {duration(age)}</span>
        )}
      </span>
    </button>
  )
}

// ---------------------------------------------------------------- review

function Review({ c, tz, now }: { c: ComplianceCase; tz: string; now: Date }) {
  const [hitIndex, setHitIndex] = useState(0)
  const hit: Hit = c.hits[Math.min(hitIndex, c.hits.length - 1)]
  const left = Math.round((new Date(c.slaDeadline).getTime() - now.getTime()) / 60_000)
  const closed = c.status === 'RELEASED' || c.status === 'REJECTED'
  const nameMatch = hit.matchedOn === 'BIC' ? 'Yes' : hit.score >= 95 ? 'Strong' : 'Partial'

  return (
    <Card className="flex min-w-0 flex-[999_1_640px] flex-col">
      <CardHeader className="!flex-nowrap !items-start">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="m-0 text-[15px] font-semibold">Review · {hit.partyName}</h2>
            <Chip tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Chip>
            <Chip tone="info">{hit.listName}</Chip>
          </div>
          <span className="text-xs text-muted">
            <span className="mono">{c.uetr}</span> · {c.direction === 'OUTGOING' ? 'Outbound' : 'Inbound'}
            {c.kind === 'COVER' ? ' cover (pacs.009)' : ''} · {(ROLE[hit.role] ?? hit.role).toLowerCase()} screened · matched on{' '}
            <span className="mono">{hit.field}</span>
          </span>
        </div>
        <div className="flex-none text-right">
          <div className="mono text-lg font-semibold">{formatMoney(c.amount, true)}</div>
          <div className="text-xs text-muted">
            Held {clock(c.heldAt, tz)} ·{' '}
            {closed ? (
              <span>decided in {duration(minutesBetween(c.heldAt, new Date(c.closedAt!)))}</span>
            ) : (
              <span style={{ color: left < 0 ? 'var(--err-fg)' : left < 60 ? 'var(--warn-fg)' : undefined }}>
                {left < 0 ? `${duration(-left)} past the SLA` : `${duration(left)} left of the SLA`}
              </span>
            )}
          </div>
        </div>
      </CardHeader>

      <div className="flex flex-wrap gap-4 border-b border-line2 px-4 py-3.5 text-xs">
        <span>
          <span className="text-muted">Debtor</span> {c.debtorName}
        </span>
        <span>
          <span className="text-muted">Creditor</span> {c.creditorName}
        </span>
        <span>
          <span className="text-muted">Route</span> <span className="mono">{c.route}</span>
        </span>
        <Link to={`/payments/${c.uetr}`} className="ml-auto font-medium text-accent no-underline">
          Open payment →
        </Link>
      </div>

      {c.hits.length > 1 && (
        <div role="tablist" aria-label="Hits" className="flex gap-0.5 border-b border-line px-2">
          {c.hits.map((h, i) => (
            <button key={h.id} type="button" role="tab" aria-selected={h === hit} className={`tab ${h === hit ? 'on' : ''}`} onClick={() => setHitIndex(i)}>
              {ROLE[h.role] ?? h.role} ≈ {h.entryId}
              <span className="tabcount">{h.score}</span>
            </button>
          ))}
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="tbl table-fixed">
          <colgroup>
            <col className="w-[130px]" />
            <col />
            <col />
            <col className="w-[96px]" />
          </colgroup>
          <thead>
            <tr>
              <th>Attribute</th>
              <th>Payment party</th>
              <th>
                List entry · <span className="mono normal-case">{hit.entryId}</span>
              </th>
              <th>Match</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="text-muted">{hit.matchedOn === 'BIC' ? 'Bank' : hit.matchedOn === 'ALIAS' ? 'Name ≈ alias' : 'Name'}</td>
              <td className="!whitespace-normal font-medium">
                <Tokens tokens={hit.partyTokens} />
              </td>
              <td className="!whitespace-normal font-medium">
                <Tokens tokens={hit.listedTokens} />
                {hit.matchedOn === 'ALIAS' && <div className="text-[11px] font-normal text-muted">alias of {hit.listedName}</div>}
              </td>
              <td>
                <Chip tone={MATCH_TONE[nameMatch]} small>
                  {nameMatch}
                </Chip>
              </td>
            </tr>
            {hit.comparison.map((r) => (
              <tr key={r.attribute}>
                <td className="text-muted">{r.attribute}</td>
                <td className={`!whitespace-normal ${/Account|Registration|Address|BIC/.test(r.attribute) ? 'mono' : ''}`}>{r.party}</td>
                <td className={`!whitespace-normal ${/Account|Registration|Address|BIC/.test(r.attribute) ? 'mono' : ''}`}>{r.listed}</td>
                <td>
                  <Chip tone={MATCH_TONE[r.match] ?? 'neutral'} small>
                    {r.match}
                  </Chip>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] border-t border-line2">
        <div className="flex flex-col gap-2.5 border-r border-line2 px-4 py-3.5">
          <div className="flex items-baseline justify-between">
            <span className="lbl">Score breakdown</span>
            <span className="mono text-lg font-semibold">
              {hit.score}
              <span className="text-xs text-muted"> / 100</span>
            </span>
          </div>
          {hit.factors.map((f) => (
            <div key={f.name} className="grid grid-cols-[140px_minmax(0,1fr)_88px] items-center gap-2.5 text-xs">
              <span className="text-muted">{f.name}</span>
              <span className="h-1.5 overflow-hidden rounded-[3px] bg-track">
                {f.percent !== null && <span className="block h-1.5" style={{ width: `${f.percent}%`, background: factorColor(f.percent) }} />}
              </span>
              <span className="mono truncate text-right" title={f.value}>
                {f.value}
              </span>
            </div>
          ))}
          <div className="mt-1 rounded-md bg-surface2 px-2.5 py-2 text-xs">
            <span className="font-semibold">Engine hint:</span> {hit.hint}
          </div>
          <span className="text-[11px] text-muted">
            Screened against {c.lists} as of {c.screenedAsOf}. Listed {hit.listedFrom}
            {hit.listedTo ? `, delisted after ${hit.listedTo}` : ''} · {hit.program}.
          </span>
        </div>
        <DecisionPanel c={c} tz={tz} />
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- decisions

function DecisionPanel({ c, tz }: { c: ComplianceCase; tz: string }) {
  const { operator } = useOperator()
  const decide = useDecide(c.id)
  const [reason, setReason] = useState<DecisionReason>('FALSE_POSITIVE')
  const [comment, setComment] = useState('')
  // One key per intention: a retry after a timeout replays instead of deciding twice. A new key once the
  // case has moved on (the previous decision succeeded).
  const key = useMemo(() => newIdempotencyKey('dec'), [c.id, c.status]) // eslint-disable-line react-hooks/exhaustive-deps
  const okLen = comment.trim().length >= 20
  const lastRelease = [...c.decisions].reverse().find((d) => d.action === 'RELEASE')
  const isReleaser = lastRelease?.decidedBy === operator.id
  const closed = c.status === 'RELEASED' || c.status === 'REJECTED'

  const send = (action: DecisionAction, r: DecisionReason | null, text = comment) => {
    const body: DecisionBody = { action, reason: r, comment: text, decidedBy: operator.id }
    decide.mutate({ body, key }, { onSuccess: () => setComment('') })
  }

  const commentField = (
    <div className="field">
      <label htmlFor="cmt">
        Comment <span className="text-err-fg">*</span>
      </label>
      <textarea
        id="cmt"
        className="ta"
        rows={3}
        maxLength={500}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        placeholder="Explain which identifiers rule the match in or out (min. 20 characters)"
        aria-required="true"
        aria-describedby="cmt-h"
      />
      <span id="cmt-h" className="hint flex justify-between">
        <span className={okLen ? '' : 'text-err-fg'}>
          {okLen ? 'Recorded in the audit trail with your decision' : `Required — ${20 - comment.trim().length} more characters`}
        </span>
        <span className="mono">{comment.length}/500</span>
      </span>
    </div>
  )

  return (
    <div className="flex flex-col gap-2.5 px-4 py-3.5">
      <span className="lbl">Decision</span>

      {(c.status === 'OPEN' || c.status === 'ESCALATED') && (
        <>
          {c.status === 'ESCALATED' && <span className="hint text-warn-fg">Escalated to the MLRO: decide on the information now gathered.</span>}
          <div className="field">
            <label htmlFor="reason">Reason</label>
            <select id="reason" className="sel" value={reason} onChange={(e) => setReason(e.target.value as DecisionReason)}>
              {REASONS.filter((r) => c.status === 'OPEN' || r.value !== 'INSUFFICIENT_INFORMATION').map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </div>
          {commentField}
          {c.fourEyes && reason === 'FALSE_POSITIVE' && (
            <span className="hint text-warn-fg">Score ≥ 90 — the release goes to a second approver before the payment continues.</span>
          )}
          <div className="flex gap-2">
            {reason === 'INSUFFICIENT_INFORMATION' ? (
              <Button className="flex-1 justify-center" disabled={!okLen || decide.isPending} onClick={() => send('ESCALATE', reason)}>
                Escalate to MLRO
              </Button>
            ) : (
              <>
                <Button
                  className="flex-1 justify-center !border-ok !text-ok-fg"
                  disabled={!okLen || reason !== 'FALSE_POSITIVE' || decide.isPending}
                  title={reason !== 'FALSE_POSITIVE' ? 'A release needs reason: false positive' : undefined}
                  onClick={() => send('RELEASE', 'FALSE_POSITIVE')}
                >
                  Release
                </Button>
                <Button
                  className="flex-1 justify-center !border-err !text-err-fg"
                  disabled={!okLen || reason !== 'TRUE_MATCH' || decide.isPending}
                  title={reason !== 'TRUE_MATCH' ? 'A rejection needs reason: true match' : undefined}
                  onClick={() => send('REJECT', 'TRUE_MATCH')}
                >
                  Reject
                </Button>
              </>
            )}
          </div>
        </>
      )}

      {c.status === 'PENDING_APPROVAL' && lastRelease && (
        <>
          <div className="flex flex-col gap-1 rounded-md bg-info-bg px-3 py-2.5 text-info-fg">
            <span className="font-semibold">Released by {lastRelease.decidedBy} — awaiting a second approver</span>
            <span className="text-xs">“{lastRelease.comment}”</span>
            <span className="text-[11px]">{clock(lastRelease.decidedAt, tz)}</span>
          </div>
          {isReleaser ? (
            <>
              <span className="hint">You released it: someone else must approve it (four-eyes principle).</span>
              <Button
                className="self-start"
                disabled={decide.isPending}
                onClick={() => send('WITHDRAW', null, 'Release withdrawn by the releaser before approval')}
              >
                Withdraw my release
              </Button>
            </>
          ) : (
            <>
              {commentField}
              <div className="flex gap-2">
                <Button className="flex-1 justify-center !border-ok !text-ok-fg" disabled={!okLen || decide.isPending} onClick={() => send('APPROVE', null)}>
                  Approve release
                </Button>
                <Button className="flex-1 justify-center" disabled={!okLen || decide.isPending} onClick={() => send('DECLINE', null)}>
                  Decline
                </Button>
              </div>
            </>
          )}
        </>
      )}

      {closed && <Outcome c={c} />}

      {decide.error && (
        <span className="rounded-md bg-err-bg px-2.5 py-2 text-xs text-err-fg">
          {decide.error instanceof ApiError ? decide.error.message : 'The decision could not be recorded'}
        </span>
      )}

      {c.decisions.length > 0 && (
        <ol className="m-0 mt-1 flex list-none flex-col gap-1.5 border-t border-line2 p-0 pt-2.5">
          <span className="lbl">Audit trail</span>
          {c.decisions.map((d) => (
            <li key={d.id} className="text-xs">
              <span className="mono text-muted">{clock(d.decidedAt, tz)}</span> <b>{d.action}</b>
              {d.reason ? ` · ${d.reason.toLowerCase().replace(/_/g, ' ')}` : ''} by <span className="mono">{d.decidedBy}</span>{' '}
              <span className="text-muted">
                ({d.statusBefore} → {d.statusAfter})
              </span>
              <div className="text-muted">“{d.comment}”</div>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

function Outcome({ c }: { c: ComplianceCase }) {
  const released = c.status === 'RELEASED'
  const title = released
    ? c.direction === 'OUTGOING'
      ? 'Released — the payment continues to the funds reservation and is sent'
      : 'Released — the payment continues: credited, passed on or settled'
    : c.direction === 'OUTGOING'
      ? 'Rejected — RR04, nothing was reserved; the debtor keeps the funds'
      : 'Rejected — pacs.002 RJCT RR04 sent back, nothing was booked'
  return (
    <div className={`flex flex-col gap-1 rounded-md px-3 py-2.5 ${released ? 'bg-ok-bg text-ok-fg' : 'bg-err-bg text-err-fg'}`}>
      <span className="font-semibold">{title}</span>
      <span className="text-xs">
        {c.decisions
          .filter((d) => d.statusAfter === c.status || d.action === 'RELEASE')
          .slice(-2)
          .map((d) => d.decidedBy)
          .join(' + ')}
      </span>
    </div>
  )
}
