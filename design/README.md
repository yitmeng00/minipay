# Design reference — MiniPay Operations Console

Exported from the Claude Design canvas "MiniPay Operations Console"
(https://claude.ai/code/artifact/70883f27-2578-4ac7-bc65-8b1690859f07).

These `.dc.html` files are **reference only** — the real UI lives in `frontend/ops-console`
and re-implements them with React + Tailwind. `canvas.json` lists the screens:

| # | File | Screen | Powered by milestone |
|---|------|--------|----------------------|
| 1 | Main.dc.html | Overview dashboard | M3 (sanctions M6, liquidity limits M7, recon M8) |
| 2 | Payments.dc.html | Payments list | M2 |
| 3 | PaymentDetail.dc.html | Payment detail (Messages tab: M4) | M2/M4 |
| 4 | CreatePayment.dc.html | Create payment (test tool) | M2/M3 |
| 5 | Sanctions.dc.html | Sanctions screening queue | M6 |
| 6 | ReferenceData.dc.html | Reference data | M3 |
| 7 | Accounts.dc.html | Accounts & ledger | **M1** |
| 8 | Settlement.dc.html | Settlement | M7 |
| 9 | Reconciliation.dc.html | Reconciliation | M8 |

Design tokens (colours, type, spacing) are in the `<style>` block at the top of every file
and are mirrored in `frontend/ops-console/src/index.css`.
