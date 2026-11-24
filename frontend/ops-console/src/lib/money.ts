import type { Money } from './types'

/**
 * Formats a decimal string with thousands separators — WITHOUT converting to a JS number.
 * `Number("12345678901234.57")` would already lose precision; string manipulation cannot.
 */
export function formatAmount(amount: string): string {
  const negative = amount.startsWith('-')
  const [int, frac] = (negative ? amount.slice(1) : amount).split('.')
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (negative ? '−' : '') + grouped + (frac !== undefined ? '.' + frac : '')
}

export function formatMoney(m: Money | null | undefined, withCurrency = false): string {
  if (!m) return ''
  return (withCurrency ? m.currency + ' ' : '') + formatAmount(m.amount)
}

export function isZero(m: Money): boolean {
  return /^-?0*(\.0*)?$/.test(m.amount)
}

export function isNegative(m: Money): boolean {
  return m.amount.startsWith('-') && !isZero(m)
}
