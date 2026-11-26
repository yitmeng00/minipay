import { createContext, useContext, useState, type ReactNode } from 'react'
import { useBank, type Bank } from './banks'

/**
 * Who is using the console. There is no login in the simulator, but four-eyes decisions need to know
 * who decided: the person picked here is sent as `decidedBy`, and the backend refuses to let the same
 * person release and approve. Each bank has its own (fictional) staff.
 */
export type Operator = { id: string; name: string; role: string }

export const STAFF: Record<Bank['id'], Operator[]> = {
  'bank-a': [
    { id: 'n.rahman', name: 'Nadia Rahman', role: 'Payment Ops · L2' },
    { id: 'j.tan', name: 'Jason Tan', role: 'Compliance · L2' },
    { id: 's.lim', name: 'Siew Lim', role: 'MLRO' },
  ],
  'bank-b': [
    { id: 'k.weber', name: 'Katrin Weber', role: 'Compliance · L2' },
    { id: 'm.vogel', name: 'Markus Vogel', role: 'Payment Ops · L2' },
    { id: 'h.braun', name: 'Hanna Braun', role: 'MLRO' },
  ],
  'bank-c': [
    { id: 'o.hughes', name: 'Olivia Hughes', role: 'Compliance · L2' },
    { id: 'r.patel', name: 'Ravi Patel', role: 'Payment Ops · L2' },
    { id: 'd.evans', name: 'Daniel Evans', role: 'MLRO' },
  ],
}

type OperatorContextValue = { operator: Operator; staff: Operator[]; setOperator: (id: string) => void }

const OperatorContext = createContext<OperatorContextValue | null>(null)

export function OperatorProvider({ children }: { children: ReactNode }) {
  const { bank } = useBank()
  const staff = STAFF[bank.id]
  // The chosen person per bank, remembered across reloads.
  const [chosen, setChosen] = useState<Record<string, string | null>>(() =>
    Object.fromEntries(Object.keys(STAFF).map((id) => [id, localStorage.getItem(`minipay.operator.${id}`)])),
  )
  const operator = staff.find((o) => o.id === chosen[bank.id]) ?? staff[0]
  const setOperator = (id: string) => {
    localStorage.setItem(`minipay.operator.${bank.id}`, id)
    setChosen((c) => ({ ...c, [bank.id]: id }))
  }
  return <OperatorContext.Provider value={{ operator, staff, setOperator }}>{children}</OperatorContext.Provider>
}

export function useOperator(): OperatorContextValue {
  const ctx = useContext(OperatorContext)
  if (!ctx) throw new Error('useOperator outside OperatorProvider')
  return ctx
}

export function initials(name: string) {
  return name.split(' ').map((p) => p[0]).join('').slice(0, 2).toUpperCase()
}
