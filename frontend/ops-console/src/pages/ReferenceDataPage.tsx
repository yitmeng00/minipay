import { useState } from 'react'
import { Button, Card, CardHeader, Chip, Segmented, type Tone } from '../components/ui'
import {
  useBankInfo,
  useBicDirectory,
  useHolidays,
  useIbanCheck,
  useRefDataSummary,
  useReleases,
  useRoute,
  useRoutingRules,
  useSchemes,
} from '../lib/api'
import { useBank } from '../lib/banks'
import type { Connectivity, IbanCheck, RoutingRule } from '../lib/types'

const CONNECTIVITY: Record<Connectivity, { label: string; tone: Tone }> = {
  CONNECTED: { label: 'Connected', tone: 'ok' },
  PENDING_RMA: { label: 'Pending RMA', tone: 'warn' },
  NOT_CONNECTED: { label: 'Not connected', tone: 'err' },
}

const COUNTRIES = ['MY', 'DE', 'GB', 'BE', 'FR', 'US'] as const
type CountryFilter = 'all' | (typeof COUNTRIES)[number] | 'other'

const PART_CLASS: Record<string, string> = {
  COUNTRY: 's-cc',
  CHECK_DIGITS: 's-ck',
  BANK: 's-bk',
  BRANCH: 's-br',
  ACCOUNT: 's-ac',
  NATIONAL_CHECK: 's-nk',
  BBAN: 's-ac',
}

const IBAN_SAMPLES: [string, string][] = [
  ['DE', 'DE87 5001 0060 0778 8120 03'],
  ['BE', 'BE71 0961 2345 6769'],
  ['GB', 'GB86 MNPC 4005 1288 3344 10'],
  ['FR', 'FR76 3000 6000 0112 3456 7890 189'],
  ['Bad check', 'GB97 MNPC 6016 1331 9268 19'],
]

/** Effective-to is inclusive. A rule is Active on a date inside [from, to]. */
function ruleStatus(r: RoutingRule, on: string): { label: string; tone: Tone } {
  if (r.effectiveFrom > on) return { label: 'Scheduled', tone: 'info' }
  if (r.effectiveTo && r.effectiveTo < on) return { label: 'Expired', tone: 'err' }
  return { label: 'Active', tone: 'ok' }
}

function BicCode({ bic }: { bic: string }) {
  return (
    <span className="mono font-medium">
      <span>{bic.slice(0, 4)}</span>
      <span className="text-info-fg">{bic.slice(4, 6)}</span>
      <span className="text-warn-fg">{bic.slice(6, 8)}</span>
      <span className="text-muted">{bic.slice(8)}</span>
    </span>
  )
}

// ---------------------------------------------------------------- BIC directory

function BicDirectory({ date }: { date: string }) {
  const bics = useBicDirectory(date)
  const [q, setQ] = useState('')
  const [cc, setCc] = useState<CountryFilter>('all')
  const all = bics.data ?? []
  const rows = all.filter((b) => {
    const inCountry = cc === 'all' || (cc === 'other' ? !(COUNTRIES as readonly string[]).includes(b.countryCode) : b.countryCode === cc)
    const needle = q.trim().toLowerCase()
    return inCountry && (!needle || [b.bic, b.institutionName, b.city].some((s) => s.toLowerCase().includes(needle)))
  })
  return (
    <Card className="min-w-0 flex-[999_1_640px] overflow-hidden">
      <div id="bic" />
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">BIC directory</h2>
          <span className="text-xs text-muted">
            {rows.length} of {all.length} institutions on {date} · simulator copy of the BIC directory
          </span>
        </div>
      </CardHeader>
      <div className="flex flex-wrap gap-2.5 border-b border-line2 px-4 py-3">
        <input
          className="input min-w-[200px] flex-[1_1_240px]"
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="BIC, institution or city"
          aria-label="Search BIC directory"
        />
        <Segmented<CountryFilter>
          label="Country"
          value={cc}
          onChange={setCc}
          options={[
            { value: 'all', label: 'All' },
            ...COUNTRIES.map((c) => ({ value: c, label: c })),
            { value: 'other', label: 'Other' },
          ]}
        />
      </div>
      <div className="overflow-x-auto">
        <table className="tbl">
          <thead>
            <tr>
              <th>BIC</th>
              <th>Institution</th>
              <th>Country</th>
              <th>City</th>
              <th>Status</th>
              <th>Currencies</th>
              <th>Valid</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((b) => (
              <tr key={b.bic}>
                <td>
                  <BicCode bic={b.bic} />
                </td>
                <td>{b.institutionName}</td>
                <td className="mono">{b.countryCode}</td>
                <td>{b.city}</td>
                <td>
                  <Chip tone={CONNECTIVITY[b.connectivity].tone}>{CONNECTIVITY[b.connectivity].label}</Chip>
                </td>
                <td className="mono text-muted">{b.currencies.join(' ')}</td>
                <td className="mono text-muted" title={`Introduced in ${b.releaseVersion}`}>
                  {b.validFrom}
                  {b.validTo && <span className="text-warn-fg"> → {b.validTo}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {bics.isSuccess && rows.length === 0 && <div className="p-7 text-center text-muted">No institutions match.</div>}
      </div>
      <div className="flex flex-wrap gap-3.5 border-t border-line2 px-4 py-2 text-[11px] text-muted">
        <span>
          <span className="mono">MNPA</span> institution
        </span>
        <span>
          <span className="mono text-info-fg">MY</span> country
        </span>
        <span>
          <span className="mono text-warn-fg">KL</span> location
        </span>
        <span>
          <span className="mono">XXX</span> branch (XXX = head office)
        </span>
        <span className="ml-auto">Pending RMA = on the network, but no authorised relationship with us yet</span>
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- IBAN validator

function CheckRow({ outcome, name, detail }: IbanCheck['checks'][number]) {
  const cls = outcome === 'PASS' ? 'ok' : outcome === 'WARN' ? 'warn' : 'err'
  return (
    <div className="chk">
      <span className={`i ${cls}`}>{outcome === 'PASS' ? '✓' : '!'}</span>
      <span>
        <span className="font-medium">{name}</span> <span className="text-muted">· {detail}</span>
      </span>
    </div>
  )
}

function IbanValidator({ date }: { date: string }) {
  const [iban, setIban] = useState(IBAN_SAMPLES[2][1])
  const check = useIbanCheck(iban, date).data
  const shown = iban.trim() ? check : undefined
  return (
    <Card className="min-w-0 flex-[1_1_420px]">
      <div id="iban" />
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">IBAN validator</h2>
          <span className="text-xs text-muted">ISO 13616 · rules from the IBAN registry as of {date}</span>
        </div>
      </CardHeader>
      <div className="flex flex-col gap-3.5 p-4">
        <div className="field">
          <label htmlFor="v-iban">IBAN</label>
          <input
            id="v-iban"
            className={`input mono h-[38px] text-[15px] tracking-[.05em] ${shown ? (shown.valid ? 'good' : 'bad') : ''}`}
            value={iban}
            onChange={(e) => setIban(e.target.value)}
            spellCheck={false}
          />
          <div className="mt-0.5 flex flex-wrap gap-1.5">
            <span className="hint self-center">Try</span>
            {IBAN_SAMPLES.map(([label, value]) => (
              <Button key={label} className="!h-6 !px-2 text-[11px]" onClick={() => setIban(value)}>
                {label}
              </Button>
            ))}
          </div>
        </div>
        {shown && (
          <>
            <div className="flex flex-col gap-1.5">
              <span className="lbl">Structure</span>
              <div className="seggrid">
                {shown.parts.map((p) => (
                  <div className="sg" key={p.name}>
                    <span className={`v ${PART_CLASS[p.role] ?? 's-ac'}`}>{p.value}</span>
                    <span className="k">{p.name}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="flex flex-col gap-2 border-t border-line2 pt-3">
              {shown.checks.map((c) => (
                <CheckRow key={c.name} {...c} />
              ))}
            </div>
            <dl className="m-0 grid grid-cols-[110px_minmax(0,1fr)] gap-x-2.5 gap-y-1.5 border-t border-line2 pt-3 text-xs">
              <dt className="text-muted">Print format</dt>
              <dd className="mono m-0">{shown.printFormat || '—'}</dd>
              <dt className="text-muted">Electronic</dt>
              <dd className="mono m-0 [overflow-wrap:anywhere]">{shown.electronic || '—'}</dd>
              <dt className="text-muted">Bank → BIC</dt>
              <dd className="mono m-0">{shown.bic ?? '—'}</dd>
            </dl>
          </>
        )}
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------- routing table

function RoutingTable({ date }: { date: string }) {
  const { bank } = useBank()
  const rules = useRoutingRules().data ?? []
  const releases = useReleases().data ?? []
  const [bic, setBic] = useState('MNPEFRPPXXX')
  const [ccy, setCcy] = useState('EUR')
  const [valueDate, setValueDate] = useState('')
  const on = valueDate || date
  const resolution = useRoute(bic.trim().toUpperCase(), ccy, on).data
  const winner = resolution?.route?.rule.ruleId
  const current = releases[0]
  const changed = current ? rules.filter((r) => r.releaseVersion === current.version).map((r) => r.ruleId) : []
  const currencies = [...new Set(['EUR', 'GBP', 'MYR', 'USD', ...rules.map((r) => r.currency)])].sort()

  const path = (() => {
    const route = resolution?.route
    if (!route) return resolution?.problem ?? ''
    const dest = resolution.creditorAgentBic
    const r = route.rule
    if (r.method === 'SERIAL') return `${bank.bic} → ${route.instructedAgentBic} → ${dest}`
    if (r.method === 'COVER') return `${bank.bic} → ${dest} · cover via ${r.nextHopBic}`
    if (r.method === 'RTGS') return `${bank.bic} → RTGS → ${dest}`
    return `${bank.bic} → ${dest} (direct)`
  })()

  return (
    <Card className="overflow-hidden">
      <div id="routing" />
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Routing table</h2>
          <span className="text-xs text-muted">
            Destination BIC + currency → next hop · lowest priority number wins among rules valid on the value date
          </span>
        </div>
        <span className="text-xs text-muted">Status as of {date}</span>
      </CardHeader>
      <div className="flex flex-wrap items-end gap-2.5 border-b border-line2 bg-surface2 px-4 py-3">
        <span className="lbl self-center">Test a route</span>
        <div className="field">
          <label htmlFor="rt-bic" className="hint">
            Destination BIC
          </label>
          <input id="rt-bic" className="input mono w-[150px]" value={bic} onChange={(e) => setBic(e.target.value)} spellCheck={false} />
        </div>
        <div className="field">
          <label htmlFor="rt-ccy" className="hint">
            Currency
          </label>
          <select id="rt-ccy" className="sel mono w-[86px]" value={ccy} onChange={(e) => setCcy(e.target.value)}>
            {currencies.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="rt-date" className="hint">
            Value date
          </label>
          <input id="rt-date" type="date" className="input mono w-[140px]" value={on} onChange={(e) => setValueDate(e.target.value)} />
        </div>
        {resolution && (
          <div
            className={`flex min-h-8 flex-wrap items-center gap-2.5 rounded-md px-3 py-1 ${
              resolution.route ? 'bg-ok-bg text-ok-fg' : 'bg-err-bg text-err-fg'
            }`}
          >
            <span className="font-semibold">{resolution.route ? `${winner} matches` : 'No usable rule'}</span>
            <span className="mono text-xs">{resolution.route ? path : `${path} → payment would be REJECTED CNOR`}</span>
          </div>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="tbl">
          <thead>
            <tr>
              <th>Rule</th>
              <th>Destination BIC</th>
              <th>Ccy</th>
              <th>Method</th>
              <th>Next hop</th>
              <th>Settlement account</th>
              <th className="!text-right">Priority</th>
              <th>Effective from</th>
              <th>Effective to</th>
              <th>Status</th>
              <th>Release</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => {
              const s = ruleStatus(r, date)
              return (
                <tr key={r.ruleId} className={r.ruleId === winner ? 'selected' : ''}>
                  <td className="mono font-medium">{r.ruleId}</td>
                  <td className="mono">{r.destination}</td>
                  <td className="mono">{r.currency}</td>
                  <td>
                    <span className="mono text-[11px]">{r.method}</span>
                  </td>
                  <td className="mono">{r.nextHopBic ?? <span className="text-muted">creditor agent</span>}</td>
                  <td className="mono text-muted">{r.settlementAccount}</td>
                  <td className="num mono">{r.priority}</td>
                  <td className="mono">{r.effectiveFrom}</td>
                  <td className="mono text-muted">{r.effectiveTo ?? 'open-ended'}</td>
                  <td>
                    <Chip tone={s.tone}>{s.label}</Chip>
                  </td>
                  <td className={`mono text-[11px] ${current && r.releaseVersion === current.version ? 'text-accent' : 'text-muted'}`}>
                    {r.releaseVersion}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {rules.length === 0 && <div className="p-7 text-center text-muted">No routing rules for this bank.</div>}
      </div>
      {current && (
        <div className="flex flex-wrap gap-4.5 border-t border-line2 px-4 py-2.5 text-xs text-muted">
          <span>
            Release <span className="mono text-fg">{current.version}</span> published{' '}
            {new Date(current.publishedAt).toISOString().slice(0, 10)} by <span className="mono">{current.publishedBy}</span>, approved by{' '}
            <span className="mono">{current.approvedBy}</span> · {current.description}
          </span>
          <span>{changed.length ? `Rules introduced: ${changed.join(', ')}` : 'No routing rules changed in this release'}</span>
          <span>Rules are never edited: a change is a new rule, and the old one is end-dated.</span>
        </div>
      )}
    </Card>
  )
}

// ---------------------------------------------------------------- calendars (not in the design: M3 data that needed a home)

function Calendars({ date }: { date: string }) {
  const schemes = useSchemes().data ?? []
  const holidays = (useHolidays(date).data ?? []).slice(0, 12)
  const tz = useBankInfo().data?.timeZone
  const inBankZone = (time: string, zone: string) => {
    if (!tz) return ''
    // The cut-off is wall-clock time in the scheme's zone on the given date; show it in the bank's zone.
    const [h, m] = time.split(':').map(Number)
    const guess = new Date(`${date}T${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00Z`)
    const offset = (z: string) => {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: z, timeZoneName: 'longOffset' }).formatToParts(guess)
      const v = parts.find((p) => p.type === 'timeZoneName')?.value ?? 'GMT'
      const mm = /GMT([+-])(\d{2}):(\d{2})/.exec(v)
      return mm ? (mm[1] === '-' ? -1 : 1) * (Number(mm[2]) * 60 + Number(mm[3])) : 0
    }
    const instant = new Date(guess.getTime() - offset(zone) * 60_000)
    const local = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit' }).format(instant)
    const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(instant)
    return local + (localDate > date ? ' +1' : localDate < date ? ' −1' : '')
  }
  return (
    <Card className="overflow-hidden">
      <div id="calendars" />
      <CardHeader>
        <div>
          <h2 className="m-0 text-sm font-semibold">Settlement calendars &amp; cut-offs</h2>
          <span className="text-xs text-muted">
            Value dates skip weekends and holidays; a payment after the cut-off settles the next business day
          </span>
        </div>
      </CardHeader>
      <div className="flex flex-wrap">
        <div className="min-w-0 flex-[1_1_480px] overflow-x-auto border-r border-line2">
          <table className="tbl">
            <thead>
              <tr>
                <th>Ccy</th>
                <th>Scheme</th>
                <th>Calendar</th>
                <th>Customer cut-off</th>
                <th>In {tz ?? 'bank time'}</th>
              </tr>
            </thead>
            <tbody>
              {schemes.map((s) => (
                <tr key={s.currency}>
                  <td className="mono font-medium">{s.currency}</td>
                  <td>{s.schemeName}</td>
                  <td className="mono text-muted">{s.calendarCode}</td>
                  <td className="mono">
                    {s.cutoffTime.slice(0, 5)} <span className="text-muted">{s.timeZone}</span>
                  </td>
                  <td className="mono">{inBankZone(s.cutoffTime, s.timeZone)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="min-w-0 flex-[1_1_360px] overflow-x-auto">
          <table className="tbl">
            <thead>
              <tr>
                <th>Next holidays</th>
                <th>Calendar</th>
                <th>Name</th>
              </tr>
            </thead>
            <tbody>
              {holidays.map((h) => (
                <tr key={h.calendarCode + h.date}>
                  <td className="mono">{h.date}</td>
                  <td className="mono text-muted">{h.calendarCode}</td>
                  <td>{h.name}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </Card>
  )
}

export function ReferenceDataPage() {
  const businessDate = useBankInfo().data?.businessDate ?? ''
  const summary = useRefDataSummary().data
  const [asOf, setAsOf] = useState('')
  const date = asOf || businessDate
  if (!date) return <div className="text-muted">Loading reference data…</div>
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-[-0.01em]">Reference data</h1>
          <p className="mt-0.5 mb-0 text-muted">
            BIC directory, IBAN structures and routing rules used by VALIDATED and SENT · data version{' '}
            <span className="mono text-fg">{summary?.dataVersion ?? '…'}</span>
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <div className="field">
            <label htmlFor="as-of" className="hint" title="Reference data is effective-dated: see what applied, or will apply, on any date">
              As of
            </label>
            <input id="as-of" type="date" className="input mono h-8 w-[150px]" value={date} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          {asOf && asOf !== businessDate && <Button onClick={() => setAsOf('')}>Back to today</Button>}
          <nav aria-label="Sections" className="flex gap-2">
            <a className="inline-flex h-8 items-center rounded-md border border-line bg-surface px-3 font-medium text-fg no-underline hover:bg-surface2" href="#bic">
              BIC directory
            </a>
            <a className="inline-flex h-8 items-center rounded-md border border-line bg-surface px-3 font-medium text-fg no-underline hover:bg-surface2" href="#iban">
              IBAN validator
            </a>
            <a className="inline-flex h-8 items-center rounded-md border border-line bg-surface px-3 font-medium text-fg no-underline hover:bg-surface2" href="#routing">
              Routing table
            </a>
          </nav>
        </div>
      </div>
      <div className="flex flex-wrap items-start gap-4">
        <BicDirectory date={date} />
        <IbanValidator date={date} />
      </div>
      <RoutingTable date={date} />
      <Calendars date={date} />
    </>
  )
}

