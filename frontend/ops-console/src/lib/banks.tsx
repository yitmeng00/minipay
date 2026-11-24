import { createContext, useContext, useState, type ReactNode } from 'react'

/** The three simulated banks. `prefix` is the Vite proxy path to that bank's backend. */
export const BANKS = [
  { id: 'bank-a', name: 'Bank A', city: 'Kuala Lumpur', bic: 'MNPAMYKLXXX', role: 'Originating bank', port: 8081 },
  { id: 'bank-b', name: 'Bank B', city: 'Frankfurt', bic: 'MNPBDEFFXXX', role: 'Correspondent', port: 8082 },
  { id: 'bank-c', name: 'Bank C', city: 'London', bic: 'MNPCGB2LXXX', role: 'Beneficiary bank', port: 8083 },
] as const

export type Bank = (typeof BANKS)[number]

type BankContextValue = { bank: Bank; setBank: (b: Bank) => void }

const BankContext = createContext<BankContextValue | null>(null)

export function BankProvider({ children }: { children: ReactNode }) {
  const [bank, setBankState] = useState<Bank>(
    () => BANKS.find((b) => b.id === localStorage.getItem('minipay.bank')) ?? BANKS[0],
  )
  const setBank = (b: Bank) => {
    localStorage.setItem('minipay.bank', b.id)
    setBankState(b)
  }
  return <BankContext.Provider value={{ bank, setBank }}>{children}</BankContext.Provider>
}

export function useBank(): BankContextValue {
  const ctx = useContext(BankContext)
  if (!ctx) throw new Error('useBank outside BankProvider')
  return ctx
}
