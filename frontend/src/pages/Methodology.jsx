import { api } from '../api';
import { useApp } from '../AppContext';
import { useAsync } from '../hooks';
import { dateTime } from '../format';
import { Card, ErrorState, Loading, PageHead } from '../components/ui';

export default function Methodology() {
  const { dataset, datasetError } = useApp();
  const impact = useAsync(() => api.impactModels(), []);
  const q = dataset?.quality_report;

  return (
    <>
      <PageHead eyebrow="Review" title="Data & Methods"
        lede="Where the numbers come from, how each part of the system works, and what it cannot tell you." />

      <div className="grid grid-2">
        <Card title="Data sources">
          {datasetError && <ErrorState error={datasetError} />}
          {!dataset && !datasetError && <Loading height={120} />}
          {dataset && (
            <div className="stack" style={{ gap: 10 }}>
              <table className="data">
                <tbody>
                  <tr><td>Dataset</td><td><strong>{dataset.label}</strong></td></tr>
                  <tr><td>Records</td><td className="num">{dataset.record_count.toLocaleString('en-IN')}</td></tr>
                  <tr><td>Built</td><td>{dateTime(dataset.built_at)}</td></tr>
                  <tr><td>Loaded</td><td>{dateTime(dataset.imported_at)}</td></tr>
                  <tr><td>Checks</td><td>{q?.outlier_flag_count} unusual year-on-year changes flagged for review (kept, not removed)</td></tr>
                </tbody>
              </table>
              {dataset.source_summary.split('\n').map((s) => {
                const [text, url] = s.split(', https://');
                return <p key={s} className="small">{text}{url && <> · <a href={`https://${url}`} target="_blank" rel="noreferrer">source</a></>}</p>;
              })}
            </div>
          )}
        </Card>

        <Card title="What each section does">
          <div className="stack small" style={{ gap: 8 }}>
            <p><strong>Forecasts</strong> predict next year's growth of each budget item from its recent growth, inflation and the
              state economy, learned across all states. The method for each item was chosen on 2015-16 to 2020-21 and checked on
              2021-22 and 2022-23, which were not used for choosing.</p>
            <p><strong>Scenarios</strong> move money between sectors and recalculate borrowing exactly from the budget accounts.</p>
            <p><strong>Optimization</strong> searches for an allocation that satisfies the 3% borrowing limit, the sector ranges and any
              spending floor, with a stated goal.</p>
            <p><strong>Stress tests</strong> apply shocks sized from each state's own worst years and recalculate borrowing.</p>
            <p><strong>Prediction accuracy</strong> stores every forecast and compares it with the actual figure once published.</p>
          </div>
        </Card>
      </div>

      <Card title="Does more spending improve public services? What this data shows" sub="Estimated links between per-person spending and indicators, across states">
        {impact.error && <ErrorState error={impact.error} onRetry={impact.reload} />}
        {impact.loading && <Loading height={140} />}
        {impact.data && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Spending</th><th>Indicator</th><th className="r">Estimated effect of +1% spending</th><th className="r">90% range</th><th className="r">Observations</th><th>Reliable?</th></tr></thead>
              <tbody>
                {impact.data.map((m) => (
                  <tr key={m.key}>
                    <td>{{ health: 'Health', social_sector: 'Social sector', capital_outlay: 'Capital outlay' }[m.sector]} per person</td>
                    <td>{m.outcome_name}</td>
                    <td className="r num">{m.elasticity >= 0 ? '+' : ''}{m.elasticity.toFixed(3)}%</td>
                    <td className="r num">{m.ci_low.toFixed(3)} to {m.ci_high.toFixed(3)}</td>
                    <td className="r num">{m.n_obs} ({m.n_states} states)</td>
                    <td>{m.supported ? <span className="badge good">Yes</span> : <span className="badge warn">No: range includes zero</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="small muted" style={{ marginTop: 10 }}>
          Because no link is statistically reliable, the system does not predict changes in these indicators and does not offer
          an outcome-based optimization goal. This reflects the limits of short, slow-moving state-level data, not proof that
          spending has no effect.
        </p>
      </Card>

      <div style={{ height: 16 }} />
      <Card title="Limitations to keep in mind">
        <ul className="small" style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 6 }}>
          <li>About 30 states over 14–21 years is a small dataset; forecasts carry real uncertainty, shown as ranges.</li>
          <li>For four of five items the forecast method in use is “same as last year”, because it was the most accurate method in
            the selection years (2017-18 to 2020-21, which include the GST transition and COVID-19). This choice was fixed in advance and
            will be revisited when 2023-24 actuals are published.</li>
          <li>Education spending is not published separately by state in this dataset; scenarios use the social sector, which includes it.</li>
          <li>Health spending is published only up to 2019-20; later years are estimated from the last published share.</li>
          <li>No state-level governance index exists in the data, so the system does not compute one.</li>
        </ul>
      </Card>
    </>
  );
}
