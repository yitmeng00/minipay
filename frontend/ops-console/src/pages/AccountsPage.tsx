import { createColumnHelper, tableFeatures, useTable } from '@tanstack/react-table'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button, Card, CardHeader, Chip, Segmented, type Tone } from '../components/ui'
import {
  ApiError,
  messageDownloadUrl,
  newIdempotencyKey,
  useAccounts,
  useBankInfo,
  useIntegrity,
  useIssueStatement,
  useMessage,
  useStatement,
} from '../lib/api'
import { Code } from '../components/Code'
import { useBank } from '../lib/banks'
import { formatMoney, isNegative, isZero } from '../lib/money'
import type { Account, AccountStatus, AccountType, EntryType, IntegrityReport, Statement } from '../lib/types'

// ---------------------------------------------------------------- presentation rules

type TypeFilter = 'all' | 'CUSTOMER' | 'NOSTRO' | 'VOSTRO' | 'GL'

const TYPE_LABEL: Record<AccountType, string> = {
  CUSTOMER: 'Customer',
  NOSTRO: 'Nostro',
  VOSTRO: 'Vostro',
  SUSPENSE: 'Suspense',
  FEE_INCOME: 'Fee income',
}
const TYPE_TONE: Record<AccountType, Tone> = {
  CUSTOMER: 'info',
  NOSTRO: 'ok',
  VOSTRO: 'warn',
  SUSPENSE: 'neutral',
  FEE_INCOME: 'neutral',
}
const STATUS_TONE: Record<AccountStatus, Tone> = { ACTIVE: 'ok', BLOCKED: 'err', CLOSED: 'neutral' }
const ENTRY_TONE: Record<EntryType, Tone> = {
  SETTLE: 'info',
  FEE: 'neutral',
  REVERSAL: 'err',
  RESERVE: 'warn',
  RELEASE: 'warn',
}

/** Which side of the balance sheet the account is on — the key to reading its statement. */
const SIDE_NOTE: Record<AccountType, (a: Account) => string> = {
  CUSTOMER: () => 'liability · credit balance = money we owe the customer',
  NOSTRO: (a) => `asset · debit balance = money ${a.counterpartyBic ?? 'the other bank'} holds for us`,
  VOSTRO: (a) => `liability · credit balance = money we hold for ${a.counterpartyBic ?? 'another bank'}`,
  SUSPENSE: () => 'technical control account',
  FEE_INCOME: () => 'income · credit balance = fees earned',
}
const FOOTNOTE: Record<AccountType, string> = {
  CUSTOMER:
    'Customer accounts are liabilities: a debit reduces what we owe the customer. Every line here has an equal and opposite line on the contra account.',
  NOSTRO:
    'Nostro accounts are assets: a credit means money left our account at the correspondent. The correspondent’s camt.053 for this account is reconciled in M8.',
  VOSTRO:
    'Vostro accounts are liabilities: the mirror image of the other bank’s nostro with us. A credit means we owe that bank more.',
  SUSPENSE:
    'The holds control account is the contra side of every hold in this currency, so its reserved balance always equals the sum of all active holds.',
  FEE_INCOME: 'Fee income is profit & loss: credits are fees earned, debits are fees refunded or reversed.',
}

/** Display order from the design: customers, then nostros, vostros, and GL accounts last. */
const TYPE_ORDER: Record<AccountType, number> = { CUSTOMER: 0, NOSTRO: 1, VOSTRO: 2, FEE_INCOME: 3, SUSPENSE: 4 }

function byTypeThenCurrency(a: Account, b: Account) {
  return (
    TYPE_ORDER[a.type] - TYPE_ORDER[b.type] ||
    a.currency.localeCompare(b.currency) ||
    a.accountNumber.localeCompare(b.accountNumber)
  )
}

function plural(n: number, word: string) {
  return `${n} ${word}${n === 1 ? 'y' : 'ies'}`
}

function matchesFilter(a: Account, f: TypeFilter) {
  if (f === 'all') return true
  if (f === 'GL') return a.type === 'FEE_INCOME' || a.type === 'SUSPENSE'
  return a.type === f
}

function shortId(id: string | null) {
  return id ? id.slice(0, 8) + '…' + id.slice(-4) : '—'
}

function addDays(isoDate: string, days: number): string {
  const d = new Date(isoDate + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

function formatTime(iso: string, timeZone: string) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3,
    hour12: false,
  }).format(new Date(iso))
}

function formatDay(isoDate: string) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(isoDate + 'T00:00:00Z'),
  )
}

// ---------------------------------------------------------------- accounts table (TanStack Table v9)

const features = tableFeatures({})
const col = createColumnHelper<typeof features, Account>()
const NUMERIC = new Set(['booked', 'reserved', 'available'])

const columns = col.columns([
  col.accessor('accountNumber', {
    header: 'Account',
    cell: (c) => <span className="mono font-medium text-accent">{c.getValue()}</span>,
  }),
  col.accessor('ownerName', { header: 'Name' }),
  col.accessor('type', {
    header: 'Type',
    cell: (c) => (
      <Chip small tone={TYPE_TONE[c.getValue()]}>
        {TYPE_LABEL[c.getValue()]}
      </Chip>
    ),
  }),
  col.accessor('currency', { header: 'Ccy', cell: (c) => <span className="mono">{c.getValue()}</span> }),
  col.accessor('booked', {
    header: 'Booked',
    cell: (c) => <span className={isNegative(c.getValue()) ? 'text-err-fg' : ''}>{formatMoney(c.getValue())}</span>,
  }),
  col.accessor('reserved', {
    header: 'Reserved',
    cell: (c) => (
      <span className={isZero(c.getValue()) ? 'text-muted' : 'text-warn-fg'}>{formatMoney(c.getValue())}</span>
    ),
  }),
  col.accessor('available', {
    header: 'Available',
    cell: (c) => (
      <span className={`font-medium ${isNegative(c.getValue()) ? 'text-err-fg' : ''}`}>{formatMoney(c.getValue())}</span>
    ),
  }),
  col.accessor('counterpartyBic', {
    header: 'Counterparty',
    cell: (c) => <span className="mono text-[11px] text-muted">{c.getValue() ?? '—'}</span>,
  }),
  col.accessor('status', {
    header: 'State',
    cell: (c) => <Chip tone={STATUS_TONE[c.getValue()]}>{c.getValue().charAt(0) + c.getValue().slice(1).toLowerCase()}</Chip>,
  }),
])

const EMPTY: Account[] = []

function AccountsTable({
  accounts,
  selectedId,
  onSelect,
}: {
  accounts: Account[]
  selectedId?: number
  onSelect: (a: Account) => void
}) {
  const table = useTable({ features, columns, data: accounts })
  return (
    <div className="overflow-x-auto">
      <table className="tbl">
        <thead>
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id}>
              {group.headers.map((header) => (
                <th key={header.id} style={NUMERIC.has(header.column.id) ? { textAlign: 'right' } : undefined}>
                  {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => (
            <tr
              key={row.id}
              className={`clickable ${row.original.id === selectedId ? 'selected' : ''}`}
              onClick={() => onSelect(row.original)}
              onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect(row.original)}
              tabIndex={0}
              aria-selected={row.original.id === selectedId}
            >
              {row.getAllCells().map((cell) => (
                <td key={cell.id} className={NUMERIC.has(cell.column.id) ? 'num mono' : undefined}>
                  <table.FlexRender cell={cell} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ---------------------------------------------------------------- statement

type Period = 'today' | 'yesterday' | 'custom'

function StatementCard({ account, businessDate }: { account: Account; businessDate: string | undefined }) {
  const [period, setPeriod] = useState<Period>('today')
  const [customDate, setCustomDate] = useState<string>('')
  const date =
    period === 'today'
      ? businessDate
      : period === 'yesterday' && businessDate
        ? addDays(businessDate, -1)
        : customDate || businessDate
  const { data: st, isLoading, isError } = useStatement(account.id, date)
  // camt.053: we statement the accounts we keep for others; a nostro's statement comes from the bank that keeps it.
  const statementable = account.type === 'CUSTOMER' || account.type === 'VOSTRO'
  const issue = useIssueStatement()
  const [issued, setIssued] = useState<{ id: string; msgId: string | null; replayed: boolean } | null>(null)
  // One key per (account, date) intention: a double click replays the statement instead of issuing two.
  const [key, setKey] = useState(() => newIdempotencyKey('ui-camt053'))
  const issuedRaw = useMessage(issued?.id).data
  const { bank } = useBank()

  return (
    <Card className="overflow-hidden">
      <CardHeader className="items-start">
        <div>
          <h2 className="m-0 text-[15px] font-semibold">
            Statement · <span className="mono">{account.accountNumber}</span>
          </h2>
          <span className="text-xs text-muted">
            {account.ownerName} · {account.currency} · {SIDE_NOTE[account.type](account)}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Segmented<Period>
            label="Period"
            value={period}
            onChange={setPeriod}
            options={[
              { value: 'today', label: 'Today' },
              { value: 'yesterday', label: 'Yesterday' },
              { value: 'custom', label: 'Custom' },
            ]}
          />
          {period === 'custom' && (
            <label className="flex items-center gap-1.5 text-xs text-muted">
              <span className="sr-only">Statement date</span>
              <input
                type="date"
                value={customDate || businessDate || ''}
                max={businessDate}
                onChange={(e) => setCustomDate(e.target.value)}
                className="h-8 rounded-md border border-line bg-surface px-2.5 text-fg"
              />
            </label>
          )}
          <Button
            className="!h-[30px]"
            disabled={!statementable || !date || issue.isPending}
            title={statementable ? 'Issue an ISO 20022 end-of-day statement for this account' : 'The camt.053 for a ' + account.type.toLowerCase() + ' account comes from the bank that keeps it'}
            onClick={() =>
              date &&
              issue.mutate(
                { accountId: account.id, date, key },
                {
                  onSuccess: (r) => {
                    setIssued({ id: r.value.id, msgId: r.value.msgId, replayed: r.replayed })
                    setKey(newIdempotencyKey('ui-camt053'))
                  },
                },
              )
            }
          >
            Generate camt.053
          </Button>
        </div>
      </CardHeader>
      {issue.error && <div className="border-b border-line2 px-4 py-2 text-xs text-err-fg">{(issue.error as ApiError).message}</div>}
      {issued && (
        <details className="border-b border-line2" open>
          <summary className="flex cursor-pointer flex-wrap items-center gap-2.5 px-4 py-2 text-xs">
            <Chip tone="ok">camt.053 issued</Chip>
            <span className="mono">{issued.msgId}</span>
            <span className="text-muted">
              {issued.replayed ? 'replayed' : 'stored'} · to {account.counterpartyBic ?? 'the customer channel'}
            </span>
            <a className="ml-auto font-medium text-accent no-underline" href={messageDownloadUrl(bank.id, issued.id)}>
              Download
            </a>
            <button type="button" className="cursor-pointer border-0 bg-transparent text-muted" onClick={() => setIssued(null)}>
              Close
            </button>
          </summary>
          {issuedRaw && <Code text={issuedRaw.raw} className="max-h-[420px]" />}
        </details>
      )}

      {isError && <div className="px-4 py-7 text-center text-err-fg">Could not load the statement.</div>}
      {isLoading && <div className="px-4 py-7 text-center text-muted">Loading statement…</div>}
      {st && <StatementBody st={st} />}

      <div className="border-t border-line2 bg-surface2 px-4 py-2.5 text-xs text-muted">{FOOTNOTE[account.type]}</div>
    </Card>
  )
}

function SummaryCell({ label, value, hint, strong }: { label: string; value: string; hint?: string; strong?: boolean }) {
  return (
    <div className="border-r border-line2 px-4 py-3 last:border-r-0">
      <span className="lbl">{label}</span>
      <div className={`mono mt-0.5 text-[15px] ${strong ? 'font-semibold' : 'font-medium'}`}>{value}</div>
      {hint && <span className="text-[11px] text-muted">{hint}</span>}
    </div>
  )
}

function StatementBody({ st }: { st: Statement }) {
  const a = st.account
  const tz = st.timeZone
  return (
    <>
      <div className="grid grid-cols-2 border-b border-line2 md:grid-cols-5">
        <SummaryCell label="Opening booked" value={formatMoney(st.openingBooked)} />
        <SummaryCell label="Debits" value={formatMoney(st.totalDebits)} hint={plural(st.debitCount, 'entr')} />
        <SummaryCell label="Credits" value={formatMoney(st.totalCredits)} hint={plural(st.creditCount, 'entr')} />
        <SummaryCell label="Closing booked" value={formatMoney(st.closingBooked)} strong />
        <SummaryCell label="Reserved · available" value={`${formatMoney(a.reserved)} · ${formatMoney(a.available)}`} />
      </div>

      {st.lines.length === 0 ? (
        <div className="px-4 py-7 text-center text-muted">No postings booked on this account on {formatDay(st.date)}.</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="tbl">
            <thead>
              <tr>
                <th>Booked ({tz.split('/').pop()?.replace('_', ' ')})</th>
                <th>Journal</th>
                <th>Event</th>
                <th>Payment</th>
                <th>Narrative</th>
                <th>Contra account</th>
                <th style={{ textAlign: 'right' }}>Debit</th>
                <th style={{ textAlign: 'right' }}>Credit</th>
                <th style={{ textAlign: 'right' }}>Balance</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={8} className="text-muted">
                  Opening balance {formatDay(st.date)} 00:00
                </td>
                <td className="num mono">{formatMoney(st.openingBooked)}</td>
              </tr>
              {st.lines.map((l) => (
                <tr key={l.postingId}>
                  <td className="mono text-muted">{formatTime(l.bookedAt, tz)}</td>
                  <td className="mono" title={l.journalEntryId}>
                    {shortId(l.journalEntryId)}
                  </td>
                  <td>
                    <Chip small tone={ENTRY_TONE[l.entryType]}>
                      {l.entryType}
                    </Chip>
                  </td>
                  <td className="mono text-muted" title={l.paymentId ?? undefined}>
                    {l.paymentId ? (
                      <Link to={`/payments/${l.paymentId}`} className="text-accent no-underline">
                        {shortId(l.paymentId)}
                      </Link>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="max-w-[260px] overflow-hidden text-ellipsis" title={l.narrative ?? undefined}>
                    {l.narrative}
                  </td>
                  <td className="mono text-muted">{l.contraAccounts}</td>
                  <td className="num mono">{formatMoney(l.debit)}</td>
                  <td className="num mono">{formatMoney(l.credit)}</td>
                  <td className="num mono font-medium">{formatMoney(l.balance)}</td>
                </tr>
              ))}
              <tr>
                <td colSpan={6} className="border-t border-line font-semibold">
                  Totals · closing balance
                </td>
                <td className="num mono border-t border-line font-semibold">{formatMoney(st.totalDebits)}</td>
                <td className="num mono border-t border-line font-semibold">{formatMoney(st.totalCredits)}</td>
                <td className="num mono border-t border-line font-semibold">{formatMoney(st.closingBooked)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}

      {st.activeHolds.length > 0 && (
        <div className="flex flex-col gap-1.5 border-t border-line2 px-4 py-3">
          <span className="lbl">Active holds</span>
          {st.activeHolds.map((h) => (
            <div key={h.id} className="flex flex-wrap items-center gap-3.5 text-xs">
              <Chip small tone="warn">
                HOLD
              </Chip>
              <span className="mono" title={h.id}>
                {shortId(h.id)}
              </span>
              <span className="mono font-medium">{formatMoney(h.amount, true)}</span>
              <span className="text-muted">{h.reason ?? 'Funds reserved'}</span>
              <span className="mono ml-auto text-muted">since {formatTime(h.createdAt, tz).slice(0, 8)}</span>
            </div>
          ))}
        </div>
      )}
    </>
  )
}

// ---------------------------------------------------------------- trial balance / integrity

function IntegrityCard({ report, onClose }: { report: IntegrityReport; onClose: () => void }) {
  const problems =
    report.unbalancedEntries.length + report.balanceMismatches.length + report.holdMismatches.length
  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <div className="flex items-center gap-3">
          <h2 className="m-0 text-[13px] font-semibold">Trial balance · end-of-day integrity</h2>
          <Chip tone={report.ok ? 'ok' : 'err'}>{report.ok ? 'Books balance' : `${problems} problem(s)`}</Chip>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs text-muted">
            {report.journalEntries} journal entries · {report.postings} postings
          </span>
          <Button onClick={onClose} className="h-[30px]">
            Close
          </Button>
        </div>
      </CardHeader>
      <div className="overflow-x-auto">
        <table className="tbl">
          <thead>
            <tr>
              <th>Currency</th>
              <th>Balance type</th>
              <th style={{ textAlign: 'right' }}>Total debits</th>
              <th style={{ textAlign: 'right' }}>Total credits</th>
              <th>Check</th>
            </tr>
          </thead>
          <tbody>
            {report.trialBalance.map((l) => (
              <tr key={l.currency + l.balanceType}>
                <td className="mono">{l.currency}</td>
                <td>{l.balanceType}</td>
                <td className="num mono">{formatMoney({ amount: l.debits, currency: l.currency })}</td>
                <td className="num mono">{formatMoney({ amount: l.credits, currency: l.currency })}</td>
                <td>
                  <Chip tone={l.balanced ? 'ok' : 'err'}>{l.balanced ? 'Debits = credits' : 'Out of balance'}</Chip>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="border-t border-line2 bg-surface2 px-4 py-2.5 text-xs text-muted">
        Unbalanced entries: {report.unbalancedEntries.length} · balances ≠ postings: {report.balanceMismatches.length} ·
        reserved ≠ active holds: {report.holdMismatches.length}. Computed on one consistent database snapshot.
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- page

export function AccountsPage() {
  const { bank } = useBank()
  const bankInfo = useBankInfo()
  const accounts = useAccounts()
  const [filter, setFilter] = useState<TypeFilter>('all')
  // The selected account lives in the URL (?account=<number>) so a statement can be linked to.
  // Account numbers are per bank; after switching bank an unknown number falls back to the first row.
  const [params, setParams] = useSearchParams()
  const selectedNumber = params.get('account')
  const [showIntegrity, setShowIntegrity] = useState(false)
  const integrity = useIntegrity(showIntegrity)

  const all = accounts.data ?? EMPTY
  const rows = useMemo(() => all.filter((a) => matchesFilter(a, filter)).sort(byTypeThenCurrency), [all, filter])
  const selected = all.find((a) => a.accountNumber === selectedNumber) ?? rows[0]

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Accounts &amp; ledger</h1>
          <p className="mt-0.5 mb-0 text-muted">
            {bank.name} books · available = booked − reserved
            {bankInfo.data && <> · business date {formatDay(bankInfo.data.businessDate)}</>}
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            onClick={() => {
              setShowIntegrity(true)
              void integrity.refetch()
            }}
          >
            Trial balance
          </Button>
        </div>
      </div>

      <section aria-label="Balance concepts" className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <Card className="flex items-start gap-3 px-4 py-3">
          <Chip tone="info">Booked</Chip>
          <span className="text-xs text-muted">
            Sum of all booked postings. Changes only when money really moves: a settlement, a fee or a reversal.
          </span>
        </Card>
        <Card className="flex items-start gap-3 px-4 py-3">
          <Chip tone="warn">Reserved</Chip>
          <span className="text-xs text-muted">
            Holds placed at FUNDS_RESERVED. Posted on the RESERVED balance only — released when the payment books or
            fails.
          </span>
        </Card>
        <Card className="flex items-start gap-3 px-4 py-3">
          <Chip tone="ok">Available</Chip>
          <span className="text-xs text-muted">Booked − reserved. The amount a new payment can use.</span>
        </Card>
      </section>

      {showIntegrity && integrity.data && <IntegrityCard report={integrity.data} onClose={() => setShowIntegrity(false)} />}

      <Card className="overflow-hidden">
        <CardHeader>
          <div>
            <h2 className="m-0 text-[13px] font-semibold">Accounts</h2>
            <span className="text-xs text-muted">{rows.length} accounts · select one to see its statement</span>
          </div>
          <Segmented<TypeFilter>
            label="Account type"
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'all', label: 'All' },
              { value: 'CUSTOMER', label: 'Customer' },
              { value: 'NOSTRO', label: 'Nostro' },
              { value: 'VOSTRO', label: 'Vostro' },
              { value: 'GL', label: 'GL' },
            ]}
          />
        </CardHeader>
        {accounts.isError ? (
          <div className="px-4 py-7 text-center text-err-fg">
            Cannot reach {bank.name} on port {bank.port}. Is it running with profile {bank.id}?
          </div>
        ) : accounts.isLoading ? (
          <div className="px-4 py-7 text-center text-muted">Loading accounts…</div>
        ) : (
          <AccountsTable accounts={rows} selectedId={selected?.id} onSelect={(a) => setParams({ account: a.accountNumber }, { replace: true })} />
        )}
      </Card>

      {selected && <StatementCard key={`${bank.id}-${selected.id}`} account={selected} businessDate={bankInfo.data?.businessDate} />}
    </>
  )
}
