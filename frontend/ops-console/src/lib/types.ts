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

// ---- Payment hub (com.minipay.paymenthub.api)

export type PaymentStatus =
  | 'RECEIVED'
  | 'VALIDATED'
  | 'SCREENED'
  | 'HELD_COMPLIANCE'
  | 'FUNDS_RESERVED'
  | 'SENT'
  | 'ACKED'
  | 'SETTLED'
  | 'REJECTED'
  | 'RETURNED'

export type ChargeBearer = 'DEBT' | 'CRED' | 'SHAR'

export type Payment = {
  id: string
  uetr: string
  endToEndId: string
  instructionId: string
  direction: 'OUTGOING' | 'INCOMING'
  status: PaymentStatus
  amount: Money
  interbankSettlementAmount: Money | null
  chargesAmount: Money
  chargeBearer: ChargeBearer
  debtorName: string
  debtorAccount: string
  debtorAgentBic: string
  creditorName: string
  creditorAccount: string
  creditorAgentBic: string
  instructedAgentBic: string | null
  remittanceInformation: string | null
  requestedExecutionDate: string
  executionDate: string
  valueDate: string | null
  routingRuleId: string | null
  rejectionReason: string | null
  rejectionDetail: string | null
  reservationId: string | null
  createdAt: string
  updatedAt: string
}

export type StateChange = {
  id: number
  from: PaymentStatus | null
  to: PaymentStatus
  reasonCode: string | null
  detail: string | null
  actor: string
  occurredAt: string
}

export type PaymentDetail = { payment: Payment; history: StateChange[] }

export type Page<T> = { items: T[]; page: number; size: number; total: number }

export type Posting = {
  id: number
  accountId: number
  accountNumber: string
  direction: 'D' | 'C'
  amount: Money
  balanceType: 'BOOKED' | 'RESERVED'
}

export type JournalEntry = {
  id: string
  entryType: EntryType
  paymentId: string | null
  reversesEntryId: string | null
  valueDate: string
  narrative: string | null
  createdAt: string
  postings: Posting[]
}

// ---- Payment hub overview (DailyOverview)

export type DailyOverview = {
  date: string
  timeZone: string
  total: number
  byStatus: Record<PaymentStatus, number>
  perHour: number[]
  valueSent: Money[]
  stuckInSent: number
  stuckAfterMinutes: number
  rejectedByReason: { reasonCode: string; count: number }[]
  queued: { currency: string; count: number; amount: Money }[]
  warehoused: number
}

// ---- Reference data (com.minipay.refdata.api). All effective-dated: validTo/effectiveTo are inclusive.

export type Connectivity = 'CONNECTED' | 'PENDING_RMA' | 'NOT_CONNECTED'

export type BicEntry = {
  bic: string
  institutionName: string
  countryCode: string
  city: string
  connectivity: Connectivity
  currencies: string[]
  validFrom: string
  validTo: string | null
  releaseVersion: string
}

export type IbanCheck = {
  input: string
  electronic: string
  printFormat: string
  valid: boolean
  countryCode: string
  parts: { name: string; role: string; value: string }[]
  checks: { name: string; outcome: 'PASS' | 'WARN' | 'FAIL'; detail: string }[]
  bankId: string | null
  bic: string | null
}

export type RoutingMethod = 'DIRECT' | 'SERIAL' | 'COVER' | 'RTGS'

export type RoutingRule = {
  ruleId: string
  destination: string
  currency: string
  method: RoutingMethod
  nextHopBic: string | null
  settlementAccount: string
  priority: number
  effectiveFrom: string
  effectiveTo: string | null
  releaseVersion: string
}

export type RouteResolution = {
  creditorAgentBic: string
  currency: string
  valueDate: string
  route: { rule: RoutingRule; instructedAgentBic: string } | null
  problem: string | null
  considered: { rule: RoutingRule; note: string }[]
}

export type Release = {
  version: string
  publishedAt: string
  publishedBy: string
  approvedBy: string
  description: string
  routingRulesChanged: number
}

export type RefDataSummary = {
  dataVersion: string | null
  currentRelease: Release | null
  date: string
  institutions: number
  routingRules: number
}

export type Cutoff = {
  currency: string
  schemeName: string
  timeZone: string
  cutoffTime: string
  settlementDate: string
  cutoff: string
  openToday: boolean
  holidayToday: string | null
}

export type ValueDating = {
  currency: string
  schemeName: string
  earliest: string
  valueDate: string
  cutoff: string
  reasons: string[]
}

export type Holiday = { calendarCode: string; date: string; name: string; releaseVersion: string }

export type Scheme = {
  currency: string
  schemeName: string
  calendarCode: string
  timeZone: string
  cutoffTime: string
  validFrom: string
  validTo: string | null
  releaseVersion: string
}

// ---- Messaging (com.minipay.messaging.api)

export type MessageType = 'PACS_008' | 'PACS_002' | 'PACS_004' | 'CAMT_053' | 'MT103'

export type MessageView = {
  id: string
  direction: 'OUTBOUND' | 'INBOUND'
  type: MessageType | null
  msgId: string | null
  senderBic: string | null
  receiverBic: string | null
  uetr: string | null
  endToEndId: string | null
  instructionId: string | null
  originalMsgId: string | null
  account: string | null
  amount: Money | null
  settlementDate: string | null
  status: string | null
  reasonCode: string | null
  errors: string[]
  sha256: string
  createdAt: string
}

export type MessageDetail = { message: MessageView; raw: string }

export type Fidelity = 'EQUIVALENT' | 'TRANSFORMED' | 'TRUNCATED' | 'IMPLICIT' | 'LOST'

export type FieldMapping = { mtTag: string; mtValue: string; mxPath: string; mxValue: string; fidelity: Fidelity; note: string | null }

export type Translation = { from: MessageType; to: MessageType; target: string; fields: FieldMapping[] }

export type PaymentPreview = { xml: string; notes: string[] }
