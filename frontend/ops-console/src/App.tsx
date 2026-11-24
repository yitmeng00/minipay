import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from './components/Shell'
import { AccountsPage } from './pages/AccountsPage'
import { CreatePaymentPage } from './pages/CreatePaymentPage'
import { PaymentDetailPage } from './pages/PaymentDetailPage'
import { PaymentsPage } from './pages/PaymentsPage'

export function App() {
  return (
    <BrowserRouter>
      <Shell>
        <Routes>
          <Route path="/payments" element={<PaymentsPage />} />
          <Route path="/payments/new" element={<CreatePaymentPage />} />
          <Route path="/payments/:ref" element={<PaymentDetailPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          {/* The Overview dashboard needs cut-offs and liquidity alerts (M3+); payments is the landing screen. */}
          <Route path="*" element={<Navigate to="/payments" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  )
}
