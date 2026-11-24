import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useBank } from './banks'
import type {
  Account,
  AccountType,
  BankInfo,
  ChargeBearer,
  IntegrityReport,
  JournalEntry,
  Money,
  Page,
  Payment,
  PaymentDetail,
  PaymentStatus,
  Statement,
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
