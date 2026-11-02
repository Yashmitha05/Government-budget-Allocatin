import { pct } from '../format';
import { InfoTip } from './ui';

const LIMIT = 3;
/** True when a GFD/GSDP value breaches the 3% limit; exactly 3.00% (as the optimiser returns) is within it. */
export const aboveLimit = (v) => typeof v === 'number' && v > LIMIT + 1e-6;

/**
 * The fiscal-deficit ruler: gross fiscal deficit as % of GSDP on a fixed scale with the 3% FRBM limit marked.
 * Used on every page that changes or tests a budget, so the limit is always read the same way.
 * marks: [{label, value, color}]
 */
export default function DeficitRuler({ marks, title = 'Fiscal deficit vs the 3% limit' }) {
  const values = marks.map((m) => m.value).filter((v) => typeof v === 'number');
  const max = Math.max(6, Math.ceil(Math.max(...values, 0) + 0.5));
  const min = Math.min(0, Math.floor(Math.min(...values, 0)));
  const pos = (v) => `${((v - min) / (max - min)) * 100}%`;
  const ticks = [];
  for (let t = min; t <= max; t += 1) ticks.push(t);

  return (
    <div className="ruler">
      <div className="stat-label">
        {title}
        <InfoTip>
          The gross fiscal deficit is what the state borrows in a year. The Fiscal Responsibility and Budget
          Management (FRBM) rule limits it to 3% of the state's GDP (GSDP). Marks right of the red line breach the limit.
        </InfoTip>
      </div>
      <div className="ruler-track" style={{ '--limit-pos': pos(LIMIT) }}>
        <div className="ruler-scale" />
        {ticks.map((t) => <span key={t} className="ruler-tick" style={{ left: pos(t) }}>{t}%</span>)}
        <div className="ruler-limit" style={{ left: pos(LIMIT) }}><span>3% limit</span></div>
        {marks.filter((m) => typeof m.value === 'number').map((m) => (
          <div key={m.label} style={{ position: 'absolute', left: pos(m.value), top: 0 }}>
            <div className="ruler-mark" style={{ left: 0, background: m.color }} title={`${m.label}: ${pct(m.value, 2)}`} />
          </div>
        ))}
      </div>
      <div className="ruler-legend">
        {marks.map((m) => (
          <span key={m.label} className="num">
            <i style={{ background: m.color }} />
            {m.label}: <strong style={{ color: aboveLimit(m.value) ? 'var(--bad)' : 'var(--ink)' }}>
              {pct(m.value, 2)}
            </strong>
            {typeof m.value === 'number' && (aboveLimit(m.value)
              ? <span className="badge bad" style={{ marginLeft: 6 }}>Above limit</span>
              : <span className="badge good" style={{ marginLeft: 6 }}>Within limit</span>)}
          </span>
        ))}
      </div>
    </div>
  );
}
