import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from './components/Shell'
import { AccountsPage } from './pages/AccountsPage'

export function App() {
  return (
    <BrowserRouter>
      <Shell>
        <Routes>
          <Route path="/accounts" element={<AccountsPage />} />
          {/* Overview arrives in M2; until then the ledger is the landing screen. */}
          <Route path="*" element={<Navigate to="/accounts" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  )
}
