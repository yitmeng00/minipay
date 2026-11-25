import { useState } from 'react'
import { messageDownloadUrl, useMessage, useMessages, useMt103 } from '../lib/api'
import { useBank } from '../lib/banks'
import type { Fidelity, MessageType, MessageView } from '../lib/types'
import { Code } from './Code'
import { Button, Chip, Segmented, type Tone } from './ui'

const NAME: Record<MessageType, { short: string; full: string; desc: string }> = {
  PACS_008: { short: 'pacs.008', full: 'pacs.008.001.08', desc: 'FI to FI customer credit transfer' },
  PACS_002: { short: 'pacs.002', full: 'pacs.002.001.10', desc: 'Payment status report' },
  PACS_004: { short: 'pacs.004', full: 'pacs.004.001.09', desc: 'Payment return' },
  CAMT_053: { short: 'camt.053', full: 'camt.053.001.08', desc: 'Bank to customer statement' },
  MT103: { short: 'MT103', full: 'MT103', desc: 'Single customer credit transfer' },
}

const FIDELITY: Record<Fidelity, { label: string; tone: Tone }> = {
  EQUIVALENT: { label: 'Equivalent', tone: 'ok' },
  TRANSFORMED: { label: 'Transformed', tone: 'info' },
  IMPLICIT: { label: 'Implicit', tone: 'info' },
  TRUNCATED: { label: 'Truncated', tone: 'warn' },
  LOST: { label: 'No MT field', tone: 'err' },
}

/** The chip on a message: what it says about the payment. */
function statusOf(m: MessageView): { label: string; tone: Tone } {
  if (m.status === 'INVALID') return { label: 'INVALID', tone: 'err' }
  if (m.type === 'PACS_008') return { label: m.direction === 'OUTBOUND' ? 'SENT' : 'RCVD', tone: 'info' }
  if (m.type === 'PACS_004') return { label: 'RTND', tone: 'err' }
  if (m.status === 'RJCT') return { label: 'RJCT', tone: 'err' }
  return { label: m.status ?? '—', tone: 'ok' }
}

function counterpart(m: MessageView) {
  return m.direction === 'OUTBOUND' ? `→ Out to ${m.receiverBic ?? 'channel'}` : `← In from ${m.senderBic ?? 'unknown'}`
}

function describe(m: MessageView) {
  if (!m.type) return 'Unreadable inbound message'
  const base = NAME[m.type].desc
  if (m.type === 'PACS_002') return `${base} · ${m.status} from ${m.senderBic}`
  if (m.type === 'PACS_004') return `${base} · ${m.reasonCode} · ${m.amount ? `${m.amount.currency} ${m.amount.amount}` : ''} returned`
  return `${base} · ${m.direction === 'OUTBOUND' ? `sent to ${m.receiverBic}` : `from ${m.senderBic}`}`
}

function Mt103SideBySide({ id }: { id: string }) {
  const t = useMt103(id).data
  if (!t) return <div className="p-4 text-muted">Translating…</div>
  return (
    <div className="flex-1 overflow-auto">
      <table className="tbl table-fixed">
        <colgroup>
          <col className="w-[70px]" />
          <col className="w-[30%]" />
          <col className="w-[34px]" />
          <col className="w-[24%]" />
          <col />
          <col className="w-[150px]" />
        </colgroup>
        <thead>
          <tr>
            <th>MT tag</th>
            <th>MT103 value</th>
            <th aria-label="maps to" />
            <th>pacs.008 element</th>
            <th>pacs.008 value</th>
            <th>Mapping</th>
          </tr>
        </thead>
        <tbody>
          {t.fields.map((f, i) => (
            <tr key={i}>
              <td className="mono font-medium text-[var(--x-tag)]">{f.mtTag}</td>
              <td className="mono !whitespace-pre-wrap [overflow-wrap:anywhere]">{f.mtValue || '—'}</td>
              <td className="text-center text-muted">→</td>
              <td className="mono !whitespace-normal text-[11px] [overflow-wrap:anywhere] text-[var(--x-attr)]">{f.mxPath}</td>
              <td className="mono !whitespace-normal [overflow-wrap:anywhere]">{f.mxValue}</td>
              <td className="!whitespace-normal">
                <Chip tone={FIDELITY[f.fidelity].tone} small>
                  {FIDELITY[f.fidelity].label}
                </Chip>
                {f.note && <div className="mt-[3px] text-[11px] text-muted">{f.note}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <details className="border-t border-line2">
        <summary className="cursor-pointer px-3.5 py-2 text-xs text-muted">The MT103 as FIN text</summary>
        <Code text={t.target} xml={false} />
      </details>
    </div>
  )
}

/**
 * Every message exchanged about one payment, oldest first, each exactly as stored. A pacs.008 can be
 * shown side by side with the MT103 it translates to, with what the translation keeps, changes or loses.
 */
export function MessagesPanel({ uetr, tz }: { uetr: string; tz: string }) {
  const { bank } = useBank()
  const messages = useMessages(uetr).data ?? []
  const [selectedId, setSelectedId] = useState<string>()
  const [view, setView] = useState<'xml' | 'mt'>('xml')
  const [copied, setCopied] = useState(false)
  const selected = messages.find((m) => m.id === selectedId) ?? messages[0]
  const detail = useMessage(selected?.id).data
  const canMt = selected?.type === 'PACS_008' && selected.errors.length === 0
  const showMt = canMt && view === 'mt'

  if (messages.length === 0) {
    return <div className="p-7 text-center text-muted">No messages yet: the pacs.008 is issued when the payment is sent.</div>
  }
  const time = (iso: string) =>
    new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(iso))

  return (
    <div className="flex min-h-[520px] flex-wrap">
      <div role="listbox" aria-label="Messages" className="flex flex-[0_0_280px] flex-col gap-1 border-r border-line2 p-2.5">
        {messages.map((m) => {
          const s = statusOf(m)
          const on = m.id === selected?.id
          return (
            <button key={m.id} role="option" aria-selected={on} className={`msg ${on ? 'on' : ''}`} onClick={() => setSelectedId(m.id)}>
              <div className="flex w-full items-center justify-between">
                <span className="mono font-semibold">{m.type ? NAME[m.type].short : 'unknown'}</span>
                <Chip tone={s.tone} small>
                  {s.label}
                </Chip>
              </div>
              <div className="flex w-full justify-between text-[11px] text-muted">
                <span>{counterpart(m)}</span>
                <span className="mono">{time(m.createdAt)}</span>
              </div>
              <div className="mono text-[11px] text-muted">{m.msgId ?? '—'}</div>
            </button>
          )
        })}
      </div>
      {selected && (
        <div className="flex min-w-0 flex-[1_1_480px] flex-col">
          <div className="flex flex-wrap items-center gap-2.5 border-b border-line2 px-3.5 py-2.5">
            <span className="mono font-semibold">{selected.type ? NAME[selected.type].full : 'Unreadable'}</span>
            <span className="text-xs text-muted">{describe(selected)}</span>
            <div className="ml-auto flex gap-2">
              {canMt && (
                <Segmented<'xml' | 'mt'>
                  label="View"
                  value={view}
                  onChange={setView}
                  options={[
                    { value: 'xml', label: 'XML' },
                    { value: 'mt', label: 'MT103 side-by-side' },
                  ]}
                />
              )}
              <Button
                className="!h-[30px]"
                disabled={!detail}
                onClick={() => {
                  if (!detail) return
                  navigator.clipboard?.writeText(detail.raw)
                  setCopied(true)
                  setTimeout(() => setCopied(false), 1600)
                }}
              >
                {copied ? 'Copied' : 'Copy'}
              </Button>
              <a
                className="inline-flex h-[30px] items-center rounded-md border border-line bg-surface px-3 font-medium text-fg no-underline hover:bg-surface2"
                href={messageDownloadUrl(bank.id, selected.id)}
              >
                Download
              </a>
            </div>
          </div>
          {selected.errors.length > 0 && (
            <ul className="m-0 list-disc border-b border-line2 bg-err-bg py-2 pr-3.5 pl-8 text-xs text-err-fg">
              {selected.errors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          )}
          {showMt ? (
            <Mt103SideBySide id={selected.id} />
          ) : detail ? (
            <Code text={detail.raw} className="flex-1" />
          ) : (
            <div className="p-4 text-muted">Loading…</div>
          )}
          <div className="border-t border-line2 px-3.5 py-2 text-[11px] text-muted">
            Stored byte for byte · SHA-256 <span className="mono">{selected.sha256.slice(0, 12)}…</span>
            {selected.originalMsgId && (
              <>
                {' '}
                · refers to <span className="mono">{selected.originalMsgId}</span>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
