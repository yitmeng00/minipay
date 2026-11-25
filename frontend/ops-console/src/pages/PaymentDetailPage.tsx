import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Button, Card, CardHeader, Chip, Tabs, type Tone } from '../components/ui'
import { ApiError, useAccounts, useBankInfo, useBicDirectory, useNetworkEvent, usePayment, usePaymentEntries } from '../lib/api'
import { BANKS } from '../lib/banks'
import { CHARGE_BEARER, REASON, STATUS_TONE, formatIban, reasonText } from '../lib/iso'
import { formatAmount, formatMoney } from '../lib/money'
import type { JournalEntry, Payment, PaymentStatus, StateChange } from '../lib/types'
import { shortUetr } from './PaymentsPage'

// ---------------------------------------------------------------- time helpers

function clock(iso: string, tz: string) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3,
    hour12: false,
  }).format(new Date(iso))
}

function delta(ms: number) {
  if (ms < 1000) return `+${Math.max(0, Math.round(ms))} ms`
  if (ms < 60_000) return `+${(ms / 1000).toFixed(3)} s`
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  return `+${h ? h + 'h ' : ''}${m}m ${s % 60}s`
}

function tzLabel(tz: string) {
  return tz.split('/').pop()?.replace('_', ' ') ?? tz
}

function bankName(bic: string | null) {
  if (!bic) return undefined
  return BANKS.find((b) => b.bic.slice(0, 8) === bic.slice(0, 8))
}

// ---------------------------------------------------------------- lifecycle

const MAIN_PATH: PaymentStatus[] = ['RECEIVED', 'VALIDATED', 'SCREENED', 'FUNDS_RESERVED', 'SENT', 'ACKED', 'SETTLED']

function Lifecycle({ history, tz }: { history: StateChange[]; tz: string }) {
  const at = new Map(history.map((h) => [h.to, h]))
  const start = history[0] ? new Date(history[0].occurredAt).getTime() : 0
  const branch = history.find((h) => h.to === 'REJECTED' || h.to === 'RETURNED' || h.to === 'HELD_COMPLIANCE')
  const branchCol = branch?.from ? Math.min(MAIN_PATH.indexOf(branch.from), 5) : 0
  const branchTone: Tone = branch?.to === 'HELD_COMPLIANCE' ? 'warn' : 'err'
  const last = history[history.length - 1]
  const total = last ? new Date(last.occurredAt).getTime() - start : 0

  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="m-0 text-[13px] font-semibold">Lifecycle</h2>
          <span className="text-xs text-muted">
            Times in {tzLabel(tz)} · {history.length} state changes{total > 0 ? ` · elapsed ${delta(total).slice(1)}` : ''}
          </span>
        </div>
        <div className="flex gap-3.5 text-xs text-muted">
          <span className="inline-flex items-center gap-1.5">
            <span className="node ok !size-3" />
            Completed
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="node skip !size-3" />
            Not reached
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="node err !size-3" />
            Failure branch
          </span>
        </div>
      </CardHeader>
      <div className="overflow-x-auto px-2 py-5">
        <ol className="m-0 grid min-w-[760px] list-none grid-cols-7 p-0">
          {MAIN_PATH.map((s, i) => {
            const h = at.get(s)
            const prev = i > 0 ? at.get(MAIN_PATH[i - 1]) : undefined
            const nextReached = i < MAIN_PATH.length - 1 && at.has(MAIN_PATH[i + 1])
            return (
              <li key={s} className="flex flex-col items-center gap-[3px] px-1.5 text-center">
                <div className="relative flex h-6 w-full justify-center">
                  {i < MAIN_PATH.length - 1 && <div className={`line ${nextReached ? '' : 'dashed'}`} />}
                  <span className={`node ${h ? 'ok' : 'skip'}`}>{h ? '✓' : ''}</span>
                </div>
                <span className={`mono mt-1.5 text-[11px] font-semibold ${h ? 'text-fg' : 'text-muted'}`}>{s}</span>
                <span className="mono text-[11px]">{h ? clock(h.occurredAt, tz) : '—'}</span>
                <span className="text-[11px] text-muted">
                  {h ? (i === 0 ? 'start' : prev ? delta(new Date(h.occurredAt).getTime() - new Date(prev.occurredAt).getTime()) : '') : 'not reached'}
                </span>
                {h?.detail && <span className="text-[11px] leading-[1.35] text-muted">{h.detail}</span>}
              </li>
            )
          })}
        </ol>
        {branch && (
          <div className="grid min-w-[760px] grid-cols-7">
            <div
              style={{ gridColumn: `${branchCol + 1} / span 2` }}
              className={`mx-1.5 mt-3 flex flex-col items-center gap-[3px] rounded-lg px-2.5 py-2 text-center ${
                branchTone === 'err' ? 'bg-err-bg' : 'bg-warn-bg'
              }`}
            >
              <span className={`mono text-[11px] font-semibold ${branchTone === 'err' ? 'text-err-fg' : 'text-warn-fg'}`}>
                {branch.to} {branch.from && <span className="font-normal text-muted">from {branch.from}</span>}
              </span>
              <span className="mono text-[11px]">{clock(branch.occurredAt, tz)}</span>
              <span className="text-[11px] text-muted">{delta(new Date(branch.occurredAt).getTime() - start)} from start</span>
              {branch.reasonCode && <span className="text-[11px] font-medium">{reasonText(branch.reasonCode)}</span>}
              {branch.detail && <span className="text-[11px] leading-[1.35]">{branch.detail}</span>}
            </div>
          </div>
        )}
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- network simulation (until M5)

const RETURN_REASONS = ['AC04', 'AC01', 'AC06', 'BE04', 'MD07', 'CUST', 'DUPL']

function SimulateNetwork({ p }: { p: Payment }) {
  const ev = useNetworkEvent(p.uetr)
  const [reason, setReason] = useState('AC04')
  const [amount, setAmount] = useState(p.interbankSettlementAmount?.amount ?? '')
  const next = p.status === 'SENT' ? 'ACCEPTED' : p.status === 'ACKED' ? 'SETTLED' : p.status === 'SETTLED' ? 'RETURNED' : null
  // One key per (payment, event type): a double click or a retry replays instead of applying twice.
  const send = (body: Parameters<typeof ev.mutate>[0]['body']) => ev.mutate({ body, key: `sim-${p.uetr}-${body.type}` })

  return (
    <div className="flex shrink-0 flex-col items-end gap-2">
      <span className="lbl">Simulate network · until M5</span>
      {next === null ? (
        <span className="text-xs text-muted">
          {p.status === 'FUNDS_RESERVED' ? 'Warehoused — sent on its value date' : 'No network event applies in this state'}
        </span>
      ) : next === 'RETURNED' ? (
        <div className="flex flex-wrap items-center justify-end gap-2">
          <select className="sel mono" aria-label="Return reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            {RETURN_REASONS.map((r) => (
              <option key={r} value={r}>
                {r} · {REASON[r]}
              </option>
            ))}
          </select>
          <input
            className="input mono w-[130px] text-right"
            aria-label="Returned amount"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
          <Button
            disabled={ev.isPending}
            onClick={() => send({ type: 'RETURNED', reasonCode: reason, returnedAmount: { amount, currency: p.amount.currency } })}
          >
            pacs.004 return
          </Button>
        </div>
      ) : (
        <Button primary disabled={ev.isPending} onClick={() => send({ type: next })}>
          {next === 'ACCEPTED' ? `pacs.002 ACCP from ${p.instructedAgentBic}` : 'Settlement confirmed'}
        </Button>
      )}
      {ev.error && <span className="text-xs text-err-fg">{(ev.error as ApiError).message}</span>}
      <span className="text-[11px] text-muted">The network simulator sends these messages from M5</span>
    </div>
  )
}

// ---------------------------------------------------------------- tabs

function Parties({ p }: { p: Payment }) {
  // Names come from the BIC directory as of the value date; the three simulated banks are the fallback.
  const directory = useBicDirectory(p.valueDate ?? p.executionDate).data ?? []
  const named = (bic: string | null) => {
    if (!bic) return undefined
    const d = directory.find((e) => e.bic === bic) ?? directory.find((e) => e.bic.slice(0, 8) === bic.slice(0, 8))
    return d ? { name: d.institutionName, city: d.city } : bankName(bic)
  }
  const us = named(p.debtorAgentBic)
  const cAgent = named(p.creditorAgentBic)
  const instructed = p.instructedAgentBic && p.instructedAgentBic.slice(0, 8) !== p.creditorAgentBic.slice(0, 8)
    ? named(p.instructedAgentBic)
    : undefined
  const cards: { role: string; tag: string; name: string; rows: [string, string, boolean?][] }[] = [
    { role: 'Debtor', tag: 'Dbtr', name: p.debtorName, rows: [['Account', p.debtorAccount, true]] },
    {
      role: 'Debtor agent',
      tag: 'DbtrAgt',
      name: us ? `${us.name} · ${us.city}` : p.debtorAgentBic,
      rows: [['BIC', p.debtorAgentBic, true], ['Role', 'Us — sending bank']],
    },
    ...(p.instructedAgentBic && instructed
      ? [{
          role: 'Intermediary · instructed agent',
          tag: 'InstdAgt',
          name: `${instructed.name} · ${instructed.city}`,
          rows: [
            ['BIC', p.instructedAgentBic, true],
            ['Relationship', `Our correspondent · routed by ${p.routingRuleId ?? '—'}`],
          ] as [string, string, boolean?][],
        }]
      : []),
    {
      role: 'Creditor agent',
      tag: 'CdtrAgt',
      name: cAgent ? `${cAgent.name} · ${cAgent.city}` : p.creditorAgentBic,
      rows: [['BIC', p.creditorAgentBic, true]],
    },
    {
      role: 'Creditor',
      tag: 'Cdtr',
      name: p.creditorName,
      rows: [[/^[A-Z]{2}\d{2}/.test(p.creditorAccount) ? 'IBAN' : 'Account', formatIban(p.creditorAccount), true]],
    },
    {
      role: 'Remittance',
      tag: 'RmtInf',
      name: 'Unstructured',
      rows: [['Ustrd', p.remittanceInformation ?? '—', true], ['End-to-end', p.endToEndId, true]],
    },
  ]
  return (
    <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-3">
      {cards.map((c) => (
        <div key={c.role} className="flex flex-col gap-2 rounded-lg border border-line2 px-3.5 py-3">
          <div className="flex items-center justify-between">
            <span className="lbl">{c.role}</span>
            <span className="mono text-[11px] text-muted">{c.tag}</span>
          </div>
          <div className="font-semibold">{c.name}</div>
          <dl className="m-0 grid grid-cols-[96px_minmax(0,1fr)] gap-x-2.5 gap-y-1 text-xs">
            {c.rows.map(([k, v, mono]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className={`m-0 [overflow-wrap:anywhere] ${mono ? 'mono' : ''}`}>{v}</dd>
              </div>
            ))}
          </dl>
        </div>
      ))}
    </div>
  )
}

function LedgerPostings({ p, entries, tz }: { p: Payment; entries: JournalEntry[]; tz: string }) {
  const accounts = useAccounts().data ?? []
  const nameOf = (id: number) => accounts.find((a) => a.id === id)?.ownerName ?? ''
  let dr = 0n
  let cr = 0n
  const cents = (s: string) => BigInt(s.replace('.', '').replace('-', ''))
  const net = new Map<string, bigint>()
  for (const e of entries) {
    for (const x of e.postings) {
      if (x.direction === 'D') dr += cents(x.amount.amount)
      else cr += cents(x.amount.amount)
      if (x.balanceType === 'BOOKED') {
        // Signed in the account's normal direction, as balances are: an asset (nostro) grows with
        // debits, a liability (customer) or income account with credits. "−" always means "less".
        const debitNormal = ['NOSTRO', 'SUSPENSE'].includes(accounts.find((a) => a.id === x.accountId)?.type ?? '')
        const growsWith = debitNormal ? 'D' : 'C'
        const signed = x.direction === growsWith ? cents(x.amount.amount) : -cents(x.amount.amount)
        net.set(x.accountNumber, (net.get(x.accountNumber) ?? 0n) + signed)
      }
    }
  }
  const scale = p.amount.amount.split('.')[1]?.length ?? 0
  const fmtCents = (v: bigint) => {
    const neg = v < 0n
    const s = (neg ? -v : v).toString().padStart(scale + 1, '0')
    const str = scale ? `${s.slice(0, -scale)}.${s.slice(-scale)}` : s
    return (neg ? '−' : '+') + formatAmount(str)
  }
  if (entries.length === 0) {
    return <div className="px-4 py-7 text-center text-muted">No ledger entries for this payment.</div>
  }
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 pt-3">
        <span className="text-xs text-muted">
          {bankName(p.debtorAgentBic)?.name ?? p.debtorAgentBic} books · hold and release move the RESERVED balance;
          bookings move BOOKED
        </span>
        <Chip tone={dr === cr ? 'ok' : 'err'}>{dr === cr ? 'Balanced · Dr = Cr' : 'Unbalanced'}</Chip>
      </div>
      <div className="overflow-x-auto pt-2">
        <table className="tbl">
          <thead>
            <tr>
              <th>Journal</th>
              <th>Booked ({tzLabel(tz)})</th>
              <th>Event</th>
              <th>Balance</th>
              <th>Account</th>
              <th>Account name</th>
              <th style={{ textAlign: 'right' }}>Debit {p.amount.currency}</th>
              <th style={{ textAlign: 'right' }}>Credit {p.amount.currency}</th>
            </tr>
          </thead>
          <tbody>
            {entries.flatMap((e) =>
              e.postings.map((x, i) => (
                <tr key={x.id}>
                  <td className={`mono ${e.entryType === 'REVERSAL' ? 'text-err-fg' : ''}`} title={e.id}>
                    {i === 0 ? shortUetr(e.id) : ''}
                  </td>
                  <td className="mono text-muted">{i === 0 ? clock(e.createdAt, tz) : ''}</td>
                  <td>
                    <span className="mono text-[11px]">{i === 0 ? e.entryType : ''}</span>
                  </td>
                  <td className="text-[11px] text-muted">{x.balanceType}</td>
                  <td className="mono">
                    <Link to={`/accounts?account=${x.accountNumber}`} className="text-accent no-underline">
                      {x.accountNumber}
                    </Link>
                  </td>
                  <td>{nameOf(x.accountId)}</td>
                  <td className="num mono">{x.direction === 'D' ? formatAmount(x.amount.amount) : ''}</td>
                  <td className="num mono">{x.direction === 'C' ? formatAmount(x.amount.amount) : ''}</td>
                </tr>
              )),
            )}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap gap-6 px-4 pt-2.5 pb-4 text-xs text-muted">
        <span>Net booked per account →</span>
        {[...net.entries()].map(([acct, v]) => (
          <span key={acct}>
            <span className="mono">{acct}</span>{' '}
            <b className={`mono font-medium ${v < 0n ? 'text-err-fg' : 'text-ok-fg'}`}>{fmtCents(v)}</b>
          </span>
        ))}
      </div>
    </>
  )
}

function AuditLog({ history, entries, tz }: { history: StateChange[]; entries: JournalEntry[]; tz: string }) {
  type Row = { t: string; actor: string; action: string; tone: Tone; detail: string }
  const rows: Row[] = [
    ...history.map((h) => ({
      t: h.occurredAt,
      actor: h.actor,
      action: h.to,
      tone: STATUS_TONE[h.to],
      detail: [h.reasonCode ? reasonText(h.reasonCode) : '', h.detail ?? ''].filter(Boolean).join(' · '),
    })),
    ...entries.map((e) => ({
      t: e.createdAt,
      actor: 'svc/ledger',
      action: e.entryType === 'RESERVE' ? 'HOLD' : e.entryType === 'RELEASE' ? 'RELEASED' : 'POSTED',
      tone: (e.entryType === 'RESERVE' || e.entryType === 'RELEASE' ? 'warn' : 'ok') as Tone,
      detail: `${e.entryType} ${shortUetr(e.id)} · ${e.postings
        .filter((x) => x.direction === 'D')
        .map((x) => `${formatMoney(x.amount, true)} ${x.accountNumber} → ${e.postings.find((y) => y.direction === 'C')?.accountNumber}`)
        .join('; ')}${e.narrative ? ' · ' + e.narrative : ''}`,
    })),
  ].sort((a, b) => a.t.localeCompare(b.t))
  return (
    <div className="overflow-x-auto">
      <table className="tbl">
        <thead>
          <tr>
            <th>Time ({tzLabel(tz)})</th>
            <th>Actor</th>
            <th>Action</th>
            <th>Detail</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td className="mono text-muted">{clock(r.t, tz)}</td>
              <td className="mono">{r.actor}</td>
              <td>
                <Chip small tone={r.tone}>
                  {r.action}
                </Chip>
              </td>
              <td className="!whitespace-normal">{r.detail}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ---------------------------------------------------------------- page

type Tab = 'parties' | 'ledger' | 'messages' | 'audit'

export function PaymentDetailPage() {
  const { ref } = useParams()
  const { data, isLoading, error } = usePayment(ref)
  const entries = usePaymentEntries(data?.payment.id).data ?? []
  const tz = useBankInfo().data?.timeZone ?? 'UTC'
  // The open tab lives in the URL (?tab=ledger) so it can be linked to.
  const [params, setParams] = useSearchParams()
  const tab = (['parties', 'ledger', 'audit'].includes(params.get('tab') ?? '') ? params.get('tab') : 'parties') as Tab
  const setTab = (t: Tab) => setParams({ tab: t }, { replace: true })
  const [copied, setCopied] = useState(false)

  if (isLoading) return <div className="text-muted">Loading payment…</div>
  if (error || !data) {
    return (
      <Card className="p-6 text-center">
        <p className="m-0 text-err-fg">{(error as ApiError | null)?.message ?? 'Payment not found'}</p>
        <Link to="/payments" className="text-accent">
          Back to payments
        </Link>
      </Card>
    )
  }
  const p = data.payment
  const final = data.history[data.history.length - 1]
  const outbound = p.direction === 'OUTGOING'

  return (
    <>
      <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 text-xs text-muted">
        <Link to="/payments" className="text-accent no-underline">
          Payments
        </Link>
        <span>/</span>
        <span className="mono text-fg">{shortUetr(p.uetr)}</span>
      </nav>

      <Card className="flex flex-col gap-4 px-5 py-[18px]">
        <div className="flex flex-wrap items-start justify-between gap-4 lg:flex-nowrap">
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className={`dir ${outbound ? 'out' : 'in'}`} aria-hidden="true">
                {outbound ? '↗' : '↙'}
              </span>
              <span className="text-xs text-muted">
                {outbound ? 'Outbound' : 'Inbound'} customer credit transfer · pacs.008.001.08 · CBPR+ serial
              </span>
            </div>
            <div className="flex flex-wrap items-baseline gap-3">
              <span className="text-[32px] font-semibold tracking-[-0.02em] tabular-nums">{formatAmount(p.amount.amount)}</span>
              <span className="mono text-lg text-muted">{p.amount.currency}</span>
              <Chip tone={STATUS_TONE[p.status]}>{p.status}</Chip>
              {p.rejectionReason && (
                <span className="text-xs text-err-fg">
                  {reasonText(p.rejectionReason)}
                  {final?.detail ? ` · ${final.detail}` : ''}
                </span>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="lbl">UETR</span>
              <span className="mono rounded-[5px] border border-line2 bg-surface2 px-2 py-[3px] text-[13px]">{p.uetr}</span>
              <Button
                className="!h-[26px] !px-2 text-xs"
                aria-label="Copy UETR"
                onClick={() => {
                  void navigator.clipboard?.writeText(p.uetr)
                  setCopied(true)
                  setTimeout(() => setCopied(false), 1500)
                }}
              >
                {copied ? 'Copied' : 'Copy'}
              </Button>
            </div>
          </div>
          {outbound && <SimulateNetwork key={p.status} p={p} />}
        </div>
        <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-3 border-t border-line2 pt-3.5 md:grid-cols-6">
          <div>
            <dt className="lbl">End-to-end ID</dt>
            <dd className="mono mt-[3px] mb-0 ml-0">{p.endToEndId}</dd>
          </div>
          <div>
            <dt className="lbl">Instruction ID</dt>
            <dd className="mono mt-[3px] mb-0 ml-0">{p.instructionId}</dd>
          </div>
          <div>
            <dt className="lbl">Value date</dt>
            <dd className="mono mt-[3px] mb-0 ml-0">
              {p.valueDate ?? <span className="text-muted">executes {p.executionDate}</span>}
              {p.executionDate !== p.requestedExecutionDate && (
                <span className="text-muted" title="The requested date was not a business day of the currency's calendar">
                  {' '}
                  · requested {p.requestedExecutionDate}
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt className="lbl">Charge bearer</dt>
            <dd className="mt-[3px] mb-0 ml-0" title={CHARGE_BEARER[p.chargeBearer].long}>
              <span className="mono">{p.chargeBearer}</span> <span className="text-muted">· {CHARGE_BEARER[p.chargeBearer].short.toLowerCase()}</span>
            </dd>
          </div>
          <div>
            <dt className="lbl">Next agent</dt>
            <dd className="mono mt-[3px] mb-0 ml-0">
              {p.instructedAgentBic ?? <span className="text-muted">— not routed</span>}
              {p.routingRuleId && (
                <Link to="/reference-data#routing" className="text-muted no-underline" title="Routing rule that chose this route">
                  {' '}
                  · {p.routingRuleId}
                </Link>
              )}
            </dd>
          </div>
          <div>
            <dt className="lbl">Our charges · interbank</dt>
            <dd className="mono mt-[3px] mb-0 ml-0">
              {formatMoney(p.chargesAmount, true)}
              {p.interbankSettlementAmount && <span className="text-muted"> · {formatAmount(p.interbankSettlementAmount.amount)}</span>}
            </dd>
          </div>
        </dl>
      </Card>

      <Lifecycle history={data.history} tz={tz} />

      <Card>
        <Tabs<Tab>
          label="Payment detail sections"
          className="px-3"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'parties', label: 'Parties', count: p.instructedAgentBic && p.instructedAgentBic !== p.creditorAgentBic ? 6 : 5 },
            { id: 'ledger', label: 'Ledger postings', count: entries.reduce((n, e) => n + e.postings.length, 0) },
            { id: 'messages', label: 'Messages', count: 'M4', disabled: true, title: 'pacs.008 / pacs.002 / pacs.004 arrive with messaging (M4)' },
            { id: 'audit', label: 'Audit log', count: data.history.length + entries.length },
          ]}
        />
        {tab === 'parties' && <Parties p={p} />}
        {tab === 'ledger' && <LedgerPostings p={p} entries={entries} tz={tz} />}
        {tab === 'audit' && <AuditLog history={data.history} entries={entries} tz={tz} />}
      </Card>
    </>
  )
}
