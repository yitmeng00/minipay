import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { BankProvider } from './lib/banks'
import { initialTheme } from './lib/theme'
import './index.css'

// Apply the saved theme before first paint to avoid a light flash in dark mode.
document.documentElement.classList.toggle('dark', initialTheme() === 'dark')

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 2_000 } } })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BankProvider>
        <App />
      </BankProvider>
    </QueryClientProvider>
  </StrictMode>,
)
