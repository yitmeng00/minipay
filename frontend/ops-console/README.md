# MiniPay ops console

React + TypeScript + Vite, styled with Tailwind using the design tokens from `design/` (light and dark).
Data via TanStack Query; tables via TanStack Table v9.

```bash
npm install
npm run dev      # http://localhost:5173 — proxies /bank-a → :8081, /bank-b → :8082, /bank-c → :8083
npm run build    # type-check + production build
```

The bank switcher in the header changes which bank's backend the console talks to.

| Screen | Status |
|---|---|
| Accounts & ledger | M1 — accounts with booked / reserved / available, statement per business date, active holds, trial balance |
| Overview, Payments, Create payment | M2 |
| Reference data | M3 |
| Sanctions queue | M6 |
| Settlement | M7 |
| Reconciliation | M8 |

Conventions: amounts arrive as decimal strings and are formatted without ever becoming JS numbers
(`src/lib/money.ts`); every query key starts with the bank id so banks never share cached data.
