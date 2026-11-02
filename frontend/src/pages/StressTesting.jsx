import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAction, useAsync } from '../hooks';
import { crore, croreShort, dateTime, number, pct, rupees, signedPct } from '../format';
import { Card, Empty, ErrorState, InfoTip, Loading, MethodNote, PageHead, Segmented, StatusBadge } from '../components/ui';
import DeficitRuler, { aboveLimit } from '../components/DeficitRuler';

const SHOCKS = [
  { key: 'REVENUE_SHOCK', title: 'Revenue shortfall', desc: 'Own tax collections fall as much as in the state’s worst year on record.' },
  { key: 'INFLATION', title: 'High inflation', desc: 'Prices rise at the highest rate seen across states since 2014-15, eroding what the budget buys.' },
  { key: 'POPULATION', title: 'Faster population growth', desc: 'More people to serve with the same budget.' },
  { key: 'DOWNTURN', title: 'Economic downturn', desc: 'The state economy grows as slowly as in its worst year; tax revenue falls with it.' },
  { key: 'COMBINED', title: 'All of the above', desc: 'Downturn, inflation and population growth together, with the larger of the two revenue losses.' },
];
const SHOCK_TITLE = Object.fromEntries(SHOCKS.map((s) => [s.key, s.title]));
const OVERRIDES = [
  { key: 'revenue_shock_pct', label: 'Change in own tax revenue (%)', basis: 'revenue_shock_basis' },
  { key: 'gsdp_shortfall_pct', label: 'Change in state GDP vs normal (%)', basis: 'gsdp_basis' },
  { key: 'inflation_pct', label: 'Inflation rate (%)', basis: 'inflation_basis' },
  { key: 'population_excess_growth_pct', label: 'Extra population growth (%)', basis: 'population_basis' },
];

const val = (results, scenario, metric, key = 'stressed') => results.find((r) => r.scenario === scenario && r.metric === metric)?.[key];

function ShockCard({ name, results }) {
  const normal = val(results, name, 'gfd_pct_gsdp', 'normal');
  const stressed = val(results, name, 'gfd_pct_gsdp');
  const gap = val(results, name, 'adjustment_needed_for_frbm');
  const gapNormal = val(results, name, 'adjustment_needed_for_frbm', 'normal');
  const otrN = val(results, name, 'own_tax_revenue', 'normal');
  const otrS = val(results, name, 'own_tax_revenue');
  const socN = val(results, name, 'real_per_capita_social_sector', 'normal');
  const socS = val(results, name, 'real_per_capita_social_sector');
  const breach = aboveLimit(stressed);
  return (
    <Card title={SHOCK_TITLE[name]} actions={breach ? <span className="badge bad">Breaches 3% limit</span> : <span className="badge good">Stays within limit</span>}>
      <DeficitRuler title="Fiscal deficit (% of GSDP)" marks={[
        { label: 'Normal', value: normal, color: '#9fb3c8' },
        { label: 'Under stress', value: stressed, color: breach ? 'var(--bad)' : 'var(--brand)' },
      ]} />
      <table className="data" style={{ marginTop: 14 }}>
        <tbody>
          <tr><td>Own tax revenue</td><td className="r">{croreShort(otrN)} → <strong>{croreShort(otrS)}</strong></td><td className="r muted">{signedPct(otrN ? (100 * (otrS - otrN)) / otrN : null)}</td></tr>
          <tr><td>Social spending per person, in today's prices</td><td className="r">{rupees(socN)} → <strong>{rupees(socS)}</strong></td><td className="r muted">{signedPct(socN ? (100 * (socS - socN)) / socN : null)}</td></tr>
          <tr><td>Cut or extra revenue needed to return to 3% <InfoTip>How much spending would have to be cut, or revenue raised, to bring borrowing back to 3% of GSDP.</InfoTip></td>
            <td className="r" colSpan={2}><strong style={{ color: gap > 0.5 ? 'var(--bad)' : undefined }}>{gap > 0.5 ? crore(gap) : 'None needed'}</strong>
              {gapNormal > 0.5 && <span className="muted small"> (already {crore(gapNormal)} without stress)</span>}</td></tr>
        </tbody>
      </table>
    </Card>
  );
}

export default function StressTesting() {
  const { stateCode, stateName } = useApp();
  const [params] = useSearchParams();
  const [source, setSource] = useState(params.get('scenario') ? 'SCENARIO' : params.get('optimization') ? 'OPTIMIZATION' : 'CURRENT');
  const [sourceId, setSourceId] = useState(params.get('scenario') || params.get('optimization') || '');
  const [chosen, setChosen] = useState(SHOCKS.map((s) => s.key));
  const [overrides, setOverrides] = useState({});
  const [result, setResult] = useState(null);
  const scenarios = useAsync(() => api.scenarios(stateCode), [stateCode]);
  const optimizations = useAsync(() => api.optimizations(stateCode), [stateCode]);
  const history = useAsync(() => api.stressTests(stateCode), [stateCode]);
  const runAction = useAction((body) => api.stressTest(body));

  const sourceOptions = useMemo(() => (source === 'SCENARIO'
    ? (scenarios.data || []).map((s) => ({ id: String(s.id), label: `${s.name} (${s.fiscal_year})` }))
    : (optimizations.data || []).filter((o) => o.status === 'OPTIMAL').map((o) => ({ id: String(o.id), label: `Run #${o.id}: ${o.objective === 'MIN_ADJUSTMENT' ? 'smallest change' : o.objective === 'MAX_CAPITAL_OUTLAY' ? 'max infrastructure' : o.objective} (${o.fiscal_year})` }))),
  [source, scenarios.data, optimizations.data]);

  useEffect(() => { setResult(null); }, [stateCode]);
  useEffect(() => {
    if (source !== 'CURRENT' && sourceOptions.length && !sourceOptions.some((o) => o.id === sourceId)) setSourceId(sourceOptions[0].id);
  }, [source, sourceOptions, sourceId]);

  const needsSource = source !== 'CURRENT' && !sourceId;
  const run = async () => {
    const ov = Object.fromEntries(Object.entries(overrides).filter(([, v]) => v !== '' && v != null).map(([k, v]) => [k, Number(v)]));
    const res = await runAction.run({
      state_code: stateCode, source_type: source, source_id: source === 'CURRENT' ? undefined : Number(sourceId),
      scenarios: chosen, overrides: Object.keys(ov).length ? ov : undefined,
    });
    if (res) { setResult(res); history.reload(); }
  };
  const r = result?.result;

  return (
    <>
      <PageHead eyebrow="Plan" title="Stress Testing"
        lede={`Check whether ${stateName}'s budget, or an allocation you created, still holds if revenue falls, prices rise or the economy slows. Shock sizes come from the state's own worst years on record.`} />

      <div className="grid grid-side">
        <Card title="Choose what to test">
          <div className="stack">
            <div className="field">
              <span className="field-label">Allocation</span>
              <Segmented label="Allocation to test" value={source} onChange={(v) => { setSource(v); setSourceId(''); }}
                options={[{ value: 'CURRENT', label: 'Current budget' }, { value: 'SCENARIO', label: 'Scenario' }, { value: 'OPTIMIZATION', label: 'Optimization' }]} />
              {source !== 'CURRENT' && (sourceOptions.length ? (
                <select className="select" aria-label="Saved item" value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
                  {sourceOptions.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              ) : (
                <div className="small muted">No saved {source === 'SCENARIO' ? 'scenarios' : 'optimization results'} for {stateName} yet.</div>
              ))}
            </div>
            <div className="field">
              <span className="field-label">Adverse conditions</span>
              <div className="choice-list">
                {SHOCKS.map((s) => (
                  <label key={s.key} className={`choice ${chosen.includes(s.key) ? 'selected' : ''}`}>
                    <input type="checkbox" checked={chosen.includes(s.key)} style={{ marginTop: 3, accentColor: 'var(--brand)' }}
                      onChange={(e) => setChosen(e.target.checked ? [...chosen, s.key] : chosen.filter((x) => x !== s.key))} />
                    <span><span className="choice-title">{s.title}</span><br /><span className="choice-desc">{s.desc}</span></span>
                  </label>
                ))}
              </div>
            </div>
            <details>
              <summary className="small" style={{ cursor: 'pointer', fontWeight: 600, color: 'var(--ink-2)' }}>Customise shock sizes (optional)</summary>
              <div className="stack" style={{ gap: 8, marginTop: 10 }}>
                <span className="small muted">Leave blank to use the data-based size{r ? ' shown in the results' : ''}.</span>
                {OVERRIDES.map((o) => (
                  <div key={o.key} className="field">
                    <label htmlFor={o.key}>{o.label}</label>
                    <input id={o.key} className="input" type="number" step="0.1"
                      placeholder={r ? `default ${number(r.calibration[o.key], 1)}` : 'data-based default'}
                      value={overrides[o.key] ?? ''} onChange={(e) => setOverrides({ ...overrides, [o.key]: e.target.value })} />
                  </div>
                ))}
              </div>
            </details>
            <button className="btn" onClick={run} disabled={runAction.running || chosen.length === 0 || needsSource}>
              {runAction.running ? 'Running…' : 'Run stress test'}
            </button>
            {chosen.length === 0 && <div className="small muted">Select at least one condition.</div>}
            <ErrorState error={runAction.error} />
          </div>
        </Card>

        <div className="stack">
          {!r && <Card><Empty title="No stress test run yet">Pick an allocation and the conditions to test, then select <strong>Run stress test</strong>.</Empty></Card>}
          {r && (
            <>
              <Card title={`Results for ${r.fiscal_year}`}
                sub={result.source_type === 'CURRENT' ? 'Current budget' : `${result.source_type === 'SCENARIO' ? 'Scenario' : 'Optimization run'} #${result.source_id}`}
                actions={<StatusBadge status={r.baseline_data_status} />}>
                <div className="small">
                  Spending is kept at the tested allocation; only revenue, prices, the economy and population change.
                </div>
              </Card>
              <div className="stack">
                {chosen.filter((c) => r.results.some((x) => x.scenario === c)).map((c) => <ShockCard key={c} name={c} results={r.results} />)}
              </div>
              <Card title="How big are the shocks?" sub="Each size comes from this state's own history unless you changed it">
                <table className="data">
                  <tbody>
                    {OVERRIDES.map((o) => (
                      <tr key={o.key}><td>{o.label.replace(' (%)', '')}</td><td className="r num"><strong>{number(r.calibration[o.key], 1)}%</strong></td>
                        <td className="small muted">{r.calibration[o.basis]}</td></tr>
                    ))}
                    <tr><td>Tax response to the economy <InfoTip>When state GDP falls 1%, own tax revenue falls by about this many percent (estimated across all states, 2012-13 to 2022-23).</InfoTip></td>
                      <td className="r num"><strong>{r.calibration.tax_buoyancy.buoyancy.toFixed(2)}</strong></td>
                      <td className="small muted">90% range {r.calibration.tax_buoyancy.ci_low.toFixed(2)}–{r.calibration.tax_buoyancy.ci_high.toFixed(2)}, {r.calibration.tax_buoyancy.n_obs} state-years</td></tr>
                  </tbody>
                </table>
              </Card>
            </>
          )}
        </div>
      </div>

      <Card title="Previous stress tests" sub={`For ${stateName}`}>
        {history.loading && !history.data && <Loading height={60} />}
        {history.error && <ErrorState error={history.error} onRetry={history.reload} />}
        {history.data?.length === 0 && <Empty title="No stress tests yet">Tests you run appear here.</Empty>}
        {history.data?.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Allocation tested</th><th>Year</th><th className="r">Worst deficit (% GSDP)</th><th>Created</th></tr></thead>
              <tbody>
                {history.data.map((h) => {
                  const worst = Math.max(...h.result.results.filter((x) => x.metric === 'gfd_pct_gsdp').map((x) => x.stressed));
                  return (
                    <tr key={h.id} className={result?.id === h.id ? 'sel' : ''}>
                      <td><button className="btn-link" onClick={() => { setResult(h); setChosen([...new Set(h.result.results.map((x) => x.scenario))]); }}>
                        {h.source_type === 'CURRENT' ? 'Current budget' : `${h.source_type === 'SCENARIO' ? 'Scenario' : 'Optimization'} #${h.source_id}`}</button></td>
                      <td>{h.fiscal_year}</td>
                      <td className="r" style={{ color: aboveLimit(worst) ? 'var(--bad)' : undefined }}>{pct(worst, 2)}</td>
                      <td className="small muted">{dateTime(h.created_at)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <MethodNote>
        <p>Revenue shortfall: the state's largest year-on-year fall in own tax revenue in actual years (2005-06 to 2022-23).
          Downturn: the state's worst real GSDP growth compared with its average; tax revenue falls by the estimated tax
          response × the GSDP shortfall.</p>
        <p>Inflation: the 95th percentile of state CPI inflation from 2014-15 to 2024-25, applied to the real value of spending.
          Population: the 95th percentile of state population growth minus the state's own average.</p>
        <p>The combined case uses the larger of the two revenue losses rather than adding them, to avoid counting the same
          fall twice. Results follow accounting identities; they are not forecasts of what will happen.</p>
      </MethodNote>
    </>
  );
}
