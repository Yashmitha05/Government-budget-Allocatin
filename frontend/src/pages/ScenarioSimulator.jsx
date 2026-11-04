import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAction, useAsync } from '../hooks';
import { crore, croreShort, dateTime, fiscalYearsDesc, pct, rupees, SECTORS, signedCrore, signedPct } from '../format';
import { Card, Empty, ErrorState, InfoTip, Loading, MethodNote, PageHead, StatusBadge, Toast } from '../components/ui';
import DeficitRuler from '../components/DeficitRuler';
import { AllocationCompareChart } from '../components/Charts';
import IndicatorEffects from '../components/IndicatorEffects';
import { useAdminKey } from '../components/AdminKey';

const TYPES = [
  { value: 'BALANCED', title: 'Balanced', desc: 'Return each sector to its average share of the last five actual years.' },
  { value: 'HEALTH_PRIORITY', title: 'Health priority', desc: 'Health +10%, paid for by trimming capital outlay and other spending in proportion.' },
  { value: 'SOCIAL_PRIORITY', title: 'Education & social priority', desc: 'Social sector +10%, paid for by the other two sectors.' },
  { value: 'INFRASTRUCTURE_PRIORITY', title: 'Infrastructure priority', desc: 'Capital outlay +10%, paid for by the other two sectors.' },
  { value: 'CUSTOM', title: 'Custom', desc: 'Set your own percentage change for each sector.' },
];
const TYPE_TITLE = Object.fromEntries(TYPES.map((t) => [t.value, t.title]));
const SLIDERS = [
  { key: 'social_sector', label: 'Social sector', help: 'Education, health, welfare, housing, water' },
  { key: 'health', label: 'of which health', help: 'Changes only the health part of social spending' },
  { key: 'capital_outlay', label: 'Capital outlay', help: 'Roads, irrigation, buildings, other assets' },
  { key: 'other_revenue', label: 'Other spending', help: 'Administration, police, economic services, subsidies' },
];
const ZERO = { social_sector: 0, health: 0, capital_outlay: 0, other_revenue: 0 };

function ScenarioResult({ s }) {
  const r = s.result;
  const fx = r.fiscal_effects;
  const changed = r.sectors.some((x) => Math.abs(x.change) > 0.5);
  const totalChange = fx.scenario.discretionary - fx.baseline.discretionary;
  return (
    <div className="stack">
      <Card title={s.name} sub={`${TYPE_TITLE[s.scenario_type]} · ${r.description}`}
        actions={<StatusBadge status={r.baseline_data_status} />}>
        <DeficitRuler marks={[
          { label: `Current ${r.fiscal_year} budget`, value: fx.baseline.gfd_pct_gsdp, color: '#9fb3c8' },
          { label: 'This scenario', value: fx.scenario.gfd_pct_gsdp, color: 'var(--brand)' },
        ]} />
        <div className="grid grid-3" style={{ marginTop: 18, gap: 10 }}>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Change in total spending</div><div className="figure-value num">{signedCrore(totalChange)}</div>
            <div className="figure-note">{Math.abs(totalChange) < 1 ? 'Money moved between sectors only' : 'Changes borrowing one-for-one'}</div></div>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Borrowing (fiscal deficit)</div><div className="figure-value">{croreShort(fx.scenario.gross_fiscal_deficit)}</div>
            <div className="figure-note">was {croreShort(fx.baseline.gross_fiscal_deficit)}</div></div>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Revenue deficit <InfoTip>Day-to-day spending minus revenue. A positive value means borrowing pays for running costs, not just assets.</InfoTip></div>
            <div className="figure-value">{croreShort(fx.scenario.revenue_deficit)}</div>
            <div className="figure-note">was {croreShort(fx.baseline.revenue_deficit)}</div></div>
        </div>
      </Card>

      <Card title="Allocation by sector" sub="Current budget compared with this scenario">
        {!changed && <div className="callout info" style={{ marginBottom: 12 }}>This scenario leaves the allocation almost unchanged; the current split already matches it.</div>}
        <AllocationCompareChart rows={r.sectors.map((x) => ({ sector: x.sector, a: x.baseline, b: x.scenario }))}
          aLabel="Current budget" bLabel="Scenario" />
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table className="data">
            <thead><tr><th>Sector</th><th className="r">Current</th><th className="r">Scenario</th><th className="r">Change</th>
              <th className="r">Per person</th><th className="r">All-state median <InfoTip>Median per-person spending on this sector across states in the same year.</InfoTip></th></tr></thead>
            <tbody>
              {r.sectors.map((x) => (
                <tr key={x.sector}>
                  <td>{SECTORS[x.sector].label}</td>
                  <td className="r">{crore(x.baseline)}</td>
                  <td className="r">{crore(x.scenario)}</td>
                  <td className="r">{signedPct(x.change_pct)}</td>
                  <td className="r">{rupees(x.per_capita_scenario)}</td>
                  <td className="r">{rupees(x.per_capita_all_state_median)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card><IndicatorEffects effects={r.indicator_effects} /></Card>
      <div className="small muted">
        Next: check how this allocation holds up in a downturn on the <Link to={`/stress-testing?scenario=${s.id}`}>Stress Testing</Link> page.
      </div>
    </div>
  );
}

export default function ScenarioSimulator() {
  const { stateCode, stateName } = useApp();
  const [type, setType] = useState('HEALTH_PRIORITY');
  const [year, setYear] = useState('');
  const [name, setName] = useState('');
  const [adj, setAdj] = useState(ZERO);
  const [current, setCurrent] = useState(null);
  const [selected, setSelected] = useState([]);
  const [toast, setToast] = useState('');
  const [deleteError, setDeleteError] = useState(null);
  const admin = useAdminKey();
  const saved = useAsync(() => api.scenarios(stateCode), [stateCode]);
  const create = useAction((body) => api.createScenario(body));
  const compare = useAction((ids) => api.compareScenarios(ids));
  const open = useAction((id) => api.scenario(id));

  useEffect(() => { setCurrent(null); setSelected([]); compare.reset(); }, [stateCode]); // eslint-disable-line react-hooks/exhaustive-deps

  const defaultName = `${TYPE_TITLE[type]} – ${year || 'latest budget'}`;
  const customEmpty = type === 'CUSTOM' && Object.values(adj).every((v) => v === 0);

  const run = async () => {
    const body = { name: name.trim() || defaultName, state_code: stateCode, scenario_type: type, fiscal_year: year || undefined };
    if (type === 'CUSTOM') body.adjustments_pct = Object.fromEntries(Object.entries(adj).filter(([, v]) => v !== 0));
    const res = await create.run(body);
    if (res) { setCurrent(res); setToast('Scenario saved'); saved.reload(); setName(''); }
  };

  const remove = async (id) => {
    if (!window.confirm('Delete this saved scenario? This cannot be undone.')) return;
    setDeleteError(null);
    try { await admin.withAdminKey((key) => api.deleteScenario(id, key)); } catch (e) { setDeleteError(e); return; }
    if (current?.id === id) setCurrent(null);
    setSelected((s) => s.filter((x) => x !== id));
    saved.reload();
  };

  const view = async (id) => { const s = await open.run(id); if (s) setCurrent(s); window.scrollTo({ top: 0, behavior: 'smooth' }); };

  return (
    <>
      <PageHead eyebrow="Plan" title="Scenario Simulator"
        lede={`Try a different split of ${stateName}'s budget and see what it does to borrowing and to public-service indicators. Every scenario you run is saved so you can compare them.`} />

      <div className="grid grid-side">
        <Card title="Build a scenario">
          <div className="stack">
            <div className="field">
              <label htmlFor="sc-year">Start from budget year</label>
              <select id="sc-year" className="select" value={year} onChange={(e) => setYear(e.target.value)}>
                <option value="">Latest budget (2024-25)</option>
                {fiscalYearsDesc(2011, 2023).map((y) => <option key={y}>{y}</option>)}
              </select>
            </div>
            <div className="field">
              <span className="field-label">Type of change</span>
              <div className="choice-list" role="radiogroup" aria-label="Type of change">
                {TYPES.map((t) => (
                  <button key={t.value} type="button" role="radio" aria-checked={type === t.value}
                    className={`choice ${type === t.value ? 'selected' : ''}`} onClick={() => setType(t.value)}>
                    <span><span className="choice-title">{t.title}</span><br /><span className="choice-desc">{t.desc}</span></span>
                  </button>
                ))}
              </div>
            </div>
            {type === 'CUSTOM' && (
              <div className="stack" style={{ gap: 12 }}>
                {SLIDERS.map((sl) => (
                  <div key={sl.key} className="field">
                    <label htmlFor={`sl-${sl.key}`}>{sl.label} <span className="muted" style={{ fontWeight: 400 }}>· {sl.help}</span></label>
                    <div className="slider-row">
                      <input id={`sl-${sl.key}`} type="range" min={-30} max={30} step={1} value={adj[sl.key]}
                        onChange={(e) => setAdj({ ...adj, [sl.key]: Number(e.target.value) })} />
                      <span className="slider-value">{signedPct(adj[sl.key], 0)}</span>
                    </div>
                  </div>
                ))}
                <div className="small muted">Increases that are not offset elsewhere raise total spending, and the state must borrow the difference.</div>
                <button type="button" className="btn-link small" style={{ alignSelf: 'flex-start' }} onClick={() => setAdj(ZERO)}>Reset all to 0%</button>
              </div>
            )}
            <div className="field">
              <label htmlFor="sc-name">Scenario name</label>
              <input id="sc-name" className="input" value={name} placeholder={defaultName} maxLength={120} onChange={(e) => setName(e.target.value)} />
            </div>
            <button className="btn" onClick={run} disabled={create.running || customEmpty}>
              {create.running ? 'Running…' : 'Run and save scenario'}
            </button>
            {customEmpty && <div className="small muted">Move at least one slider to run a custom scenario.</div>}
            <ErrorState error={create.error} />
          </div>
        </Card>

        <div>
          {open.running && <Loading height={300} />}
          {!open.running && current && <ScenarioResult s={current} />}
          {!open.running && !current && (
            <Card><Empty title="No scenario selected yet">
              Choose a type of change and select <strong>Run and save scenario</strong>, or open one of your saved scenarios below.
            </Empty></Card>
          )}
        </div>
      </div>

      <Card title="Saved scenarios" sub={`For ${stateName}. Tick two or more to compare them side by side.`}
        actions={<button className="btn btn-secondary btn-small" disabled={selected.length < 2 || selected.length > 6 || compare.running}
          onClick={() => compare.run(selected)}>Compare selected ({selected.length})</button>}
        className="">
        {saved.error && <ErrorState error={saved.error} onRetry={saved.reload} />}
        <ErrorState error={deleteError} />
        {saved.loading && !saved.data && <Loading height={80} />}
        {saved.data?.length === 0 && <Empty title="No saved scenarios">Scenarios you run appear here.</Empty>}
        {saved.data?.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th><span className="visually-hidden">Select</span></th><th>Name</th><th>Type</th><th>Year</th>
                <th className="r">Deficit (% GSDP)</th><th>3% limit</th><th>Created</th><th /></tr></thead>
              <tbody>
                {saved.data.map((s) => (
                  <tr key={s.id} className={current?.id === s.id ? 'sel' : ''}>
                    <td><input type="checkbox" aria-label={`Select ${s.name}`} checked={selected.includes(s.id)}
                      onChange={(e) => setSelected(e.target.checked ? [...selected, s.id] : selected.filter((x) => x !== s.id))} /></td>
                    <td><button className="btn-link" onClick={() => view(s.id)}>{s.name}</button></td>
                    <td>{TYPE_TITLE[s.scenario_type]}</td>
                    <td>{s.fiscal_year}</td>
                    <td className="r">{pct(s.baseline_gfd_pct_gsdp, 2)} → {pct(s.scenario_gfd_pct_gsdp, 2)}</td>
                    <td>{s.breaches_frbm_limit ? <span className="badge bad">Above</span> : <span className="badge good">Within</span>}</td>
                    <td className="small muted">{dateTime(s.created_at)}</td>
                    <td><button className="btn-link small" style={{ color: 'var(--bad)' }} onClick={() => remove(s.id)}>Delete</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <ErrorState error={compare.error} />
        {compare.result && (
          <div style={{ marginTop: 18 }}>
            <h3 style={{ marginBottom: 8 }}>Side-by-side comparison</h3>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>Measure</th>{compare.result.map((s) => <th key={s.id} className="r">{s.name}</th>)}</tr></thead>
                <tbody>
                  {Object.keys(SECTORS).map((k) => (
                    <tr key={k}><td>{SECTORS[k].label}</td>
                      {compare.result.map((s) => {
                        const x = s.result.sectors.find((y) => y.sector === k);
                        return <td key={s.id} className="r">{crore(x?.scenario)} <span className="muted small">({signedPct(x?.change_pct)})</span></td>;
                      })}</tr>
                  ))}
                  <tr><td>Total spending change</td>{compare.result.map((s) => <td key={s.id} className="r">{signedCrore(s.result.fiscal_effects.scenario.discretionary - s.result.fiscal_effects.baseline.discretionary)}</td>)}</tr>
                  <tr><td>Fiscal deficit (% GSDP)</td>{compare.result.map((s) => <td key={s.id} className="r"><strong>{pct(s.result.fiscal_effects.scenario.gfd_pct_gsdp, 2)}</strong></td>)}</tr>
                  <tr><td>Within 3% limit</td>{compare.result.map((s) => <td key={s.id} className="r">{s.result.fiscal_effects.scenario.breaches_frbm_gfd_limit ? <span className="badge bad">No</span> : <span className="badge good">Yes</span>}</td>)}</tr>
                  <tr><td>Budget year</td>{compare.result.map((s) => <td key={s.id} className="r">{s.fiscal_year}</td>)}</tr>
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Card>

      <MethodNote>
        <p>Scenarios change the three reallocatable sectors: social sector, capital outlay and other revenue spending.
          Interest and pensions are fixed. Revenue is held at the budget level, so every extra rupee of spending is
          borrowed: the fiscal deficit changes one-for-one.</p>
        <p>Presets move money between sectors and keep the total unchanged. Health figures after 2019-20 are estimated from
          the last published health share of social spending.</p>
        <p>Effects on indicators come from a two-way fixed-effects panel regression with a state-clustered bootstrap. They are
          historical associations, not proof of cause and effect.</p>
      </MethodNote>
      <Toast message={toast} onDone={() => setToast('')} />
    </>
  );
}
