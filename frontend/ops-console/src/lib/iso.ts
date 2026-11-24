import type { Tone } from '../components/ui'
import type { ChargeBearer, PaymentStatus } from './types'

/** ISO 20022 ExternalStatusReason1Code / return reasons used by MiniPay, with their official names. */
export const REASON: Record<string, string> = {
  AC01: 'Incorrect account number',
  AC03: 'Invalid creditor account number',
  AC04: 'Closed account number',
  AC06: 'Blocked account',
  AG01: 'Transaction forbidden',
  AG03: 'Transaction not supported',
  AM03: 'Not allowed currency',
  AM04: 'Insufficient funds',
  AM09: 'Wrong amount',
  AM12: 'Invalid amount',
  BE04: 'Missing creditor address',
  CNOR: 'Creditor bank is not registered',
  CUST: 'Requested by customer',
  DT01: 'Invalid date',
  DUPL: 'Duplicate payment',
  MD07: 'End customer deceased',
  NARR: 'Narrative',
  RC01: 'Bank identifier incorrect',
  RR03: 'Missing creditor name or address',
}

export function reasonText(code: string | null | undefined): string {
  if (!code) return ''
  return REASON[code] ? `${code} · ${REASON[code]}` : code
}

export const STATUS_TONE: Record<PaymentStatus, Tone> = {
  RECEIVED: 'info',
  VALIDATED: 'info',
  SCREENED: 'info',
  HELD_COMPLIANCE: 'warn',
  FUNDS_RESERVED: 'info',
  SENT: 'info',
  ACKED: 'info',
  SETTLED: 'ok',
  REJECTED: 'err',
  RETURNED: 'err',
}

/** The payments list's status tabs (as in the design) and the statuses each one covers. */
export const STATUS_TABS: { id: string; label: string; statuses: PaymentStatus[] }[] = [
  { id: 'all', label: 'All', statuses: [] },
  { id: 'progress', label: 'In progress', statuses: ['RECEIVED', 'VALIDATED', 'SCREENED', 'SENT', 'ACKED'] },
  { id: 'pending', label: 'Pending / held', statuses: ['FUNDS_RESERVED', 'HELD_COMPLIANCE'] },
  { id: 'settled', label: 'Settled', statuses: ['SETTLED'] },
  { id: 'rejected', label: 'Rejected / returned', statuses: ['REJECTED', 'RETURNED'] },
]

export const CHARGE_BEARER: Record<ChargeBearer, { short: string; long: string }> = {
  DEBT: { short: 'Debtor pays all', long: 'debtor pays all charges (MT: OUR)' },
  CRED: { short: 'Creditor pays all', long: 'all charges deducted from the amount (MT: BEN)' },
  SHAR: { short: 'Shared', long: 'debtor pays our fee, later banks deduct theirs (MT: SHA)' },
}

/** ISO 13616 check: move the first four characters to the end, letters → numbers, mod 97 must be 1. */
export function ibanCheck(raw: string): { ok: boolean; msg: string } {
  const iban = raw.replace(/\s+/g, '').toUpperCase()
  if (!iban) return { ok: false, msg: 'Required' }
  if (!/^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$/.test(iban)) return { ok: false, msg: 'Not an IBAN shape (CC + 2 check digits + BBAN)' }
  const rearranged = iban.slice(4) + iban.slice(0, 4)
  let rem = 0
  for (const ch of rearranged) {
    const v = ch >= 'A' ? (ch.charCodeAt(0) - 55).toString() : ch
    for (const d of v) rem = (rem * 10 + Number(d)) % 97
  }
  return rem === 1 ? { ok: true, msg: 'mod-97 = 1' } : { ok: false, msg: `Check digits wrong (mod-97 = ${rem})` }
}

export function formatIban(raw: string): string {
  return raw.replace(/\s+/g, '').toUpperCase().replace(/(.{4})/g, '$1 ').trim()
}

export const BIC_RE = /^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$/
