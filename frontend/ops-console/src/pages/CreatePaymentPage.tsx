import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, CardHeader, Chip, Segmented } from '../components/ui'
import { ApiError, newIdempotencyKey, useAccounts, useBankInfo, useCreatePayment } from '../lib/api'
import { BANKS, useBank } from '../lib/banks'
import { BIC_RE, CHARGE_BEARER, STATUS_TONE, formatIban, ibanCheck, reasonText } from '../lib/iso'
import { formatMoney } from '../lib/money'
import type { Account, ChargeBearer, Payment } from '../lib/types'

type Form = {
  debtorAccount: string
  creditorName: string
  creditorAccount: string
  creditorAgentBic: string
  amount: string
  chargeBearer: ChargeBearer
  valueDate: string
  endToEndId: string
  remittance: string
}

type Preset = 'happy' | 'iban' | 'funds' | 'future'

const PRESET_LABEL: Record<Preset, string> = {
  happy: 'Happy path',
  iban: 'Invalid IBAN',
  funds: 'Insufficient funds',
  future: 'Future-dated',
}

function addDays(iso: string, n: number) {
  const d = new Date(iso + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

/** Decimal-string comparison without floats: is a ≤ b? Both non-negative, same currency. */
function lessOrEqual(a: string, b: string) {
  const [ai, af = ''] = a.split('.')
  const [bi, bf = ''] = b.split('.')
  const len = Math.max(af.length, bf.length)
  const x = BigInt(ai + af.padEnd(len, '0'))
  const y = BigInt(bi.replace('-', '') + bf.padEnd(len, '0')) * (b.startsWith('-') ? -1n : 1n)
  return x <= y
}

export function CreatePaymentPage() {
  const { bank } = useBank()
  const businessDate = useBankInfo().data?.businessDate ?? ''
  const customers = (useAccounts('CUSTOMER').data ?? []).filter((a) => a.status === 'ACTIVE')
  const create = useCreatePayment()
  const [preset, setPreset] = useState<Preset>('happy')
  const [form, setForm] = useState<Form | null>(null)
  const [result, setResult] = useState<{ payment: Payment; replayed: boolean } | null>(null)
  // One idempotency key per filled-in form: a double click or a retry after a timeout replays the
  // first result instead of sending a second payment. Any edit makes it a new request → new key.
  const [key, setKey] = useState(() => newIdempotencyKey('ui-payment'))

  const presetForm = (p: Preset, accounts: Account[]): Form => {
    // The happy path needs a debtor who can afford it: the EUR customer with the most available funds.
    const byAvailable = [...accounts.filter((a) => a.currency === 'EUR')].sort((a, b) =>
      lessOrEqual(a.available.amount, b.available.amount) ? 1 : -1,
    )
    const eur = byAvailable[0] ?? accounts[0]
    const base: Form = {
      debtorAccount: eur?.accountNumber ?? '',
      creditorName: 'Harlow & Finch Engineering Ltd',
      creditorAccount: 'GB67 MNPC 4005 1271 5200 93',
      creditorAgentBic: 'MNPCGB2LXXX',
      amount: '48250.00',
      chargeBearer: 'SHAR',
      valueDate: businessDate,
      endToEndId: 'INV-HF-2026-1187',
      remittance: 'INV-HF-2026-1187 CNC spindle assemblies',
    }
    switch (p) {
      case 'iban':
        return { ...base, creditorAccount: 'GB96 MNPC' }
      case 'funds':
        return { ...base, amount: '9999999.00' }
      case 'future':
        return { ...base, valueDate: businessDate ? addDays(businessDate, 1) : '', endToEndId: 'PO-55102' }
      default:
        return base
    }
  }

  const f = form ?? (customers.length && businessDate ? presetForm('happy', customers) : null)
  const set = <K extends keyof Form>(k: K, v: Form[K]) => {
    if (!f) return
    setForm({ ...f, [k]: v })
    setKey(newIdempotencyKey('ui-payment'))
  }
  const debtor = customers.find((a) => a.accountNumber === f?.debtorAccount)

  const checks = useMemo(() => {
    if (!f) return []
    const iban = ibanCheck(f.creditorAccount)
    const looksIban = /^[A-Za-z]{2}/.test(f.creditorAccount.trim())
    const bicOk = BIC_RE.test(f.creditorAgentBic)
    const known = BANKS.find((b) => b.bic.slice(0, 8) === f.creditorAgentBic.slice(0, 8))
    const amountOk = /^\d+(\.\d{1,2})?$/.test(f.amount) && !/^0+(\.0+)?$/.test(f.amount)
    const funds = amountOk && debtor ? lessOrEqual(f.amount, debtor.available.amount) : false
    return [
      { label: 'Creditor name present', ok: f.creditorName.trim().length > 0, detail: `${f.creditorName.length} of 140 characters` },
      {
        label: 'IBAN structure & check digits',
        ok: looksIban ? iban.ok : f.creditorAccount.trim().length > 0,
        detail: looksIban ? iban.msg : 'Not an IBAN — domestic account number',
      },
      {
        label: 'BIC format',
        ok: bicOk,
        warn: bicOk && !known,
        detail: bicOk ? (known ? `${known.name} · ${known.city}` : 'Valid format, unknown institution (directory in M3)') : 'Not ISO 9362',
      },
      {
        label: 'Amount & funds',
        ok: amountOk && funds,
        detail: !amountOk
          ? 'Positive amount with at most 2 decimals'
          : funds
            ? `Available ${formatMoney(debtor!.available, true)} · our fee is added at reservation`
            : `Exceeds available ${debtor ? formatMoney(debtor.available, true) : ''} — will be REJECTED AM04`,
        warn: amountOk && !funds,
      },
      {
        label: 'Value date',
        ok: !!f.valueDate && f.valueDate >= businessDate,
        warn: f.valueDate > businessDate,
        detail: f.valueDate > businessDate ? 'Future date — will be warehoused at FUNDS_RESERVED' : f.valueDate < businessDate ? 'In the past — DT01' : 'Today',
      },
      { label: 'Remittance length', ok: f.remittance.length <= 140, detail: `${f.remittance.length} of 140 characters` },
    ]
  }, [f, debtor, businessDate])

  if (!f) return <div className="text-muted">Loading accounts…</div>

  const blocking = checks.filter((c) => !c.ok && !c.warn).length
  const warns = checks.filter((c) => c.warn).length
  const overall = blocking
    ? { tone: 'err' as const, label: `${blocking} blocking issue${blocking > 1 ? 's' : ''}` }
    : warns
      ? { tone: 'warn' as const, label: `Valid · ${warns} warning${warns > 1 ? 's' : ''}` }
      : { tone: 'ok' as const, label: 'Valid' }

  const submit = () => {
    if (!debtor) return
    create.mutate(
      {
        key,
        body: {
          debtorAccount: f.debtorAccount,
          creditorName: f.creditorName,
          creditorAccount: f.creditorAccount,
          creditorAgentBic: f.creditorAgentBic.toUpperCase(),
          amount: { amount: f.amount, currency: debtor.currency },
          chargeBearer: f.chargeBearer,
          requestedExecutionDate: f.valueDate || undefined,
          endToEndId: f.endToEndId || undefined,
          remittanceInformation: f.remittance || undefined,
        },
      },
      { onSuccess: (r) => setResult({ payment: r.value, replayed: r.replayed }) },
    )
  }

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-2.5">
            <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Create payment</h1>
            <Chip tone="warn">Test tool</Chip>
          </div>
          <p className="mt-0.5 mb-0 text-muted">
            Send a customer credit transfer from {bank.name} into the simulated network. Nothing leaves MiniPay.
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <span className="lbl">Load a scenario</span>
          <Segmented<Preset>
            label="Scenario presets"
            value={preset}
            onChange={(p) => {
              setPreset(p)
              setForm(presetForm(p, customers))
              setKey(newIdempotencyKey('ui-payment'))
              setResult(null)
            }}
            options={(Object.keys(PRESET_LABEL) as Preset[]).map((p) => ({ value: p, label: PRESET_LABEL[p] }))}
          />
        </div>
      </div>

      {result && (
        <div
          className={`flex flex-wrap items-center gap-3 rounded-lg px-3.5 py-2.5 ${
            result.payment.status === 'REJECTED' ? 'bg-err-bg text-err-fg' : 'bg-ok-bg text-ok-fg'
          }`}
        >
          <span className="font-semibold">
            {result.replayed ? 'Already submitted (replayed)' : 'Submitted'} · status {result.payment.status}
          </span>
          {result.payment.rejectionReason && <span>{reasonText(result.payment.rejectionReason)}</span>}
          <span className="mono">UETR {result.payment.uetr}</span>
          <Link to={`/payments/${result.payment.uetr}`} className="ml-auto font-semibold text-current">
            Open payment →
          </Link>
          <Button className="!h-7" onClick={() => setResult(null)}>
            Dismiss
          </Button>
        </div>
      )}

      <div className="flex flex-wrap items-start gap-4">
        <form
          className="flex min-w-0 flex-[1_1_520px] flex-col rounded-lg border border-line bg-surface"
          onSubmit={(e) => e.preventDefault()}
        >
          <CardHeader>
            <h2 className="m-0 text-[13px] font-semibold">Debtor</h2>
            <span className="mono text-[11px] text-muted">Dbtr · DbtrAcct · DbtrAgt</span>
          </CardHeader>
          <div className="grid grid-cols-2 gap-3.5 p-4">
            <div className="field col-span-2">
              <label htmlFor="dacct">Debtor account</label>
              <select id="dacct" className="sel" value={f.debtorAccount} onChange={(e) => set('debtorAccount', e.target.value)}>
                {customers.map((a) => (
                  <option key={a.id} value={a.accountNumber}>
                    {a.accountNumber} · {a.ownerName} · {a.currency}
                  </option>
                ))}
              </select>
              {debtor && (
                <span className="hint">
                  Available <span className="mono text-fg">{formatMoney(debtor.available, true)}</span> · booked{' '}
                  {formatMoney(debtor.booked, true)} · debtor agent <span className="mono">{bank.bic}</span>
                </span>
              )}
            </div>
          </div>

          <CardHeader className="border-t border-line2">
            <h2 className="m-0 text-[13px] font-semibold">Creditor</h2>
            <span className="mono text-[11px] text-muted">Cdtr · CdtrAcct · CdtrAgt</span>
          </CardHeader>
          <div className="grid grid-cols-2 gap-3.5 p-4">
            <div className="field col-span-2">
              <label htmlFor="cname">Creditor name</label>
              <input id="cname" className="input" maxLength={140} value={f.creditorName} onChange={(e) => set('creditorName', e.target.value)} />
            </div>
            <div className="field col-span-2">
              <label htmlFor="iban">Creditor IBAN or account</label>
              <input
                id="iban"
                className={`input mono text-sm tracking-[.04em] ${checks[1]?.ok ? 'good' : 'bad'}`}
                spellCheck={false}
                value={f.creditorAccount}
                onChange={(e) => set('creditorAccount', e.target.value)}
                onBlur={() => /^[A-Za-z]{2}\d{2}/.test(f.creditorAccount) && set('creditorAccount', formatIban(f.creditorAccount))}
              />
              <span className="hint">Spaces are ignored. Server-side IBAN validation per country arrives with reference data (M3).</span>
            </div>
            <div className="field col-span-2">
              <label htmlFor="bic">Creditor agent BIC</label>
              <div className="flex items-center gap-2">
                <input
                  id="bic"
                  list="known-bics"
                  className={`input mono w-[200px] tracking-[.04em] ${checks[2]?.ok ? 'good' : 'bad'}`}
                  spellCheck={false}
                  autoComplete="off"
                  value={f.creditorAgentBic}
                  onChange={(e) => set('creditorAgentBic', e.target.value.toUpperCase())}
                />
                <datalist id="known-bics">
                  {BANKS.filter((b) => b.bic !== bank.bic).map((b) => (
                    <option key={b.bic} value={b.bic}>
                      {b.name} · {b.city}
                    </option>
                  ))}
                </datalist>
                <span className="text-xs text-muted">{checks[2]?.detail}</span>
              </div>
            </div>
          </div>

          <CardHeader className="border-t border-line2">
            <h2 className="m-0 text-[13px] font-semibold">Amount &amp; charges</h2>
            <span className="mono text-[11px] text-muted">InstdAmt · ChrgBr</span>
          </CardHeader>
          <div className="grid grid-cols-2 gap-3.5 p-4">
            <div className="field">
              <label htmlFor="amt">Amount</label>
              <div className="flex gap-1.5">
                <input
                  id="amt"
                  className="input mono min-w-0 flex-1 text-right text-sm"
                  inputMode="decimal"
                  value={f.amount}
                  onChange={(e) => set('amount', e.target.value.trim())}
                />
                <span className="input mono inline-flex w-[64px] items-center justify-center bg-surface2" aria-label="Currency">
                  {debtor?.currency}
                </span>
              </div>
              <span className="hint">Currency follows the debtor account (AM03 otherwise).</span>
            </div>
            <div className="field">
              <label htmlFor="vdate">Requested execution date</label>
              <input id="vdate" type="date" className="input mono" min={businessDate} value={f.valueDate} onChange={(e) => set('valueDate', e.target.value)} />
              <span className="hint">Business date {businessDate} · cut-offs arrive with M3</span>
            </div>
            <div className="field col-span-2">
              <span className="flabel" id="chrg-l">
                Charge bearer
              </span>
              <div className="seg2" role="group" aria-labelledby="chrg-l">
                {(['DEBT', 'CRED', 'SHAR'] as ChargeBearer[]).map((c) => (
                  <button key={c} type="button" className={f.chargeBearer === c ? 'on' : ''} aria-pressed={f.chargeBearer === c} onClick={() => set('chargeBearer', c)}>
                    <span className="mono font-semibold">{c}</span>
                    <span className="text-[11px]">{CHARGE_BEARER[c].short}</span>
                  </button>
                ))}
              </div>
              <span className="hint">{CHARGE_BEARER[f.chargeBearer].long}</span>
            </div>
            <div className="field">
              <label htmlFor="e2e">End-to-end ID</label>
              <input id="e2e" className="input mono" maxLength={35} value={f.endToEndId} onChange={(e) => set('endToEndId', e.target.value)} />
              <span className="hint">Your reference; empty = NOTPROVIDED</span>
            </div>
            <div className="field col-span-2">
              <label htmlFor="rmt">
                Remittance information <span className="font-normal text-muted">(unstructured)</span>
              </label>
              <textarea id="rmt" className="ta mono" rows={2} maxLength={140} value={f.remittance} onChange={(e) => set('remittance', e.target.value)} />
              <span className="hint flex justify-between">
                <span>Maps to RmtInf/Ustrd · MT103 :70: keeps 4 × 35 characters</span>
                <span className="mono">{f.remittance.length}/140</span>
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2.5 rounded-b-lg border-t border-line2 bg-surface2 px-4 py-3.5">
            <span className="text-xs text-muted">
              {debtor && `${debtor.currency} ${f.amount} from ${debtor.accountNumber} to ${f.creditorAgentBic} · ${f.chargeBearer}`}
            </span>
            <Button className="ml-auto" onClick={() => {
              setForm(presetForm(preset, customers))
              setKey(newIdempotencyKey('ui-payment'))
            }}>
              Reset
            </Button>
            <Button primary disabled={blocking > 0 || create.isPending || !debtor} onClick={submit}>
              {create.isPending ? 'Sending…' : 'Send to network'}
            </Button>
          </div>
          {create.error && (
            <div className="border-t border-line2 px-4 py-2.5 text-xs text-err-fg">
              {(create.error as ApiError).message}
            </div>
          )}
        </form>

        <div className="flex min-w-0 flex-[1_1_420px] flex-col gap-4">
          <Card>
            <CardHeader>
              <div>
                <h2 className="m-0 text-[13px] font-semibold">Pre-send checks</h2>
                <span className="text-xs text-muted">The same ideas as the VALIDATED step; the server decides</span>
              </div>
              <Chip tone={overall.tone}>{overall.label}</Chip>
            </CardHeader>
            <div className="flex flex-col gap-[9px] px-4 py-3.5">
              {checks.map((c) => (
                <div key={c.label} className="chk">
                  <span className={`i ${c.ok && !c.warn ? 'ok' : c.warn ? 'warn' : 'err'}`}>{c.ok && !c.warn ? '✓' : c.warn ? '!' : '✕'}</span>
                  <span>
                    <span className="font-medium">{c.label}</span> <span className="text-muted">· {c.detail}</span>
                  </span>
                </div>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-2 border-t border-line2 px-4 py-3 text-xs">
              <span className="lbl">Lifecycle</span>
              <span className="mono text-muted">RECEIVED → VALIDATED → SCREENED → FUNDS_RESERVED → SENT</span>
            </div>
          </Card>
          <Card className="px-4 py-3.5 text-xs text-muted">
            <span className="lbl">pacs.008.001.08 preview</span>
            <p className="mt-1.5 mb-0">
              The ISO 20022 message preview arrives with messaging (M4). Until then the payment hub publishes a{' '}
              <span className="mono">payment.status-changed</span> event to Kafka for every step.
            </p>
          </Card>
          {result && (
            <Card className="px-4 py-3.5 text-xs">
              <span className="lbl">Result</span>
              <div className="mt-1.5 flex items-center gap-2">
                <Chip tone={STATUS_TONE[result.payment.status]}>{result.payment.status}</Chip>
                <span className="text-muted">
                  charges {formatMoney(result.payment.chargesAmount, true)}
                  {result.payment.instructedAgentBic && ` · next agent ${result.payment.instructedAgentBic}`}
                </span>
              </div>
            </Card>
          )}
        </div>
      </div>
    </>
  )
}
