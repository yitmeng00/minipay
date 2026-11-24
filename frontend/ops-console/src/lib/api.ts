import { useQuery } from '@tanstack/react-query'
import { useBank } from './banks'
import type { Account, AccountType, BankInfo, IntegrityReport, Statement } from './types'

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
