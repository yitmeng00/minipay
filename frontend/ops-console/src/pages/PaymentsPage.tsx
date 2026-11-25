import { createColumnHelper, tableFeatures, useTable } from '@tanstack/react-table'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Card, Chip, Segmented, Tabs } from '../components/ui'
import { usePayments, useStatusCounts, type PaymentFilters } from '../lib/api'
import { useBank } from '../lib/banks'
import { STATUS_TABS, STATUS_TONE, reasonText } from '../lib/iso'
import { formatAmount } from '../lib/money'
import type { Payment } from '../lib/types'

const features = tableFeatures({})
const col = createColumnHelper<typeof features, Payment>()

export function shortUetr(uetr: string) {
  return uetr.slice(0, 8) + '…' + uetr.slice(-4)
}

function formatCreated(iso: string) {
  const d = new Date(iso)
  return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

const columns = col.columns([
  col.accessor('uetr', {
    header: 'UETR',
    cell: (c) => (
      <div className="flex items-center gap-1.5">
        <span
          className={`dir ${c.row.original.direction === 'OUTGOING' ? 'out' : 'in'}`}
          title={c.row.original.direction === 'OUTGOING' ? 'Outbound' : 'Inbound'}
        >
          {c.row.original.direction === 'OUTGOING' ? '↗' : '↙'}
        </span>
        <Link to={`/payments/${c.getValue()}`} className="mono text-accent no-underline" title={c.getValue()}>
          {shortUetr(c.getValue())}
        </Link>
      </div>
    ),
  }),
  col.accessor('endToEndId', { header: 'End-to-end ID', cell: (c) => <span className="mono">{c.getValue()}</span> }),
  col.accessor('debtorName', {
    header: 'Debtor',
    cell: (c) => (
      <div className="max-w-[170px]">
        <div className="ell font-medium">{c.getValue()}</div>
        <div className="mono ell text-[11px] text-muted">{c.row.original.debtorAccount}</div>
      </div>
    ),
  }),
  col.accessor('creditorName', {
    header: 'Creditor',
    cell: (c) => (
      <div className="max-w-[170px]">
        <div className="ell font-medium">{c.getValue()}</div>
        <div className="mono ell text-[11px] text-muted">{c.row.original.creditorAccount}</div>
      </div>
    ),
  }),
  col.accessor('amount', {
    header: 'Amount',
    cell: (c) => (
      <>
        <span className="mono text-[13px] font-medium">{formatAmount(c.getValue().amount)}</span>{' '}
        <span className="mono text-muted">{c.getValue().currency}</span>
      </>
    ),
  }),
  col.accessor('debtorAgentBic', { header: 'Debtor BIC', cell: (c) => <span className="mono">{c.getValue()}</span> }),
  col.accessor('creditorAgentBic', { header: 'Creditor BIC', cell: (c) => <span className="mono">{c.getValue()}</span> }),
  col.accessor('status', {
    header: 'Status',
    cell: (c) => (
      <>
        <Chip tone={STATUS_TONE[c.getValue()]}>{c.getValue()}</Chip>
        {c.row.original.rejectionReason && (
          <div className="mt-0.5 max-w-[150px] text-[11px] whitespace-normal text-muted">{reasonText(c.row.original.rejectionReason)}</div>
        )}
        {c.getValue() === 'FUNDS_RESERVED' && (
          <div className="mt-0.5 max-w-[150px] text-[11px] whitespace-normal text-muted">Warehoused · executes {c.row.original.executionDate}</div>
        )}
      </>
    ),
  }),
  col.accessor('valueDate', {
    header: 'Value date',
    cell: (c) => <span className="mono">{c.getValue() ?? c.row.original.requestedExecutionDate}</span>,
  }),
  col.accessor('createdAt', {
    header: 'Created',
    cell: (c) => <span className="mono text-muted">{formatCreated(c.getValue())}</span>,
  }),
])

const EMPTY: Payment[] = []

type Dir = 'all' | 'OUTGOING' | 'INCOMING'
type Ccy = 'all' | 'EUR' | 'GBP' | 'MYR' | 'USD'

export function PaymentsPage() {
  const { bank } = useBank()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // Deep links (e.g. from an Overview alert) can open a status tab: /payments?tab=rejected
  const [tab, setTab] = useState(() => (STATUS_TABS.some((t) => t.id === searchParams.get('tab')) ? searchParams.get('tab')! : 'all'))
  const [dir, setDir] = useState<Dir>('all')
  const [ccy, setCcy] = useState<Ccy>('all')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [min, setMin] = useState('')
  const [max, setMax] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState<'50' | '100' | '250'>('50')

  const filters: PaymentFilters = {
    statuses: STATUS_TABS.find((t) => t.id === tab)?.statuses ?? [],
    direction: dir === 'all' ? undefined : dir,
    currency: ccy === 'all' ? undefined : ccy,
    valueDateFrom: from || undefined,
    valueDateTo: to || undefined,
    minAmount: /^\d+(\.\d+)?$/.test(min) ? min : undefined,
    maxAmount: /^\d+(\.\d+)?$/.test(max) ? max : undefined,
    q: q.trim() || undefined,
    page,
    size: Number(size),
  }
  const payments = usePayments(filters)
  const counts = useStatusCounts().data

  const tabs = useMemo(
    () =>
      STATUS_TABS.map((t) => ({
        id: t.id,
        label: t.label,
        count: counts
          ? (t.statuses.length ? t.statuses : (Object.keys(counts) as (keyof typeof counts)[]))
              .reduce((sum, s) => sum + (counts[s] ?? 0), 0)
              .toLocaleString('en-US')
          : '…',
      })),
    [counts],
  )

  const table = useTable({ features, columns, data: payments.data?.items ?? EMPTY })
  const total = payments.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / Number(size)))
  const reset = () => {
    setTab('all')
    setDir('all')
    setCcy('all')
    setFrom('')
    setTo('')
    setMin('')
    setMax('')
    setQ('')
    setPage(0)
  }
  const onFilter = <T,>(set: (v: T) => void) => (v: T) => {
    set(v)
    setPage(0)
  }

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Payments</h1>
          <p className="mt-0.5 mb-0 text-muted">
            Customer credit transfers (pacs.008) sent and received by {bank.name} · <span className="mono">{bank.bic}</span>
          </p>
        </div>
        <Button primary onClick={() => navigate('/payments/new')}>
          + Create test payment
        </Button>
      </div>

      <Tabs label="Status" tabs={tabs} value={tab} onChange={onFilter(setTab)} />

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <span className="lbl">Direction</span>
          <Segmented<Dir>
            label="Direction"
            value={dir}
            onChange={onFilter(setDir)}
            options={[
              { value: 'all', label: 'All' },
              { value: 'OUTGOING', label: 'Outbound' },
              { value: 'INCOMING', label: 'Inbound' },
            ]}
          />
        </div>
        <div className="flex flex-col gap-1">
          <span className="lbl">Currency</span>
          <Segmented<Ccy>
            label="Currency"
            value={ccy}
            onChange={onFilter(setCcy)}
            options={(['all', 'EUR', 'USD', 'GBP', 'MYR'] as Ccy[]).map((c) => ({ value: c, label: c === 'all' ? 'All' : c }))}
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="lbl" htmlFor="vd-from">
            Value date
          </label>
          <div className="flex items-center gap-1.5">
            <input id="vd-from" type="date" className="input mono w-[140px]" value={from} onChange={(e) => onFilter(setFrom)(e.target.value)} />
            <span className="text-muted">–</span>
            <input type="date" aria-label="Value date to" className="input mono w-[140px]" value={to} onChange={(e) => onFilter(setTo)(e.target.value)} />
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <label className="lbl" htmlFor="amt-min">
            Amount
          </label>
          <div className="flex items-center gap-1.5">
            <input id="amt-min" className="input mono w-[100px] text-right" placeholder="Min" inputMode="decimal" value={min} onChange={(e) => onFilter(setMin)(e.target.value)} />
            <span className="text-muted">–</span>
            <input aria-label="Maximum amount" className="input mono w-[100px] text-right" placeholder="Max" inputMode="decimal" value={max} onChange={(e) => onFilter(setMax)(e.target.value)} />
          </div>
        </div>
        <div className="flex min-w-[240px] flex-1 flex-col gap-1">
          <label className="lbl" htmlFor="pay-q">
            Search
          </label>
          <input
            id="pay-q"
            type="search"
            className="input w-full bg-surface2"
            placeholder="UETR, end-to-end ID, instruction ID, name, account, BIC"
            value={q}
            onChange={(e) => onFilter(setQ)(e.target.value)}
          />
        </div>
        <Button onClick={reset}>Reset</Button>
      </div>

      <Card className="overflow-hidden">
        <div className="overflow-x-auto">
          <table className="tbl">
            <thead>
              {table.getHeaderGroups().map((g) => (
                <tr key={g.id}>
                  {g.headers.map((h) => (
                    <th key={h.id} style={h.column.id === 'amount' ? { textAlign: 'right' } : undefined}>
                      <table.FlexRender header={h} />
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {table.getRowModel().rows.map((row) => (
                <tr key={row.id} className="clickable" onClick={(e) => {
                  if ((e.target as HTMLElement).closest('a')) return
                  navigate(`/payments/${row.original.uetr}`)
                }}>
                  {row.getAllCells().map((cell) => (
                    <td key={cell.id} className={cell.column.id === 'amount' ? 'num' : undefined}>
                      <table.FlexRender cell={cell} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {payments.isError && (
            <div className="p-10 text-center text-err-fg">Cannot reach {bank.name} on port {bank.port}.</div>
          )}
          {payments.data && payments.data.items.length === 0 && (
            <div className="p-10 text-center text-muted">
              No payments match these filters.{' '}
              <Button onClick={reset} className="ml-2">
                Reset filters
              </Button>
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line2 px-4 py-2.5 text-xs text-muted">
          <span>
            Showing {payments.data?.items.length ?? 0} of {total.toLocaleString('en-US')} · sorted by created, newest first
          </span>
          <div className="flex items-center gap-2">
            <span>Rows</span>
            <Segmented
              label="Rows per page"
              value={size}
              onChange={(v) => {
                setSize(v)
                setPage(0)
              }}
              options={[
                { value: '50', label: '50' },
                { value: '100', label: '100' },
                { value: '250', label: '250' },
              ]}
            />
            <Button aria-label="Previous page" disabled={page === 0} onClick={() => setPage(page - 1)}>
              ‹
            </Button>
            <span className="mono">
              {page + 1} / {pages}
            </span>
            <Button aria-label="Next page" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
              ›
            </Button>
          </div>
        </div>
      </Card>
    </>
  )
}
