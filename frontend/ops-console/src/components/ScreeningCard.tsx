import { Link } from 'react-router-dom'
import { usePaymentCompliance } from '../lib/api'
import type { CaseStatus } from '../lib/types'
import { Card, Chip, type Tone } from './ui'

const CASE: Record<CaseStatus, { label: string; tone: Tone }> = {
  OPEN: { label: 'Held for review', tone: 'warn' },
  ESCALATED: { label: 'Escalated to MLRO', tone: 'warn' },
  PENDING_APPROVAL: { label: 'Awaiting second approver', tone: 'info' },
  RELEASED: { label: 'Released', tone: 'ok' },
  REJECTED: { label: 'Rejected', tone: 'err' },
}

/** How this bank screened the payment: clear, or the hit and where its case stands. */
export function ScreeningCard({ paymentId }: { paymentId: string }) {
  const data = usePaymentCompliance(paymentId).data
  if (!data) return null
  const { screening: s, caseView: c } = data
  const top = c?.hits[0]
  return (
    <Card className="flex flex-wrap items-center gap-x-4 gap-y-1.5 px-4 py-3 text-xs">
      <span className="lbl">Sanctions screening</span>
      {c && top ? (
        <>
          <Chip tone={CASE[c.status].tone}>{CASE[c.status].label}</Chip>
          <span>
            <span className="mono">{top.field}</span> “{top.partyName}” ≈ <b>{top.matchedName}</b> ({top.entryId} · {top.listName}) score{' '}
            <b className="mono">{top.score}</b>
            {c.hits.length > 1 ? ` · +${c.hits.length - 1} more` : ''}
            {c.fourEyes ? ' · four-eyes' : ''}
          </span>
          <Link to={`/sanctions/${c.id}`} className="ml-auto font-medium text-accent no-underline">
            Review in sanctions queue →
          </Link>
        </>
      ) : (
        <>
          <Chip tone="ok">Clear</Chip>
          <span className="text-muted">
            {s.screenedItems} names and BICs against {s.lists} as of {s.asOf} · no match ≥ {s.holdThreshold} (best {s.bestScore})
          </span>
        </>
      )}
    </Card>
  )
}
