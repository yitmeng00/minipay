import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { RouteCard } from '../components/RouteCard'
import { Card, CardHeader, Chip } from '../components/ui'
import { useBankInfo, useTracker, useTrackerRecent } from '../lib/api'
import { useBank } from '../lib/banks'
import { GPI_STATUS } from '../lib/iso'
import { formatAmount } from '../lib/money'
import { shortUetr } from './PaymentsPage'

const UETR_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function time(iso: string, tz: string) {
  return new Intl.DateTimeFormat('en-GB', { timeZone: tz, day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(
    new Date(iso),
  )
}

function Tracked({ uetr, tz }: { uetr: string; tz: string }) {
  const { bank } = useBank()
  const { data, isLoading } = useTracker(uetr)
  if (isLoading) return <div className="text-muted">Looking for {uetr} on the network…</div>
  if (!data) return <Card className="p-6 text-center text-muted">The network has not carried a message for this UETR (yet).</Card>
  return (
    <>
      <RouteCard view={data} tz={tz} ourBic={bank.bic} />
      <Card className="overflow-hidden">
        <CardHeader>
          <div>
            <h2 className="m-0 text-[13px] font-semibold">Every message on the network</h2>
            <span className="text-xs text-muted">
              {data.messages.length} messages, in the order the network carried them · times in {tz}
            </span>
          </div>
          <Link to={`/payments/${uetr}`} className="text-xs font-medium text-accent no-underline">
            This bank’s record →
          </Link>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="tbl">
            <thead>
              <tr>
                <th>Time</th>
                <th>Message</th>
                <th>From</th>
                <th>To</th>
                <th>MsgId</th>
                <th>Refers to</th>
                <th className="!text-right">Amount</th>
                <th>Status</th>
                <th>Deductions listed</th>
              </tr>
            </thead>
            <tbody>
              {data.messages.map((m) => (
                <tr key={m.eventId}>
                  <td className="mono text-muted">{time(m.sentAt, tz)}</td>
                  <td className="mono font-medium">{m.type}</td>
                  <td className="mono">{m.fromBic}</td>
                  <td className="mono">{m.toBic}</td>
                  <td className="mono text-muted">{m.msgId}</td>
                  <td className="mono text-muted">{m.originalMsgId ?? '—'}</td>
                  <td className="num mono">{m.amount ? `${m.amount.currency} ${formatAmount(m.amount.amount)}` : ''}</td>
                  <td>
                    {m.status && (
                      <Chip tone={m.status === 'RJCT' || m.status === 'RTND' ? 'err' : m.status === 'ACSC' || m.status === 'CRDT' ? 'ok' : 'info'} small>
                        {m.status}
                        {m.reasonCode ? ` ${m.reasonCode}` : ''}
                      </Chip>
                    )}
                    {m.settlementMethod && <span className="mono ml-1 text-[11px] text-muted">{m.settlementMethod}</span>}
                  </td>
                  <td className="mono text-[11px] text-muted">
                    {m.charges.map((c) => `${c.agentBic.slice(0, 8)} −${formatAmount(c.amount.amount)}`).join(' · ') || '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  )
}

/**
 * The UETR tracker: the network's end-to-end view of payments (what SWIFT's gpi Tracker gives every bank
 * on the route). Every bank instance keeps its own copy, built from all the traffic on the network.
 */
export function TrackerPage() {
  const { uetr } = useParams()
  const navigate = useNavigate()
  const tz = useBankInfo().data?.timeZone ?? 'UTC'
  const recent = useTrackerRecent().data ?? []
  const [q, setQ] = useState(uetr ?? '')
  const valid = UETR_RE.test(q.trim().toLowerCase())

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">UETR tracker</h1>
          <p className="mt-0.5 mb-0 text-muted">
            Every message the network carries, by UETR: each hop, each bank’s deduction, the confirmations, cover legs and returns
          </p>
        </div>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (valid) navigate(`/tracker/${q.trim().toLowerCase()}`)
          }}
        >
          <input
            className={`input mono w-[340px] ${q && !valid ? 'bad' : ''}`}
            placeholder="UETR, e.g. eb6305c9-1f7f-49de-aed0-16487c27b42d"
            aria-label="UETR"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <button type="submit" className="h-8 cursor-pointer rounded-md border border-accent bg-accent px-3 font-medium text-on-accent disabled:opacity-50" disabled={!valid}>
            Track
          </button>
        </form>
      </div>
      {uetr && <Tracked uetr={uetr} tz={tz} />}
      <Card className="overflow-hidden">
        <CardHeader>
          <div>
            <h2 className="m-0 text-[13px] font-semibold">Recently on the network</h2>
            <span className="text-xs text-muted">All banks’ payments, not only ours: the tracker sees the whole network</span>
          </div>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="tbl">
            <thead>
              <tr>
                <th>UETR</th>
                <th>Debtor agent</th>
                <th>Creditor agent</th>
                <th className="!text-right">Sent</th>
                <th>Status</th>
                <th className="!text-right">Messages</th>
                <th>Last update</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((r) => (
                <tr key={r.uetr} className={`clickable ${r.uetr === uetr ? 'selected' : ''}`} onClick={() => navigate(`/tracker/${r.uetr}`)}>
                  <td className="mono text-accent">{shortUetr(r.uetr)}</td>
                  <td className="mono">{r.debtorAgentBic ?? '—'}</td>
                  <td className="mono">{r.creditorAgentBic ?? '—'}</td>
                  <td className="num mono">{r.sentAmount ? `${r.sentAmount.currency} ${formatAmount(r.sentAmount.amount)}` : ''}</td>
                  <td>
                    <Chip tone={GPI_STATUS[r.status].tone}>
                      {r.status} · {GPI_STATUS[r.status].label}
                    </Chip>
                  </td>
                  <td className="num">{r.messages}</td>
                  <td className="mono text-muted">{time(r.lastUpdate, tz)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {recent.length === 0 && <div className="p-7 text-center text-muted">Nothing on the network yet.</div>}
        </div>
      </Card>
    </>
  )
}
