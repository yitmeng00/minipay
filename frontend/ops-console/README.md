# MiniPay ops console

The operations console for the MiniPay simulator: React, TypeScript, Vite and Tailwind (light and dark
themes, tokens in `src/index.css`), data through TanStack Query, tables through TanStack Table v9.

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # type-check + production build
npm run lint     # oxlint
```

The dev server proxies each institution's backend under its own prefix, so the bank switcher in the
header only changes the prefix (no CORS):

| Prefix | Backend |
|---|---|
| `/bank-a` | Bank A, http://localhost:8081 |
| `/bank-b` | Bank B, http://localhost:8082 |
| `/bank-c` | Bank C, http://localhost:8083 |
| `/operator` | the central bank (clearing systems), http://localhost:8084 |

| Screen | What it shows | Screenshot |
|---|---|---|
| Overview | today's payments, throughput, liquidity, cut-offs, sanctions and reconciliation figures, alerts | [overview](../../docs/screenshots/overview.png) |
| Payments, payment detail | the payments list; one payment's lifecycle, history, ledger entries, messages, screening and route | [payment-detail](../../docs/screenshots/payment-detail.png) |
| UETR tracker | a payment's route end to end, from every bank's messages | [tracker](../../docs/screenshots/tracker.png) |
| Sanctions queue | held payments, the hits explained, four-eyes decisions | [sanctions](../../docs/screenshots/sanctions.png) |
| Settlement | T2 queue, MiniPay Net cycle and netting, MiniPay Instant feed (read from the central bank) | [settlement-dns](../../docs/screenshots/settlement-dns.png) |
| Reconciliation | each nostro against its servicer's camt.053: matching, breaks, balance proof | [reconciliation](../../docs/screenshots/reconciliation.png) |
| Create payment | a payment form with presets, service level and the pacs.008 it would produce | [create-payment](../../docs/screenshots/create-payment.png) |
| Accounts & ledger | balances, statements, holds, the integrity check | [accounts](../../docs/screenshots/accounts.png) |
| Reference data | BIC directory, IBAN check, routing rules and route tester, calendars and cut-offs | [reference-data](../../docs/screenshots/reference-data.png) |

The person acting (for compliance decisions and break resolutions) is picked at the bottom of the
sidebar: the simulator has no login. More screenshots: [the main README](../../README.md#the-ops-console).
