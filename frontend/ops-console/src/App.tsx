import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from './components/Shell'
import { AccountsPage } from './pages/AccountsPage'
import { CreatePaymentPage } from './pages/CreatePaymentPage'
import { OverviewPage } from './pages/OverviewPage'
import { PaymentDetailPage } from './pages/PaymentDetailPage'
import { PaymentsPage } from './pages/PaymentsPage'
import { ReferenceDataPage } from './pages/ReferenceDataPage'
import { SanctionsPage } from './pages/SanctionsPage'
import { TrackerPage } from './pages/TrackerPage'

export function App() {
  return (
    <BrowserRouter>
      <Shell>
        <Routes>
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/payments" element={<PaymentsPage />} />
          <Route path="/payments/new" element={<CreatePaymentPage />} />
          <Route path="/payments/:ref" element={<PaymentDetailPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/reference-data" element={<ReferenceDataPage />} />
          <Route path="/sanctions" element={<SanctionsPage />} />
          <Route path="/sanctions/:caseId" element={<SanctionsPage />} />
          <Route path="/tracker" element={<TrackerPage />} />
          <Route path="/tracker/:uetr" element={<TrackerPage />} />
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  )
}
