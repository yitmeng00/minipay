import { Fragment } from 'react'
import { useBicDirectory } from '../lib/api'
import { GPI_STATUS } from '../lib/iso'
import { formatAmount, formatMoney } from '../lib/money'
import type { Charge, Hop, Money, TrackerView } from '../lib/types'
import { Card, CardHeader, Chip, type Tone } from './ui'

const SHORT: Record<string, string> = {
  'pacs.008.001.08': 'pacs.008',
  'pacs.009.001.08': 'pacs.009 COV',
  'camt.054.001.08': 'camt.054',
  'pacs.004.001.09': 'pacs.004',
  'pacs.002.001.10': 'pacs.002',
}

const METHOD: Record<string, string> = {
  SERIAL: 'Serial method · the payment travels through each bank',
  COVER: 'Cover method · the payment goes direct, the money through a correspondent',
  DIRECT: 'Direct · settled on the account between the two banks',
}

const SETTLEMENT: Record<string, string> = {
  INDA: 'INDA · on the receiver’s books',
  INGA: 'INGA · on the sender’s books',
  COVE: 'COVE · money by cover',
  CLRG: 'CLRG · through the RTGS',
}

function sameBank(a: string | null | undefined, b: string | null | undefined) {
  return !!a && !!b && a.slice(0, 8) === b.slice(0, 8)
}

function time(iso: string | null, tz: string) {
  if (!iso) return ''
  return new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(iso))
}

function duration(iso: string | null) {
  if (!iso) return null
  const m = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(iso)
  if (!m) return iso
  const s = Number(m[1] ?? 0) * 3600 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)
  return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`
}

function replyTone(reply: string | null): Tone {
  return reply === 'RJCT' ? 'err' : reply === 'ACSC' ? 'ok' : reply ? 'info' : 'neutral'
}

/**
 * A payment's route as the network saw it (the UETR tracker): every bank, every hop with its message,
 * amount and answer, every bank's deduction, the cover legs and the return path.
 */
export function RouteCard({ view, tz, ourBic }: { view: TrackerView; tz: string; ourBic: string }) {
  const directory = useBicDirectory().data ?? []
  const name = (bic: string) =>
    directory.find((d) => d.bic === bic)?.institutionName ?? directory.find((d) => d.bic.slice(0, 8) === bic.slice(0, 8))?.institutionName ?? bic
  const banks = view.hops.length ? [view.hops[0].fromBic, ...view.hops.map((h) => h.toBic)] : []
  const deduction = (bic: string): Charge | undefined => view.deductions.find((d) => sameBank(d.agentBic, bic))
  const role = (i: number) => (i === 0 ? 'Debtor agent' : i === banks.length - 1 ? 'Creditor agent' : 'Intermediary · instructed agent')
  const returned = view.returnPath.length > 0
  const status = GPI_STATUS[view.status]
  const amount = (m: Money | null) => (m ? `${m.currency} ${formatAmount(m.amount)}` : '—')

  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="m-0 text-[13px] font-semibold">Payment route</h2>
          <span className="text-xs text-muted">
            {view.method ? METHOD[view.method] : 'Not on the network yet'} · {view.hops.length} hop{view.hops.length === 1 ? '' : 's'} · as
            seen by the UETR tracker
          </span>
        </div>
        <Chip tone={status.tone}>
          {view.status} · {status.label}
        </Chip>
      </CardHeader>
      <div className="flex flex-col gap-2.5 p-5">
        <div className="flex items-stretch overflow-x-auto">
          {banks.map((bic, i) => {
            const d = deduction(bic)
            const isUs = sameBank(bic, ourBic)
            const failed = i === banks.length - 1 && (view.status === 'RJCT' || view.status === 'RTND')
            return (
              <Fragment key={bic + i}>
                <div
                  className={`flex min-w-[170px] flex-[1_1_0] flex-col gap-1.5 rounded-lg border bg-surface2 px-3.5 py-3 ${
                    failed ? 'border-err' : isUs ? 'border-accent' : 'border-line'
                  }`}
                >
                  <div className="flex items-center justify-between gap-1.5">
                    <span className="lbl">{role(i)}</span>
                    {isUs ? (
                      <Chip tone="info" small>
                        Us
                      </Chip>
                    ) : failed ? (
                      <Chip tone="err" small>
                        {view.status === 'RTND' ? 'Returned' : 'Rejected'}
                      </Chip>
                    ) : null}
                  </div>
                  <div className="text-sm font-semibold">{name(bic)}</div>
                  <div className="mono text-muted">{bic}</div>
                  {i > 0 && view.hops[i - 1].settlementMethod && (
                    <div className="border-t border-line2 pt-2 text-[11px] text-muted">
                      Received {SETTLEMENT[view.hops[i - 1].settlementMethod!] ?? view.hops[i - 1].settlementMethod}
                    </div>
                  )}
                  <div className="mt-auto flex justify-between pt-1 text-xs">
                    <span className="text-muted">Deducted</span>
                    <span className="mono">{d ? `− ${formatMoney(d.amount, true)}` : '—'}</span>
                  </div>
                </div>
                {i < view.hops.length && <HopArrow hop={view.hops[i]} tz={tz} />}
              </Fragment>
            )
          })}
        </div>

        {view.coverLegs.length > 0 && (
          <div className="flex flex-wrap items-center gap-2.5 px-1 text-xs">
            <span className="lbl">Cover</span>
            {view.coverLegs.map((h) => (
              <span key={h.msgId} className="mono">
                {SHORT[h.type] ?? h.type} {h.fromBic} → {h.toBic} · {amount(h.amount)} · {time(h.sentAt, tz)}
              </span>
            ))}
          </div>
        )}

        {returned && (
          <div className="flex items-center gap-2.5 px-1">
            <div className="h-0 flex-1 border-t-2 border-dashed border-err" />
            <span className="text-xs whitespace-nowrap text-err-fg">
              <span className="mono font-semibold">pacs.004</span> return path ·{' '}
              {[view.returnPath[0].fromBic, ...view.returnPath.map((h) => h.toBic)].join(' → ')} ·{' '}
              <span className="mono">{amount(view.returnedAmount)}</span> · {time(view.returnPath[view.returnPath.length - 1].sentAt, tz)}
            </span>
            <div className="h-0 flex-1 border-t-2 border-dashed border-err" />
          </div>
        )}

        <div className="flex flex-wrap gap-5 border-t border-line2 pt-1.5 text-xs text-muted">
          <span>
            Sent <b className="mono font-medium text-fg">{amount(view.sentAmount)}</b>
          </span>
          {view.deductions.map((d) => (
            <span key={d.agentBic}>
              {name(d.agentBic)} deduction <b className="mono font-medium text-fg">−{formatAmount(d.amount.amount)}</b>
            </span>
          ))}
          <span>
            {view.status === 'ACSC' ? 'Credited' : view.status === 'RTND' ? 'Credited before the return' : 'Credited'}{' '}
            <b className="mono font-medium text-fg">{amount(view.creditedAmount)}</b>
          </span>
          {view.endToEnd && (
            <span>
              End to end <b className="mono font-medium text-fg">{duration(view.endToEnd)}</b>
            </span>
          )}
        </div>
      </div>
    </Card>
  )
}

function HopArrow({ hop, tz }: { hop: Hop; tz: string }) {
  return (
    <div className="flex flex-[0_0_150px] flex-col items-center justify-center gap-1 px-1.5">
      <span className="mono text-[11px] font-semibold">
        {SHORT[hop.type] ?? hop.type} · hop {hop.number}
      </span>
      <div className={`relative h-0.5 w-full ${hop.reply === 'RJCT' ? 'bg-err' : 'bg-info'}`}>
        <span
          className={`absolute -top-[5px] -right-0.5 h-0 w-0 border-y-[6px] border-l-8 border-y-transparent ${
            hop.reply === 'RJCT' ? 'border-l-err' : 'border-l-info'
          }`}
        />
      </div>
      <span className="mono text-[11px]">{hop.amount ? `${hop.amount.currency} ${formatAmount(hop.amount.amount)}` : ''}</span>
      <span className="text-[11px] text-muted">
        {time(hop.sentAt, tz)}
        {hop.reply && (
          <>
            {' → '}
            <Chip tone={replyTone(hop.reply)} small>
              {hop.reply}
              {hop.replyReason ? ` ${hop.replyReason}` : ''}
            </Chip>{' '}
            {time(hop.replyAt, tz)}
          </>
        )}
      </span>
    </div>
  )
}
