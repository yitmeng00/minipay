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
  | 'AWAITING_COVER'
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
  kind: 'CUSTOMER' | 'COVER'
  instructingAgentBic: string | null
  settlementMethod: 'INDA' | 'INGA' | 'COVE' | 'CLRG' | null
  coverAgentBic: string | null
  rejectionReason: string | null
  rejectionDetail: string | null
  reservationId: string | null
  createdAt: string
  updatedAt: string
  serviceLevel: ServiceLevel
  clearingSystem: string | null
  viaBic: string | null
}

/** NORM normal · HIGH urgent (never through the ACH) · INST instant (only through the instant scheme). */
export type ServiceLevel = 'NORM' | 'HIGH' | 'INST'

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

export type MessageType = 'PACS_008' | 'PACS_002' | 'PACS_004' | 'PACS_009' | 'CAMT_053' | 'CAMT_054' | 'MT103'

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

// ---- UETR tracker (com.minipay.network.api)

export type Charge = { amount: Money; agentBic: string }

export type Hop = {
  number: number
  type: string
  fromBic: string
  toBic: string
  msgId: string
  amount: Money | null
  settlementMethod: string | null
  sentAt: string
  reply: string | null
  replyReason: string | null
  replyAt: string | null
}

export type TrackedMessage = {
  eventId: string
  type: string
  msgId: string
  fromBic: string
  toBic: string
  originalMsgId: string | null
  amount: Money | null
  settlementMethod: string | null
  status: string | null
  reasonCode: string | null
  charges: Charge[]
  sentAt: string
}

export type TrackerView = {
  uetr: string
  status: 'ACSP' | 'ACSC' | 'RJCT' | 'RTND'
  method: 'SERIAL' | 'COVER' | 'DIRECT' | null
  debtorAgentBic: string | null
  creditorAgentBic: string | null
  sentAmount: Money | null
  creditedAmount: Money | null
  returnedAmount: Money | null
  deductions: Charge[]
  hops: Hop[]
  coverLegs: Hop[]
  returnPath: Hop[]
  messages: TrackedMessage[]
  endToEnd: string | null
}

export type TrackerSummary = {
  uetr: string
  status: TrackerView['status']
  debtorAgentBic: string | null
  creditorAgentBic: string | null
  sentAmount: Money | null
  messages: number
  lastUpdate: string
}

// ---------------------------------------------------------------- compliance (M6)

export type CaseStatus = 'OPEN' | 'ESCALATED' | 'PENDING_APPROVAL' | 'RELEASED' | 'REJECTED'
export type DecisionAction = 'RELEASE' | 'REJECT' | 'ESCALATE' | 'APPROVE' | 'DECLINE' | 'WITHDRAW'
export type DecisionReason = 'FALSE_POSITIVE' | 'TRUE_MATCH' | 'INSUFFICIENT_INFORMATION'

export type NameToken = { text: string; matched: boolean }
export type Factor = { name: string; percent: number | null; value: string }
export type ComparisonRow = { attribute: string; party: string; listed: string; match: string }

export type Hit = {
  id: number
  role: string
  field: string
  partyName: string
  entryId: string
  listCode: string
  listName: string
  entryType: 'INDIVIDUAL' | 'ENTITY' | 'BANK'
  listedName: string
  matchedOn: 'NAME' | 'ALIAS' | 'BIC'
  matchedName: string
  score: number
  partyTokens: NameToken[]
  listedTokens: NameToken[]
  factors: Factor[]
  comparison: ComparisonRow[]
  hint: string
  listedFrom: string
  listedTo: string | null
  program: string
}

export type Decision = {
  id: number
  action: DecisionAction
  reason: DecisionReason | null
  comment: string
  decidedBy: string
  decidedAt: string
  statusBefore: CaseStatus
  statusAfter: CaseStatus
}

export type ComplianceCase = {
  id: string
  paymentId: string
  uetr: string
  direction: 'OUTGOING' | 'INCOMING'
  kind: 'CUSTOMER' | 'COVER'
  amount: Money
  debtorName: string
  creditorName: string
  route: string
  status: CaseStatus
  bestScore: number
  fourEyes: boolean
  heldAt: string
  slaDeadline: string
  closedAt: string | null
  screenedAsOf: string
  lists: string
  hits: Hit[]
  decisions: Decision[]
}

export type ComplianceStats = {
  pending: number
  awaitingApproval: number
  escalated: number
  oldestHeldAt: string | null
  releasedToday: number
  rejectedToday: number
  medianDecisionMinutes: number | null
  slaMinutes: number
  pendingByList: Record<string, number>
}

export type ComplianceSettings = { holdThreshold: number; fourEyesThreshold: number; slaMinutes: number; lists: string[] }

export type SanctionsList = { code: string; name: string; authority: string; snapshotDate: string; activeEntries: number }

export type Screening = {
  id: string
  paymentId: string
  screenedAt: string
  asOf: string
  lists: string
  screenedItems: number
  holdThreshold: number
  bestScore: number
  outcome: 'CLEAR' | 'HIT'
  caseId: string | null
  caseStatus: CaseStatus | null
}

export type PaymentCompliance = { screening: Screening; caseView: ComplianceCase | null }

// ---------------------------------------------------------------- settlement systems (M7, served by the operator)

export type TransferStatus = 'QUEUED' | 'ACCEPTED' | 'PENDING' | 'SETTLED' | 'REJECTED'

export type Transfer = {
  id: string
  system: string
  kind: 'RTGS' | 'DNS' | 'INSTANT'
  messageType: string
  messageId: string
  msgId: string
  uetr: string
  endToEndId: string | null
  instructionId: string | null
  payerBic: string
  payeeBic: string
  amount: Money
  priority: 'HIGH' | 'NORM'
  settlementDate: string
  status: TransferStatus
  queueSeq: number
  cycleId: string | null
  deadline: string | null
  reasonCode: string | null
  reasonDetail: string | null
  receivedAt: string
  settledAt: string | null
  waiting: string | null
}

export type ClearingParticipant = {
  bic: string
  name: string
  simulated: boolean
  rtgsAccount: string
  instantAccount: string
  rtgsBalance: Money
  instantAvailable: Money
  instantReserved: Money
}

export type RtgsView = {
  system: string
  systemName: string
  participantBic: string
  participantName: string
  businessDate: string
  timeZone: string
  cutoff: string
  cutoffAt: string
  available: Money
  queuedOut: Money
  queuedOutCount: number
  queuedIn: Money
  queuedInCount: number
  projected: Money
  intraday: { at: string; balance: Money }[]
  queue: Transfer[]
  recent: Transfer[]
}

export type DnsCycle = {
  id: string
  system: string
  systemName: string
  businessDate: string
  seq: number
  status: 'OPEN' | 'SETTLED'
  scheduledClose: string
  settledAt: string | null
  payments: number
  rejected: number
  participants: string[]
  gross: { payerBic: string; cells: Money[] }[]
  positions: { bic: string; name: string; pays: Money; receives: Money; net: Money }[]
  grossTotal: Money
  bilateralTotal: Money
  multilateralTotal: Money
  unwound: string[]
}

export type InstantView = {
  system: string
  systemName: string
  timeoutSeconds: number
  participantBic: string
  available: Money
  reserved: Money
  today: number
  accepted: number
  rejected: number
  timedOut: number
  medianMillis: number | null
  p99Millis: number | null
  feed: Transfer[]
}

export type ClearingSystem = {
  code: string
  name: string
  kind: 'RTGS' | 'DNS' | 'INSTANT'
  currency: string
  operatorBic: string
  timeZone: string
  cutoff: string | null
  cycleTimes: string[]
  timeoutSeconds: number | null
}

// ---------------------------------------------------------------- reconciliation (M8): amounts signed, + = money in

export type ReconNostro = {
  account: string
  name: string
  currency: string | null
  servicerBic: string
  servicerAccount: string
  lastDate: string | null
  openBreaks: number
  ledgerBalance: Money | null
}

export type ReconLedgerLine = {
  postingId: number
  journalEntryId: string
  date: string
  bookedAt: string
  signed: Money
  uetr: string | null
  endToEndId: string | null
  narrative: string | null
}

export type ReconStatementLine = {
  id: number
  entryRef: string | null
  date: string
  bookedAt: string | null
  signed: Money
  servicerRef: string | null
  uetr: string | null
  endToEndId: string | null
  info: string | null
  bankTxCode: string | null
  reversal: boolean
  transactions: number
  xml: string | null
}

export type ReconRow = {
  matchId: number | null
  rule: string | null
  matchedBy: string | null
  comment: string | null
  breakId: string | null
  breakType: string | null
  breakStatus: string | null
  ledger: ReconLedgerLine[]
  statement: ReconStatementLine[]
}

export type ReconBreak = {
  id: string
  account: string
  type: 'MISSING_IN_STATEMENT' | 'MISSING_IN_LEDGER' | 'AMOUNT_MISMATCH' | 'DUPLICATE'
  status: 'OPEN' | 'RESOLVED' | 'CLEARED'
  difference: Money
  openedAt: string
  reference: string | null
  ledger: ReconLedgerLine | null
  statement: ReconStatementLine | null
  suggestion: string
  actions: string[]
  action: string | null
  comment: string | null
  resolvedBy: string | null
  resolvedAt: string | null
}

export type Reconciliation = {
  account: string
  name: string
  currency: string
  servicerBic: string
  servicerAccount: string
  dates: string[]
  statement: {
    id: string
    messageId: string
    msgId: string
    statementRef: string
    servicerBic: string
    servicerAccount: string
    date: string
    receivedAt: string
    opening: Money
    closing: Money
    entries: number
  } | null
  ledgerEntries: number
  statementEntries: number
  matched: number
  matchedManually: number
  openBreaks: number
  unmatchedValue: Money
  proof: { statementClosing: Money; ledgerClosing: Money; difference: Money; openingDiff: Money; explained: Money; unexplained: Money } | null
  rows: ReconRow[]
  breaks: ReconBreak[]
}
