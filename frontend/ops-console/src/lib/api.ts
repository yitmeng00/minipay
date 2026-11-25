import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useBank } from './banks'
import type {
  Account,
  AccountType,
  BankInfo,
  BicEntry,
  ChargeBearer,
  Cutoff,
  DailyOverview,
  Holiday,
  IbanCheck,
  MessageDetail,
  MessageView,
  PaymentPreview,
  Translation,
  IntegrityReport,
  JournalEntry,
  Money,
  Page,
  Payment,
  PaymentDetail,
  PaymentStatus,
  RefDataSummary,
  Release,
  RouteResolution,
  RoutingRule,
  Scheme,
  Statement,
  ValueDating,
} from './types'

/** RFC 9457 problem details from the backend, with the ISO reason code when there is one. */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly reasonCode?: string

  constructor(status: number, code?: string, reasonCode?: string, detail?: string) {
    super(detail ?? `HTTP ${status}`)
    this.status = status
    this.code = code
    this.reasonCode = reasonCode
  }
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new ApiError(res.status, body.code, body.reasonCode, body.detail)
  }
  return res.json() as Promise<T>
}

/**
 * Every write carries an Idempotency-Key. Callers create the key ONCE per user intention (e.g. when the
 * form is filled in) and reuse it on retry, so a double click or a timeout-and-retry can never send a
 * second payment — the backend replays the first result instead.
 */
async function postJson<T>(url: string, body: unknown, idempotencyKey: string): Promise<{ value: T; replayed: boolean }> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, json.code, json.reasonCode, json.detail)
  return { value: json as T, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
}

export function newIdempotencyKey(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`
}

// Query keys start with the bank id: switching bank never shows another bank's cached data.

export function useBankInfo() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'bank'],
    queryFn: () => getJson<BankInfo>(`/${bank.id}/api/bank`),
    refetchInterval: 60_000,
  })
}

export function useAccounts(type?: AccountType) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'accounts', type ?? 'all'],
    queryFn: () => getJson<Account[]>(`/${bank.id}/api/ledger/accounts${type ? `?type=${type}` : ''}`),
    refetchInterval: 5_000,
  })
}

export function useStatement(accountId: number | undefined, date: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'statement', accountId, date],
    queryFn: () =>
      getJson<Statement>(`/${bank.id}/api/ledger/accounts/${accountId}/statement${date ? `?date=${date}` : ''}`),
    enabled: accountId !== undefined,
    refetchInterval: 5_000,
  })
}

export function useIntegrity(enabled: boolean) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'integrity'],
    queryFn: () => getJson<IntegrityReport>(`/${bank.id}/api/ledger/integrity`),
    enabled,
  })
}

// ---------------------------------------------------------------- payments

export type PaymentFilters = {
  statuses: PaymentStatus[]
  direction?: 'OUTGOING' | 'INCOMING'
  currency?: string
  valueDateFrom?: string
  valueDateTo?: string
  minAmount?: string
  maxAmount?: string
  q?: string
  page: number
  size: number
}

export function usePayments(f: PaymentFilters) {
  const { bank } = useBank()
  const params = new URLSearchParams()
  f.statuses.forEach((s) => params.append('status', s))
  if (f.direction) params.set('direction', f.direction)
  if (f.currency) params.set('currency', f.currency)
  if (f.valueDateFrom) params.set('valueDateFrom', f.valueDateFrom)
  if (f.valueDateTo) params.set('valueDateTo', f.valueDateTo)
  if (f.minAmount) params.set('minAmount', f.minAmount)
  if (f.maxAmount) params.set('maxAmount', f.maxAmount)
  if (f.q) params.set('q', f.q)
  params.set('page', String(f.page))
  params.set('size', String(f.size))
  return useQuery({
    queryKey: [bank.id, 'payments', params.toString()],
    queryFn: () => getJson<Page<Payment>>(`/${bank.id}/api/payments?${params}`),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  })
}

export function useStatusCounts() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'payments', 'status-counts'],
    queryFn: () => getJson<Record<PaymentStatus, number>>(`/${bank.id}/api/payments/status-counts`),
    refetchInterval: 5_000,
  })
}

export function usePayment(ref: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'payment', ref],
    queryFn: () => getJson<PaymentDetail>(`/${bank.id}/api/payments/${ref}`),
    enabled: !!ref,
    refetchInterval: 5_000,
  })
}

export function usePaymentEntries(paymentId: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'payment-entries', paymentId],
    queryFn: () => getJson<JournalEntry[]>(`/${bank.id}/api/ledger/journal-entries?paymentId=${paymentId}`),
    enabled: !!paymentId,
    refetchInterval: 5_000,
  })
}

export type CreatePaymentBody = {
  debtorAccount: string
  creditorName: string
  creditorAccount: string
  creditorAgentBic: string
  amount: Money
  chargeBearer: ChargeBearer
  requestedExecutionDate?: string
  endToEndId?: string
  remittanceInformation?: string
}

export function useCreatePayment() {
  const { bank } = useBank()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ body, key }: { body: CreatePaymentBody; key: string }) =>
      postJson<Payment>(`/${bank.id}/api/payments`, body, key),
    onSuccess: () => qc.invalidateQueries({ queryKey: [bank.id] }),
  })
}

export type NetworkEventBody = {
  type: 'ACCEPTED' | 'SETTLED' | 'RETURNED'
  reasonCode?: string
  returnedAmount?: Money
  reference?: string
}

export function useNetworkEvent(uetr: string) {
  const { bank } = useBank()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ body, key }: { body: NetworkEventBody; key: string }) =>
      postJson<Payment>(`/${bank.id}/api/payments/${uetr}/network-events`, body, key),
    onSuccess: () => qc.invalidateQueries({ queryKey: [bank.id] }),
  })
}

export function useOverview() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'payments', 'overview'],
    queryFn: () => getJson<DailyOverview>(`/${bank.id}/api/payments/overview`),
    refetchInterval: 10_000,
  })
}

// ---------------------------------------------------------------- reference data
// Every lookup is "as of" a date; without one the backend uses the bank's business date.

const asOf = (date?: string) => (date ? `?date=${date}` : '')

export function useRefDataSummary() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'summary'],
    queryFn: () => getJson<RefDataSummary>(`/${bank.id}/api/refdata`),
  })
}

export function useBicDirectory(date?: string) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'bics', date ?? 'today'],
    queryFn: () => getJson<BicEntry[]>(`/${bank.id}/api/refdata/bics${asOf(date)}`),
    staleTime: 60_000,
  })
}

export function useIbanCheck(iban: string, date?: string) {
  const { bank } = useBank()
  const clean = iban.replace(/\s+/g, '').toUpperCase()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'iban', clean, date ?? 'today'],
    queryFn: () => getJson<IbanCheck>(`/${bank.id}/api/refdata/iban/${encodeURIComponent(clean)}${asOf(date)}`),
    enabled: clean.length > 0,
    placeholderData: (prev) => prev,
    staleTime: 60_000,
  })
}

export function useRoutingRules() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'routing-rules'],
    queryFn: () => getJson<RoutingRule[]>(`/${bank.id}/api/refdata/routing-rules`),
  })
}

export function useRoute(bic: string, currency: string, date?: string) {
  const { bank } = useBank()
  const params = new URLSearchParams({ bic, currency })
  if (date) params.set('date', date)
  return useQuery({
    queryKey: [bank.id, 'refdata', 'route', params.toString()],
    queryFn: () => getJson<RouteResolution>(`/${bank.id}/api/refdata/routes?${params}`),
    enabled: /^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$/.test(bic) && /^[A-Z]{3}$/.test(currency),
    placeholderData: (prev) => prev,
    retry: false,
  })
}

export function useReleases() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'releases'],
    queryFn: () => getJson<Release[]>(`/${bank.id}/api/refdata/releases`),
  })
}

export function useCutoffs() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'cutoffs'],
    queryFn: () => getJson<Cutoff[]>(`/${bank.id}/api/refdata/cutoffs`),
    refetchInterval: 60_000,
  })
}

export function useSchemes() {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'schemes'],
    queryFn: () => getJson<Scheme[]>(`/${bank.id}/api/refdata/schemes`),
  })
}

export function useHolidays(from?: string) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'holidays', from ?? 'today'],
    queryFn: () => getJson<Holiday[]>(`/${bank.id}/api/refdata/holidays${from ? `?from=${from}` : ''}`),
  })
}

export function useValueDate(currency: string | undefined, date: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'refdata', 'value-date', currency, date],
    queryFn: () => getJson<ValueDating>(`/${bank.id}/api/refdata/value-date?currency=${currency}${date ? `&date=${date}` : ''}`),
    enabled: !!currency,
    retry: false,
  })
}

// ---------------------------------------------------------------- messages

export function useMessages(uetr: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'messages', uetr],
    queryFn: () => getJson<MessageView[]>(`/${bank.id}/api/messages?uetr=${uetr}`),
    enabled: !!uetr,
    refetchInterval: 5_000,
  })
}

export function useMessage(id: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'message', id],
    queryFn: () => getJson<MessageDetail>(`/${bank.id}/api/messages/${id}`),
    enabled: !!id,
    staleTime: Infinity, // a stored message never changes
  })
}

export function useMt103(id: string | undefined) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'message', id, 'mt103'],
    queryFn: () => getJson<Translation>(`/${bank.id}/api/messages/${id}/mt103`),
    enabled: !!id,
    staleTime: Infinity,
  })
}

export function messageDownloadUrl(bankId: string, id: string) {
  return `/${bankId}/api/messages/${id}/raw`
}

/** The pacs.008 the form would produce. A read-only POST (nothing is stored), so no Idempotency-Key. */
export function usePaymentPreview(body: CreatePaymentBody | null) {
  const { bank } = useBank()
  return useQuery({
    queryKey: [bank.id, 'payment-preview', JSON.stringify(body)],
    queryFn: async () => {
      const res = await fetch(`/${bank.id}/api/payments/preview`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new ApiError(res.status, json.code, json.reasonCode, json.detail)
      return json as PaymentPreview
    },
    enabled: !!body,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

export function useIssueStatement() {
  const { bank } = useBank()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, date, key }: { accountId: number; date: string; key: string }) =>
      postJson<MessageView>(`/${bank.id}/api/messages/camt053`, { accountId, date }, key),
    onSuccess: () => qc.invalidateQueries({ queryKey: [bank.id, 'messages'] }),
  })
}
