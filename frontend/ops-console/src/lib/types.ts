// Mirrors the backend's com.minipay.ledger.api records. Amounts are decimal STRINGS — never numbers.

export type Money = { amount: string; currency: string }

export type AccountType = 'CUSTOMER' | 'NOSTRO' | 'VOSTRO' | 'SUSPENSE' | 'FEE_INCOME'
export type AccountStatus = 'ACTIVE' | 'BLOCKED' | 'CLOSED'
export type EntryType = 'RESERVE' | 'RELEASE' | 'SETTLE' | 'FEE' | 'REVERSAL'

export type Account = {
  id: number
  accountNumber: string
  type: AccountType
  currency: string
  ownerName: string
  counterpartyBic: string | null
  status: AccountStatus
  allowOverdraft: boolean
  booked: Money
  reserved: Money
  available: Money
  version: number
}

export type Reservation = {
  id: string
  accountId: number
  amount: Money
  paymentId: string | null
  reason: string | null
  status: 'ACTIVE' | 'RELEASED' | 'SETTLED'
  createdAt: string
}

export type StatementLine = {
  postingId: number
  bookedAt: string
  journalEntryId: string
  entryType: EntryType
  paymentId: string | null
  valueDate: string
  narrative: string | null
  contraAccounts: string | null
  debit: Money | null
  credit: Money | null
  balance: Money
}

export type Statement = {
  account: Account
  date: string
  timeZone: string
  openingBooked: Money
  totalDebits: Money
  debitCount: number
  totalCredits: Money
  creditCount: number
  closingBooked: Money
  lines: StatementLine[]
  activeHolds: Reservation[]
}

export type TrialBalanceLine = {
  currency: string
  balanceType: 'BOOKED' | 'RESERVED'
  debits: string
  credits: string
  balanced: boolean
}

export type IntegrityReport = {
  checkedAt: string
  ok: boolean
  journalEntries: number
  postings: number
  unbalancedEntries: unknown[]
  balanceMismatches: unknown[]
  holdMismatches: unknown[]
  trialBalance: TrialBalanceLine[]
}

export type BankInfo = {
  name: string
  bic: string
  city: string
  role: string
  timeZone: string
  serverTime: string
  businessDate: string
}
