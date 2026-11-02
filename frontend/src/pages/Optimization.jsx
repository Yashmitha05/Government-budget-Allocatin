import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAction, useAsync } from '../hooks';
import { crore, croreShort, dateTime, fiscalYearsDesc, pct, rupees, SECTORS, signedCrore, signedPct } from '../format';
import { Card, Empty, ErrorState, InfoTip, Loading, MethodNote, PageHead, Segmented, StatusBadge } from '../components/ui';
import DeficitRuler from '../components/DeficitRuler';
import { AllocationCompareChart } from '../components/Charts';

const GOALS = [
  { value: 'MIN_ADJUSTMENT', title: 'Smallest change that meets the rules',
    desc: 'Keeps the current split wherever possible and changes it only as much as the limits below require.' },
  { value: 'MAX_CAPITAL_OUTLAY', title: 'Maximise infrastructure spending',
    desc: 'Puts as much as the limits allow into capital outlay (roads, irrigation, buildings).' },
  { value: 'OUTCOME', title: 'Best predicted public-service results',
    desc: 'Allocates by the measured effect of spending on indicators such as infant mortality.' },
];
const GOAL_TITLE = Object.fromEntries(GOALS.map((g) => [g.value, g.title]));
const FLOORS = [
  { value: 'NONE', label: 'No floor' },
  { value: 'CURRENT', label: 'No cut per person' },
  { value: 'MEDIAN', label: 'At least the all-state median' },
];

function Result({ run }) {
  if (run.status === 'INFEASIBLE') {
    return (
      <Card title="No allocation meets all the limits" actions={<span className="badge bad">Not possible</span>}>
        <div className="callout bad">
          <strong>The limits you set conflict with each other.</strong>
          <ul>{(run.details?.length ? run.details : [run.message]).map((d) => <li key={d}>{d}</li>)}</ul>
        </div>
        <div className="small" style={{ marginTop: 12 }}>
          Try one of these: set a larger total budget, turn off the 3% limit to see the unconstrained answer, choose a
          lower social-spending floor, or widen the sector ranges under “Sector limits”.
        </div>
      </Card>
    );
  }
  const r = run.result;
  const fx = r.fiscal_effects;
  const totalChange = r.total_budget - r.current_total;
  return (
    <div className="stack">
      <Card title="Recommended allocation" sub={`${GOAL_TITLE[run.objective]} · ${r.fiscal_year}`}
        actions={<StatusBadge status={r.baseline_data_status} />}>
        <DeficitRuler marks={[
          { label: 'Current budget', value: fx.current.gfd_pct_gsdp, color: '#9fb3c8' },
          { label: 'Recommended', value: fx.recommended.gfd_pct_gsdp, color: 'var(--brand)' },
        ]} />
        <div className="grid grid-3" style={{ marginTop: 18, gap: 10 }}>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Reallocatable total</div><div className="figure-value">{croreShort(r.total_budget)}</div>
            <div className="figure-note num">{Math.abs(totalChange) < 1 ? 'Same as current budget' : `${signedCrore(totalChange)} vs current`}</div></div>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Room under the 3% limit
            <InfoTip>How much more the state could borrow this year before reaching 3% of GSDP. Negative means it is already above.</InfoTip></div>
            <div className="figure-value" style={{ color: r.deficit_room < 0 ? 'var(--bad)' : undefined }}>{r.deficit_room == null ? 'Not applied' : signedCrore(r.deficit_room)}</div>
            <div className="figure-note">before this allocation</div></div>
          <div className="figure" style={{ '--accent': 'var(--indigo)' }}><div className="figure-label">Borrowing after allocation</div><div className="figure-value">{croreShort(fx.recommended.gross_fiscal_deficit)}</div>
            <div className="figure-note">was {croreShort(fx.current.gross_fiscal_deficit)}</div></div>
        </div>
      </Card>

      <Card title="Current vs recommended" sub="Each sector stays inside its allowed range (shown in the table)">
        <AllocationCompareChart rows={r.allocation.map((a) => ({ sector: a.sector, a: a.current, b: a.recommended }))}
          aLabel="Current budget" bLabel="Recommended" />
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table className="data">
            <thead><tr><th>Sector</th><th className="r">Current</th><th className="r">Recommended</th><th className="r">Change</th>
              <th className="r">Allowed range <InfoTip>By default, the range of shares this state has actually used since 2011-12, widened to include the current budget.</InfoTip></th>
              <th className="r">Per person</th></tr></thead>
            <tbody>
              {r.allocation.map((a) => (
                <tr key={a.sector}>
                  <td>{SECTORS[a.sector].label}</td>
                  <td className="r">{crore(a.current)}</td>
                  <td className="r"><strong>{crore(a.recommended)}</strong></td>
                  <td className="r">{signedPct(a.change_pct)}</td>
                  <td className="r small">{croreShort(a.min)} – {croreShort(a.max)}</td>
                  <td className="r">{rupees(a.per_capita_recommended)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {r.binding_constraints?.length > 0 && (
          <div className="callout info" style={{ marginTop: 12 }}>
            <strong>What shaped this result</strong>
            <ul>{r.binding_constraints.map((b) => <li key={b}>{b}</li>)}</ul>
          </div>
        )}
      </Card>
      <div className="small muted">
        Next: test this allocation against revenue shocks on the <Link to={`/stress-testing?optimization=${run.id}`}>Stress Testing</Link> page.
      </div>
    </div>
  );
}

export default function Optimization() {
  const { stateCode, stateName } = useApp();
  const [goal, setGoal] = useState('MIN_ADJUSTMENT');
  const [year, setYear] = useState('');
  const [totalMode, setTotalMode] = useState('current');
  const [total, setTotal] = useState('');
  const [limit, setLimit] = useState(true);
  const [floor, setFloor] = useState('NONE');
  const [bounds, setBounds] = useState({});
  const [current, setCurrent] = useState(null);
  const impact = useAsync(() => api.impactModels(), []);
  const history = useAsync(() => api.optimizations(stateCode), [stateCode]);
  const runAction = useAction((body) => api.optimize(body));

  useEffect(() => { setCurrent(null); }, [stateCode]);
  const outcomeAvailable = (impact.data || []).some((m) => m.supported);
  const totalInvalid = totalMode === 'set' && !(Number(total) > 0);

  const run = async () => {
    const b = {};
    Object.entries(bounds).forEach(([k, v]) => {
      const min = v.min === '' || v.min == null ? null : Number(v.min) / 100;
      const max = v.max === '' || v.max == null ? null : Number(v.max) / 100;
      if (min != null || max != null) b[k] = { min_share: min, max_share: max };
    });
    const res = await runAction.run({
      state_code: stateCode, fiscal_year: year || undefined, objective: goal,
      total_budget: totalMode === 'set' ? Number(total) : undefined,
      enforce_deficit_limit: limit, social_floor: floor, bounds: Object.keys(b).length ? b : undefined,
    });
    if (res) { setCurrent(res); history.reload(); }
  };

  return (
    <>
      <PageHead eyebrow="Plan" title="Optimization"
        lede={`Find an allocation of ${stateName}'s reallocatable budget that respects the borrowing limit and the spending floors you choose.`} />

      <div className="grid grid-side">
        <Card title="Set the goal and limits">
          <div className="stack">
            <div className="field">
              <span className="field-label">Goal</span>
              <div className="choice-list" role="radiogroup" aria-label="Goal">
                {GOALS.map((g) => {
                  const disabled = g.value === 'OUTCOME' && !outcomeAvailable;
                  return (
                    <button key={g.value} type="button" role="radio" aria-checked={goal === g.value} disabled={disabled}
                      className={`choice ${goal === g.value ? 'selected' : ''}`} onClick={() => setGoal(g.value)}>
                      <span><span className="choice-title">{g.title}</span><br />
                        <span className="choice-desc">{disabled
                          ? 'Unavailable: the data shows no measurable link between these spending lines and the indicators (see Data & Methods).'
                          : g.desc}</span></span>
                    </button>
                  );
                })}
              </div>
            </div>
            <div className="field">
              <label htmlFor="op-year">Budget year</label>
              <select id="op-year" className="select" value={year} onChange={(e) => setYear(e.target.value)}>
                <option value="">Latest budget (2024-25)</option>
                {fiscalYearsDesc(2011, 2023).map((y) => <option key={y}>{y}</option>)}
              </select>
            </div>
            <div className="field">
              <span className="field-label">Total to allocate</span>
              <Segmented label="Total to allocate" value={totalMode} onChange={setTotalMode}
                options={[{ value: 'current', label: 'Current total' }, { value: 'set', label: 'Set an amount' }]} />
              {totalMode === 'set' && (
                <>
                  <input className="input" type="number" min="1" step="100" inputMode="numeric" aria-label="Total in ₹ crore"
                    placeholder="Amount in ₹ crore, e.g. 280000" value={total} onChange={(e) => setTotal(e.target.value)} />
                  <span className="help">Excludes interest and pensions.</span>
                </>
              )}
            </div>
            <label className="toggle"><input type="checkbox" checked={limit} onChange={(e) => setLimit(e.target.checked)} />
              Keep borrowing within 3% of GSDP</label>
            <div className="field">
              <span className="field-label">Protect social-sector spending <InfoTip>A minimum for education, health and welfare spending per person.</InfoTip></span>
              <Segmented label="Social spending floor" value={floor} onChange={setFloor} options={FLOORS} />
            </div>
            <details>
              <summary className="small" style={{ cursor: 'pointer', fontWeight: 600, color: 'var(--ink-2)' }}>Sector limits (optional)</summary>
              <div className="stack" style={{ gap: 8, marginTop: 10 }}>
                <span className="help small muted">Minimum and maximum share of the total, in %. Leave blank to use the state's historical range.</span>
                {Object.keys(SECTORS).map((k) => (
                  <div key={k} className="form-row" style={{ alignItems: 'center' }}>
                    <span style={{ width: 120 }} className="small">{SECTORS[k].label}</span>
                    <input className="input" style={{ width: 80 }} type="number" min="0" max="100" placeholder="min %" aria-label={`${SECTORS[k].label} minimum %`}
                      value={bounds[k]?.min ?? ''} onChange={(e) => setBounds({ ...bounds, [k]: { ...bounds[k], min: e.target.value } })} />
                    <input className="input" style={{ width: 80 }} type="number" min="0" max="100" placeholder="max %" aria-label={`${SECTORS[k].label} maximum %`}
                      value={bounds[k]?.max ?? ''} onChange={(e) => setBounds({ ...bounds, [k]: { ...bounds[k], max: e.target.value } })} />
                  </div>
                ))}
              </div>
            </details>
            <button className="btn" onClick={run} disabled={runAction.running || totalInvalid}>
              {runAction.running ? 'Calculating…' : 'Find recommended allocation'}
            </button>
            {totalInvalid && <div className="small muted">Enter a positive amount, or choose “Current total”.</div>}
            <ErrorState error={runAction.error} />
          </div>
        </Card>

        <div>
          {current ? <Result run={current} /> : (
            <Card><Empty title="No recommendation yet">Set a goal and limits, then select <strong>Find recommended allocation</strong>.</Empty></Card>
          )}
        </div>
      </div>

      <Card title="Previous runs" sub={`For ${stateName}`}>
        {history.error && <ErrorState error={history.error} onRetry={history.reload} />}
        {history.loading && !history.data && <Loading height={80} />}
        {history.data?.length === 0 && <Empty title="No runs yet">Results you calculate appear here.</Empty>}
        {history.data?.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Goal</th><th>Year</th><th>Result</th><th className="r">Total</th><th className="r">Deficit after (% GSDP)</th><th>Created</th></tr></thead>
              <tbody>
                {history.data.map((h) => (
                  <tr key={h.id} className={current?.id === h.id ? 'sel' : ''}>
                    <td><button className="btn-link" onClick={() => setCurrent(h)}>{GOAL_TITLE[h.objective]}</button></td>
                    <td>{h.fiscal_year || 'latest'}</td>
                    <td>{h.status === 'OPTIMAL' ? <span className="badge good">Found</span> : <span className="badge bad">Not possible</span>}</td>
                    <td className="r">{h.total_budget != null ? croreShort(h.total_budget) : '–'}</td>
                    <td className="r">{pct(h.result?.fiscal_effects?.recommended?.gfd_pct_gsdp, 2)}</td>
                    <td className="small muted">{dateTime(h.created_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <MethodNote>
        <p><strong>Smallest change</strong> minimises the sum of squared percentage changes from the current allocation,
          subject to the limits. <strong>Maximise infrastructure</strong> is a linear programme; any remaining money is split
          between the other sectors with the smallest proportional change.</p>
        <p>The 3% limit caps total spending at the current total plus the room left under 3% of GSDP, so a state already above
          the limit has its total reduced. Revenue is held at the budget level.</p>
        <p>The outcome-based goal is switched off because none of the estimated spending-to-indicator links is statistically
          reliable in this data. It becomes available automatically if a future dataset supports one.</p>
      </MethodNote>
    </>
  );
}
