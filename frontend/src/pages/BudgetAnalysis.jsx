import { useMemo, useState } from 'react';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAsync } from '../hooks';
import { crore, croreAxis, croreShort, fiscalYearsDesc, number, pct, rupees, SECTORS, signedPct } from '../format';
import { Card, Empty, ErrorState, InfoTip, Loading, MethodNote, PageHead, Segmented, StatusBadge } from '../components/ui';
import { HistoryChart, ShareBar } from '../components/Charts';
import DeficitRuler from '../components/DeficitRuler';

const fmtByUnit = (unit, v) => {
  switch (unit) {
    case 'INR_CRORE': return crore(v);
    case 'INR': return rupees(v);
    case 'PERCENT': return pct(v);
    case 'PER_1000': return number(v, 1);
    default: return number(v);
  }
};
const axisByUnit = (unit) => (unit === 'INR_CRORE' ? croreAxis : (v) => number(v));

const COMPARE_GROUPS = [
  { title: 'Revenue', codes: ['own_tax_revenue', 'own_non_tax_revenue', 'capital_receipts'] },
  { title: 'Spending', codes: ['revenue_expenditure', 'capital_expenditure', 'capital_outlay', 'social_sector_expenditure', 'health_expenditure', 'interest_payments', 'pension', 'committed_expenditure', 'total_expenditure'] },
  { title: 'Deficits', codes: ['gross_fiscal_deficit', 'revenue_deficit'] },
  { title: 'Economy', codes: ['gsdp_current', 'gsdp_constant', 'per_capita_nsdp_current', 'cpi_inflation', 'population'] },
];

export default function BudgetAnalysis() {
  const { stateCode, stateName } = useApp();
  const years = fiscalYearsDesc(2005, 2024);
  const [year, setYear] = useState('2024-25');
  const [indicator, setIndicator] = useState('revenue_expenditure');
  const [view, setView] = useState('chart');

  const indicators = useAsync(() => api.indicators(), []);
  const base = useAsync(() => api.baseline(stateCode, year), [stateCode, year]);
  const cmp = useAsync(() => api.compare(stateCode, year), [stateCode, year]);
  const trend = useAsync(() => api.records(stateCode, indicator), [stateCode, indicator]);

  const trendOptions = useMemo(() => (indicators.data || [])
    .filter((i) => ['FISCAL', 'ECONOMIC', 'OUTCOME', 'DERIVED'].includes(i.category) && !i.code.startsWith('india_'))
    .sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name)), [indicators.data]);
  const selectedInd = trendOptions.find((i) => i.code === indicator);
  const rowsByCode = useMemo(() => Object.fromEntries((cmp.data?.rows || []).map((r) => [r.indicator, r])), [cmp.data]);

  const b = base.data;
  const sectorItems = b ? ['social_sector', 'capital_outlay', 'other_revenue'].map((k) => ({ sector: k, value: b[k], share: (100 * b[k]) / b.discretionary })) : [];

  return (
    <>
      <PageHead
        eyebrow="Understand"
        title="Budget Analysis"
        lede={`Inspect ${stateName}'s published budget figures for any year, see how they changed from the year before, and follow any indicator over time.`}
        actions={(
          <div className="field">
            <label htmlFor="year">Budget year</label>
            <select id="year" className="select" value={year} onChange={(e) => setYear(e.target.value)}>
              {years.map((y) => <option key={y}>{y}</option>)}
            </select>
          </div>
        )}
      />

      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <Card title={`${year} budget`} sub="Sector split and fiscal position"
          actions={b && <StatusBadge status={b.data_status} />}>
          {base.error && <ErrorState error={base.error} onRetry={base.reload} />}
          {base.loading && !b && <Loading height={200} />}
          {b && !base.error && (
            <div className="stack">
              <div className="grid grid-3">
                <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Total spending</div><div className="figure-value">{croreShort(b.revenue_expenditure + b.capital_outlay)}</div></div>
                <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Interest and pensions</div><div className="figure-value">{croreShort(b.committed)}</div></div>
                <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Own tax revenue</div><div className="figure-value">{croreShort(b.own_tax_revenue)}</div></div>
              </div>
              <div>
                <div className="stat-label" style={{ marginBottom: 8 }}>Reallocatable spending, {croreShort(b.discretionary)}
                  <InfoTip>Total spending minus interest and pensions, which are fixed commitments.</InfoTip></div>
                <ShareBar items={sectorItems} />
                <div className="chart-legend" style={{ marginTop: 8 }}>
                  {sectorItems.map((s) => (
                    <span key={s.sector} className="legend-item num"><span className="legend-dot" style={{ background: SECTORS[s.sector].color }} />
                      {SECTORS[s.sector].label} {pct(s.share)} · {croreShort(s.value)}</span>
                  ))}
                </div>
              </div>
              {b.gfd_pct_gsdp != null
                ? <DeficitRuler marks={[{ label: `${year}`, value: b.gfd_pct_gsdp, color: 'var(--brand)' }]} />
                : <p className="small muted">GSDP is not published for {year}, so the deficit cannot be shown as a share of GSDP.</p>}
            </div>
          )}
        </Card>

        <Card title={`Change from ${cmp.data?.previous_fiscal_year || 'previous year'}`} sub="Published values, previous year → selected year">
          {cmp.error && <ErrorState error={cmp.error} onRetry={cmp.reload} />}
          {cmp.loading && !cmp.data && <Loading height={260} />}
          {cmp.data && !cmp.error && (
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>Indicator</th><th className="r">{cmp.data.previous_fiscal_year}</th><th className="r">{cmp.data.fiscal_year}</th><th className="r">Change</th></tr></thead>
                {COMPARE_GROUPS.map((g) => {
                  const rows = g.codes.map((c) => rowsByCode[c]).filter(Boolean);
                  if (!rows.length) return null;
                  return (
                    <tbody key={g.title}>
                      <tr><td colSpan={4} className="small" style={{ fontWeight: 700, color: 'var(--ink-2)', background: 'var(--surface-2)' }}>{g.title}</td></tr>
                      {rows.map((r) => (
                        <tr key={r.indicator}>
                          <td>{r.indicator_name}</td>
                          <td className="r">{fmtByUnit(r.unit, r.previous_value)}</td>
                          <td className="r">{fmtByUnit(r.unit, r.current_value)}</td>
                          <td className="r" style={{ color: r.change_pct == null ? undefined : 'var(--ink)' }}>{signedPct(r.change_pct)}</td>
                        </tr>
                      ))}
                    </tbody>
                  );
                })}
              </table>
              <p className="small muted" style={{ marginTop: 8 }}>
                Status of figures: {cmp.data.previous_fiscal_year} — {cmp.data.rows.find((r) => r.indicator === 'revenue_expenditure')?.previous_status || 'n/a'};
                {' '}{cmp.data.fiscal_year} — {cmp.data.rows.find((r) => r.indicator === 'revenue_expenditure')?.current_status || 'n/a'}.
                Budget and revised estimates are projections, not outcomes.
              </p>
            </div>
          )}
        </Card>
      </div>

      <Card className="" title="Indicator over time" sub={selectedInd ? `${selectedInd.name} · source: ${selectedInd.source_table}` : ''}
        actions={(
          <div className="form-row">
            <div className="field">
              <label htmlFor="ind" className="visually-hidden">Indicator</label>
              <select id="ind" className="select" value={indicator} onChange={(e) => setIndicator(e.target.value)} style={{ minWidth: 240 }}>
                {['FISCAL', 'ECONOMIC', 'OUTCOME', 'DERIVED'].map((cat) => (
                  <optgroup key={cat} label={{ FISCAL: 'Budget', ECONOMIC: 'Economy', OUTCOME: 'Public services', DERIVED: 'Derived' }[cat]}>
                    {trendOptions.filter((i) => i.category === cat).map((i) => <option key={i.code} value={i.code}>{i.name}</option>)}
                  </optgroup>
                ))}
              </select>
            </div>
            <Segmented label="View" value={view} onChange={setView} options={[{ value: 'chart', label: 'Chart' }, { value: 'table', label: 'Table' }]} />
          </div>
        )}>
        <div style={{ marginTop: 4 }} />
        {trend.error && <ErrorState error={trend.error} onRetry={trend.reload} />}
        {trend.loading && !trend.data && <Loading height={240} />}
        {trend.data && !trend.error && trend.data.length === 0 && (
          <Empty title="No published values">This indicator is not published for {stateName}.</Empty>
        )}
        {trend.data && trend.data.length > 0 && view === 'chart' && (
          <HistoryChart rows={trend.data} unitFormatter={axisByUnit(selectedInd?.unit)} />
        )}
        {trend.data && trend.data.length > 0 && view === 'table' && (
          <div className="table-wrap" style={{ maxHeight: 360, overflowY: 'auto' }}>
            <table className="data">
              <thead><tr><th>Year</th><th className="r">Value</th><th>Status</th></tr></thead>
              <tbody>
                {[...trend.data].reverse().map((r) => (
                  <tr key={r.fiscal_year}><td>{r.fiscal_year}</td><td className="r">{fmtByUnit(r.unit, r.value)}</td><td><StatusBadge status={r.data_status} /></td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <MethodNote title="About these figures">
        <p>Budget figures are from the RBI Handbook of Statistics on Indian States 2024-25 (tables 164–175), compiled
          from state budget documents. 2022-23 values are actuals, 2023-24 are revised estimates and 2024-25 are budget
          estimates.</p>
        <p>GSDP and per-capita income are from MoSPI via the same Handbook (base year 2011-12). Population is derived as
          NSDP ÷ per-capita NSDP. Missing values are shown as “–” and are never filled in.</p>
      </MethodNote>
    </>
  );
}
