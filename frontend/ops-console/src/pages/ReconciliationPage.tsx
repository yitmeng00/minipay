import { useMemo, useState } from 'react'
import { Button, Card, CardHeader, Chip, Segmented, type Tone } from '../components/ui'
import {
  ApiError,
  newIdempotencyKey,
  useBankInfo,
  useManualMatch,
  useNostros,
  usePublishStatements,
  useReconciliation,
  useReopenBreak,
  useRerun,
  useResolveBreak,
} from '../lib/api'
import { useBank } from '../lib/banks'
import { formatAmount, formatMoney, isNegative, isZero } from '../lib/money'
import { useOperator } from '../lib/operator'
import type { Money, ReconBreak, ReconLedgerLine, ReconRow, ReconStatementLine } from '../lib/types'

/**
 * Reconciliation (M8, ADR-009): our ledger's nostro against the servicer's camt.053 end-of-day statement.
 * Amounts come signed from our point of view (+ money in); a ledger Dr (our asset grows) is a statement CRDT
 * (the servicer owes us more), a ledger Cr a statement DBIT.
 */

const RULE: Record<string, { label: string; tone: Tone }> = {
  UETR: { label: 'UETR', tone: 'ok' },
  BATCH: { label: 'Batch', tone: 'ok' },
  E2E: { label: 'E2E + amt', tone: 'ok' },
  REFERENCE: { label: 'Ref + amt', tone: 'ok' },
  TAKE_ON: { label: 'Take-on', tone: 'ok' },
  CONTRA: { label: 'Contra', tone: 'neutral' },
  ADJUSTMENT: { label: 'Adjusted', tone: 'info' },
  MANUAL: { label: 'Manual', tone: 'info' },
}

const BREAK: Record<ReconBreak['type'], { label: string; tone: Tone }> = {
  MISSING_IN_LEDGER: { label: 'Missing in ledger', tone: 'warn' },
  MISSING_IN_STATEMENT: { label: 'Missing in statement', tone: 'info' },
  AMOUNT_MISMATCH: { label: 'Amount mismatch', tone: 'err' },
  DUPLICATE: { label: 'Duplicate', tone: 'err' },
}

const ACTION: Record<string, string> = {
  CARRY_FORWARD: 'Carry forward',
  ADJUSTMENT: 'Post adjustment',
  CLAIM_RAISED: 'Raise claim',
}

function time(iso: string | null | undefined, tz: string) {
  if (!iso) return ''
  return new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit' }).format(new Date(iso))
}

function stamp(iso: string | null | undefined, tz: string) {
  if (!iso) return ''
  return new Intl.DateTimeFormat('en-GB', { timeZone: tz, day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(iso))
}

function age(iso: string) {
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000))
  return min < 60 ? `${min}m` : `${Math.floor(min / 60)}h ${String(min % 60).padStart(2, '0')}m`
}

const abs = (m: Money) => formatAmount(m.amount.replace('-', ''))
const ledgerSide = (m: Money) => (isNegative(m) ? 'Cr' : 'Dr')
const stmtSide = (m: Money) => (isNegative(m) ? 'DBIT' : 'CRDT')
const signedText = (m: Money) => (isNegative(m) ? '−' : isZero(m) ? '' : '+') + abs(m)

function Tile({ k, v, note, tone }: { k: string; v: string; note?: string; tone?: 'err' | 'ok' }) {
  return (
    <div className={`card flex flex-col gap-1 px-4 py-3 ${tone === 'err' ? '!border-err' : ''}`}>
      <span className="lbl">{k}</span>
      <span className="mono text-[17px] font-semibold" style={{ color: tone === 'err' ? 'var(--err-fg)' : tone === 'ok' ? 'var(--ok-fg)' : 'var(--fg)' }}>
        {v}
      </span>
      {note && <span className="hint">{note}</span>}
    </div>
  )
}

/** A new bank starts afresh: its first nostro, its latest statement. */
export function ReconciliationPage() {
  const { bank } = useBank()
  return <Reconcile key={bank.id} />
}

function Reconcile() {
  const { bank } = useBank()
  const tz = useBankInfo().data?.timeZone ?? 'UTC'
  const { operator } = useOperator()
  const nostros = useNostros()
  const [account, setAccount] = useState<string>()
  const [date, setDate] = useState<string>()
  const [filter, setFilter] = useState<'all' | 'm' | 'b'>('all')
  const [selected, setSelected] = useState<number>(0)
  const [error, setError] = useState<string | null>(null)
  const [picked, setPicked] = useState<{ ledger: number[]; statement: number[] }>({ ledger: [], statement: [] })

  const acct = account ?? nostros.data?.[0]?.account
  const recon = useReconciliation(acct, date)
  const rerun = useRerun()
  const publish = usePublishStatements()
  const manual = useManualMatch()
  const r = recon.data

  const rows = useMemo(
    () => (r?.rows ?? []).filter((x) => filter === 'all' || (filter === 'm' ? x.matchId != null : x.breakId != null)),
    [r, filter],
  )
  const sel = rows[Math.min(selected, rows.length - 1)]
  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : String(e))

  if (nostros.data && nostros.data.length === 0) {
    return (
      <main className="flex flex-col gap-4 p-6">
        <Header bank={bank.name} />
        <Card className="px-4 py-4 text-muted">{bank.name} has no nostros configured for reconciliation (minipay.recon.nostros).</Card>
      </main>
    )
  }

  const pickedSum = (r?.rows ?? [])
    .flatMap((x) => [...x.ledger.filter((l) => picked.ledger.includes(l.postingId)).map((l) => Number(l.signed.amount)), ...x.statement.filter((s) => picked.statement.includes(s.id)).map((s) => -Number(s.signed.amount))])
    .reduce((a, b) => a + b, 0)
  const pickedCount = picked.ledger.length + picked.statement.length

  return (
    <main className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <Header bank={bank.name} />
        <div className="flex flex-wrap items-end gap-3">
          <label className="field">
            <span className="flabel">Account</span>
            <select className="sel" value={acct ?? ''} onChange={(e) => (setAccount(e.target.value), setDate(undefined), setSelected(0))}>
              {nostros.data?.map((n) => (
                <option key={n.account} value={n.account}>
                  {n.account} · {n.name}
                  {n.openBreaks ? ` · ${n.openBreaks} open` : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="flabel">Statement date</span>
            <select className="sel mono" value={date ?? r?.statement?.date ?? ''} onChange={(e) => (setDate(e.target.value), setSelected(0))} disabled={!r?.dates.length}>
              {(r?.dates ?? []).map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
          <Button disabled={!acct || rerun.isPending} onClick={() => acct && rerun.mutateAsync({ account: acct, key: newIdempotencyKey('rerun') }).then(() => setError(null)).catch(fail)}>
            Re-run matching
          </Button>
        </div>
      </div>
      {error && <Card className="bg-err-bg px-4 py-2 text-err-fg">{error}</Card>}

      {r && !r.statement ? (
        <Card className="px-4 py-4">
          <span className="font-semibold">No statement from {r.servicerBic} for {r.account} yet.</span>{' '}
          <span className="text-muted">
            The servicer sends its camt.053 after its end of day. To have every bank you run send today's statements now, use the
            servicer's console:{' '}
          </span>
          <Button className="ml-2" disabled={publish.isPending} onClick={() => publish.mutateAsync({ key: newIdempotencyKey('eod') }).catch(fail)}>
            Send {bank.name}'s statements for today
          </Button>
        </Card>
      ) : r?.statement && r.proof ? (
        <>
          <Card className="flex flex-wrap items-center gap-6 px-4 py-3.5">
            <Field k="Statement" v={r.statement.statementRef} mono />
            <Field k="From" v={r.statement.servicerBic} mono />
            <Field k="Account there" v={r.statement.servicerAccount} mono />
            <Field k="Received" v={stamp(r.statement.receivedAt, tz)} mono />
            <Field k="Message" v="camt.053.001.08" mono />
            <div className="ml-auto flex flex-col gap-0.5">
              <span className="lbl">Matching rules</span>
              <span className="text-xs">take-on → UETR exact → batch (net) → E2E + amount → ref + amount → contra → manual</span>
            </div>
          </Card>

          <section aria-label="Figures" className="grid grid-cols-7 gap-3">
            <Tile k="Ledger entries" v={String(r.ledgerEntries)} note={`our lines on ${r.statement.date}`} />
            <Tile k="Statement entries" v={String(r.statementEntries)} />
            <Tile k="Matched" v={String(r.matched)} note={`${r.matchedManually} by hand or adjusted`} />
            <Tile k="Match rate" v={r.statementEntries ? `${((r.matched / r.statementEntries) * 100).toFixed(1)}%` : '—'} note="of statement entries" />
            <Tile k="Open breaks" v={String(r.openBreaks)} tone={r.openBreaks ? 'err' : 'ok'} />
            <Tile k="Unmatched value" v={formatMoney(r.unmatchedValue)} note={`${r.currency}, absolute`} />
            <div className={`card flex flex-col gap-1 px-4 py-3 ${isZero(r.proof.unexplained) ? '' : '!border-err'}`}>
              <span className="lbl">Balance proof</span>
              <Chip small tone={isZero(r.proof.unexplained) ? 'ok' : 'err'}>
                {isZero(r.proof.unexplained) ? 'Balances' : `Unexplained ${signedText(r.proof.unexplained)}`}
              </Chip>
              <span className="hint">difference = open items</span>
            </div>
          </section>

          <Card className="flex flex-wrap items-center gap-8 px-4 py-3">
            <Field k={`Ledger closing · ${r.statement.date}`} v={formatMoney(r.proof.ledgerClosing)} mono />
            <Field k="Statement ClsgBooked" v={formatMoney(r.proof.statementClosing)} mono />
            <Field k="Difference" v={signedText(r.proof.difference)} mono />
            <Field k="Explained by open items" v={signedText(r.proof.explained)} mono />
            {!isZero(r.proof.openingDiff) && (
              <Field k="of which: opening difference" v={signedText(r.proof.openingDiff)} mono />
            )}
            <span className="text-xs text-muted">
              The two balances may differ only by what is still open: payments not on the other side yet, and breaks. Anything else
              would be a gap in the statements or a wrong opening balance.
            </span>
          </Card>

          <div className="grid grid-cols-[minmax(0,5fr)_minmax(0,3fr)] gap-4">
            <Card className="min-w-0 overflow-hidden">
              <CardHeader>
                <div>
                  <h2 className="card-t">Matching · ledger ↔ statement</h2>
                  <span className="text-xs text-muted">
                    Internal ledger · {r.account} ⟷ camt.053 · {r.servicerBic}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  {pickedCount > 0 && (
                    <ManualMatch
                      count={pickedCount}
                      balanced={Math.abs(pickedSum) < 0.005}
                      pending={manual.isPending}
                      onMatch={(comment) =>
                        manual
                          .mutateAsync({
                            body: { account: r.account, ledgerPostingIds: picked.ledger, statementEntryIds: picked.statement, comment, by: operator.id },
                            key: newIdempotencyKey('match'),
                          })
                          .then(() => (setPicked({ ledger: [], statement: [] }), setError(null)))
                          .catch(fail)
                      }
                    />
                  )}
                  <Segmented<'all' | 'm' | 'b'>
                    label="Rows"
                    options={[
                      { value: 'all', label: 'All' },
                      { value: 'm', label: 'Matched' },
                      { value: 'b', label: 'Unmatched' },
                    ]}
                    value={filter}
                    onChange={(f) => (setFilter(f), setSelected(0))}
                  />
                </div>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="tbl dense">
                  <thead>
                    <tr>
                      <th aria-label="Pick" />
                      <th>Journal</th>
                      <th>Booked</th>
                      <th>Reference</th>
                      <th className="!text-right">Amount</th>
                      <th>Match</th>
                      <th>NtryRef</th>
                      <th>Booked</th>
                      <th>Reference</th>
                      <th className="!text-right">Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row, i) => (
                      <MatchRow
                        key={(row.matchId ?? row.breakId) + '-' + i}
                        row={row}
                        tz={tz}
                        selected={row === sel}
                        onSelect={() => setSelected(i)}
                        picked={picked}
                        onPick={(side, id) =>
                          setPicked((p) => ({ ...p, [side]: p[side].includes(id) ? p[side].filter((x) => x !== id) : [...p[side], id] }))
                        }
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="border-t border-line2 px-4 py-2 text-[11px] text-muted">
                Ledger Dr/Cr is from {bank.name}’s view (the nostro is an asset); statement CRDT/DBIT is from {r.servicerBic}’s view of our
                account. A ledger Cr matches a statement DBIT. Tick unmatched items whose amounts add up to match them by hand.
              </div>
            </Card>

            <EntryPanel row={sel} />
          </div>

          <Breaks breaks={r.breaks} tz={tz} by={operator.id} onError={fail} />
        </>
      ) : (
        <Card className="px-4 py-4 text-muted">Loading…</Card>
      )}
    </main>
  )
}

function Header({ bank }: { bank: string }) {
  return (
    <div>
      <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Reconciliation</h1>
      <p className="mt-0.5 mb-0 text-muted">{bank}’s ledger against the servicer’s camt.053 end-of-day statement, nostro by nostro</p>
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

function ledgerRef(l: ReconLedgerLine) {
  return l.endToEndId && l.endToEndId !== 'NOTPROVIDED' ? l.endToEndId : l.narrative ?? ''
}

function stmtRef(s: ReconStatementLine) {
  return s.endToEndId && s.endToEndId !== 'NOTPROVIDED' ? s.endToEndId : s.transactions > 1 ? `batch · ${s.transactions} payments` : s.info ?? s.servicerRef ?? ''
}

function MatchRow({ row, tz, selected, onSelect, picked, onPick }: {
  row: ReconRow
  tz: string
  selected: boolean
  onSelect: () => void
  picked: { ledger: number[]; statement: number[] }
  onPick: (side: 'ledger' | 'statement', id: number) => void
}) {
  const n = Math.max(row.ledger.length, row.statement.length, 1)
  const isBreak = row.breakId != null
  const tone: Tone = isBreak ? (row.breakStatus === 'RESOLVED' ? 'info' : row.breakType?.startsWith('MISSING') ? 'warn' : 'err') : RULE[row.rule ?? '']?.tone ?? 'ok'
  const label = isBreak ? (row.breakStatus === 'RESOLVED' ? `${row.breakId} · resolved` : `${row.breakId}`) : RULE[row.rule ?? '']?.label ?? row.rule
  const bg = selected ? 'var(--accent-bg)' : isBreak && row.breakStatus === 'OPEN' ? 'color-mix(in srgb, var(--err-bg) 55%, transparent)' : undefined
  return (
    <>
      {Array.from({ length: n }).map((_, i) => {
        const l = row.ledger[i]
        const s = row.statement[i]
        return (
          <tr key={i} className="clickable" style={{ background: bg }} onClick={onSelect}>
            <td onClick={(e) => e.stopPropagation()}>
              {isBreak && (l || s) && (
                <input
                  type="checkbox"
                  aria-label="Pick for a manual match"
                  checked={l ? picked.ledger.includes(l.postingId) : picked.statement.includes(s!.id)}
                  onChange={() => (l ? onPick('ledger', l.postingId) : onPick('statement', s!.id))}
                />
              )}
            </td>
            <td className="mono text-muted">{l ? `JE-${l.journalEntryId.slice(0, 8)}` : i === 0 ? '—' : ''}</td>
            <td className="mono text-muted">{l ? time(l.bookedAt, tz) : ''}</td>
            <td className="max-w-[180px] truncate text-xs" title={l ? ledgerRef(l) : undefined}>
              {l ? ledgerRef(l) : ''}
            </td>
            <td className="num mono">{l ? <>{abs(l.signed)} <span className="text-muted">{ledgerSide(l.signed)}</span></> : ''}</td>
            <td>{i === 0 && <Chip small tone={tone}>{label}</Chip>}</td>
            <td className="mono">{s ? `…${(s.entryRef ?? '').slice(-6)}` : i === 0 ? '—' : ''}</td>
            <td className="mono text-muted">{s ? time(s.bookedAt, tz) : ''}</td>
            <td className="max-w-[180px] truncate text-xs" title={s ? stmtRef(s) : undefined}>
              {s ? stmtRef(s) : ''}
            </td>
            <td className="num mono">{s ? <>{abs(s.signed)} <span className="text-muted">{stmtSide(s.signed)}</span></> : ''}</td>
          </tr>
        )
      })}
    </>
  )
}

function ManualMatch({ count, balanced, pending, onMatch }: { count: number; balanced: boolean; pending: boolean; onMatch: (comment: string) => void }) {
  const [comment, setComment] = useState('')
  return (
    <span className="flex items-center gap-2">
      <input className="input !h-[30px] w-56" placeholder="Why these belong together" value={comment} onChange={(e) => setComment(e.target.value)} />
      <Button primary disabled={!balanced || comment.trim().length < 10 || pending} onClick={() => onMatch(comment)} title={balanced ? '' : 'The picked items do not add up'}>
        Match {count} by hand
      </Button>
    </span>
  )
}

function EntryPanel({ row }: { row: ReconRow | undefined }) {
  const s = row?.statement[0]
  const l = row?.ledger[0]
  const explain = !row
    ? ''
    : row.breakId
      ? row.comment ?? `${BREAK[row.breakType as ReconBreak['type']]?.label ?? row.breakType}: see the break below.`
      : row.rule === 'UETR'
        ? 'Matched on UETR: the strongest key, unique per payment, the same in our books and in the servicer’s.'
        : row.rule === 'BATCH'
          ? `One statement entry for ${row.ledger.length} payments: the net settlement of a DNS cycle. Every payment it lists matched one of our lines by UETR and amount, and together they make the entry.`
          : row.rule === 'CONTRA'
            ? 'A booking and its exact opposite for the same payment, on one side only: they cancel out (sent and reversed after a rejection, or settled and returned).'
            : row.rule === 'TAKE_ON'
              ? 'The opening balances booked when the account was set up, on both sides.'
              : row.rule === 'ADJUSTMENT' || row.rule === 'MANUAL'
                ? `${row.rule === 'MANUAL' ? 'Matched by hand' : 'Adjusted'} by ${row.matchedBy}: “${row.comment}”`
                : `Matched on ${row.rule}.`
  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader>
        <div>
          <h2 className="card-t">{s ? `camt.053 entry · NtryRef ${s.entryRef}` : l ? `Ledger JE-${l.journalEntryId.slice(0, 8)} · no statement entry` : 'Select a row'}</h2>
          {row && <span className="text-xs text-muted">{explain}</span>}
        </div>
      </CardHeader>
      {s?.xml ? (
        <pre className="m-0 max-h-[460px] overflow-auto px-4 py-3 text-[11px] leading-[1.5]">
          <code className="mono">{prettyXml(s.xml)}</code>
        </pre>
      ) : (
        <div className="px-4 py-4 text-xs text-muted">
          {row && !s ? 'No statement entry exists for this ledger line: the servicer has not booked it (yet), so nothing appears in its camt.053.' : ''}
        </div>
      )}
    </Card>
  )
}

/** The entry as it is in the message, without its namespace prefix, indented as stored. */
function prettyXml(xml: string) {
  const lines = xml.replace(/<(\/?)[A-Za-z0-9_]+:/g, '<$1').split('\n')
  const indent = Math.min(...lines.slice(1).filter((l) => l.trim()).map((l) => l.match(/^\s*/)![0].length), 99)
  return [lines[0], ...lines.slice(1).map((l) => l.slice(Math.min(indent, 2)))].join('\n')
}

function Breaks({ breaks, tz, by, onError }: { breaks: ReconBreak[]; tz: string; by: string; onError: (e: unknown) => void }) {
  const resolve = useResolveBreak()
  const reopen = useReopenBreak()
  const [acting, setActing] = useState<{ id: string; action: string } | null>(null)
  const [comment, setComment] = useState('')
  const [showCleared, setShowCleared] = useState(false)
  const cleared = breaks.filter((b) => b.status === 'CLEARED').length
  const shown = breaks.filter((b) => showCleared || b.status !== 'CLEARED')
  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <div>
          <h2 className="card-t">Breaks</h2>
          <span className="text-xs text-muted">what did not match, until a later statement clears it or someone resolves it · acting as {by}</span>
        </div>
        {cleared > 0 && (
          <label className="flex items-center gap-1.5 text-xs text-muted">
            <input type="checkbox" checked={showCleared} onChange={(e) => setShowCleared(e.target.checked)} />
            Show {cleared} cleared by a later match
          </label>
        )}
      </CardHeader>
      {shown.length === 0 ? (
        <div className="px-4 py-6 text-muted">No open breaks: every line on both sides is matched or resolved.</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="tbl dense">
            <thead>
              <tr>
                <th>Break</th>
                <th>Reason</th>
                <th>Reference</th>
                <th className="!text-right">Ledger</th>
                <th className="!text-right">Statement</th>
                <th className="!text-right">Difference</th>
                <th>Age</th>
                <th>Suggested resolution</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((b) => (
                <tr key={b.id} style={{ opacity: b.status === 'OPEN' ? 1 : 0.65 }}>
                  <td className="mono">{b.id}</td>
                  <td>
                    <Chip small tone={b.status === 'OPEN' ? BREAK[b.type].tone : b.status === 'CLEARED' ? 'ok' : 'info'}>
                      {BREAK[b.type].label}
                      {b.status !== 'OPEN' ? ` · ${b.status.toLowerCase()}` : ''}
                    </Chip>
                  </td>
                  <td className="mono max-w-[180px] truncate" title={b.reference ?? undefined}>
                    {b.reference}
                  </td>
                  <td className="num mono">{b.ledger ? `${ledgerSide(b.ledger.signed)} ${abs(b.ledger.signed)}` : '—'}</td>
                  <td className="num mono">{b.statement ? `${stmtSide(b.statement.signed)} ${abs(b.statement.signed)}` : '—'}</td>
                  <td className="num mono font-semibold" style={{ color: isNegative(b.difference) ? 'var(--err-fg)' : 'var(--fg)' }}>
                    {signedText(b.difference)}
                  </td>
                  <td className="mono text-muted">{age(b.openedAt)}</td>
                  <td className="min-w-[260px] text-xs !whitespace-normal">
                    {b.status === 'OPEN' ? b.suggestion : b.status === 'CLEARED' ? 'Cleared by a match.' : `${ACTION[b.action ?? ''] ?? b.action} by ${b.resolvedBy} at ${stamp(b.resolvedAt, tz)}: “${b.comment}”`}
                    {acting?.id === b.id && (
                      <div className="mt-2 flex items-center gap-2">
                        <input className="input !h-[28px] flex-1" autoFocus placeholder="Comment (10+ characters): the audit trail" value={comment} onChange={(e) => setComment(e.target.value)} />
                        <Button
                          primary
                          className="!h-[28px]"
                          disabled={comment.trim().length < 10 || resolve.isPending}
                          onClick={() =>
                            resolve
                              .mutateAsync({ id: b.id, body: { action: acting.action, comment, by }, key: newIdempotencyKey('resolve') })
                              .then(() => (setActing(null), setComment('')))
                              .catch(onError)
                          }
                        >
                          {ACTION[acting.action]}
                        </Button>
                        <Button className="!h-[28px]" onClick={() => setActing(null)}>
                          Cancel
                        </Button>
                      </div>
                    )}
                  </td>
                  <td className="whitespace-nowrap">
                    {b.status === 'OPEN' &&
                      b.actions.map((a) => (
                        <Button key={a} className="mr-1 !h-[26px]" onClick={() => (setActing({ id: b.id, action: a }), setComment(''))}>
                          {ACTION[a]}
                        </Button>
                      ))}
                    {b.status === 'RESOLVED' && b.action !== 'ADJUSTMENT' && (
                      <Button className="!h-[26px]" disabled={reopen.isPending} onClick={() => reopen.mutateAsync({ id: b.id, by, key: newIdempotencyKey('reopen') }).catch(onError)}>
                        Undo
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}
