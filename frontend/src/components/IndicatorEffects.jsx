import { number } from '../format';
import { InfoTip } from './ui';

const fmt = (v) => (typeof v === 'number' && Math.abs(v) >= 100 ? number(v) : number(v, 1));
const SECTOR_TEXT ={ health: 'health spending', social_sector: 'social sector spending', capital_outlay: 'capital outlay' };

/** Public-service indicators linked to a change in spending. Shows a prediction only when the data supports one. */
export default function IndicatorEffects({ effects }) {
  if (!effects?.length) return null;
  const anySupported = effects.some((e) => e.supported);
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="stat-label">
        Effect on public services
        <InfoTip>
          Estimated from how these indicators moved with spending across states between 2011 and 2023, after removing
          differences between states and common yearly changes. A change is shown only if the estimate is statistically
          reliable (90% confidence).
        </InfoTip>
      </div>
      {!anySupported && (
        <div className="callout info">
          <strong>No reliable effect can be predicted.</strong> In the historical data, year-to-year changes in these
          spending lines did not move the indicators below by a measurable amount. The figures are shown for transparency;
          this does not mean spending has no effect, only that this data cannot measure it.
        </div>
      )}
      <table className="data">
        <thead><tr><th>Indicator</th><th>Linked to</th><th className="r">Latest value</th><th>Predicted effect</th></tr></thead>
        <tbody>
          {effects.map((e) => (
            <tr key={`${e.indicator}-${e.driver_sector}`}>
              <td>{e.indicator_name}</td>
              <td>{SECTOR_TEXT[e.driver_sector] || e.driver_sector}</td>
              <td className="r num">{fmt(e.current_value)} <span className="muted small">({e.current_as_of})</span></td>
              <td>
                {e.supported
                  ? <span className="num">{fmt(e.predicted_value)} (range {fmt(e.lower)}–{fmt(e.upper)})</span>
                  : <span className="muted">Not measurable
                    <span className="small"> · estimate {e.elasticity?.toFixed(2)}, range {e.elasticity_ci?.[0]?.toFixed(2)} to {e.elasticity_ci?.[1]?.toFixed(2)}</span>
                  </span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
