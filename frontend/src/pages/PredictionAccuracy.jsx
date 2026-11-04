import { useMemo, useState } from 'react';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAction, useAsync } from '../hooks';
import { crore, dateTime, indicatorLabel, INDICATOR_LABELS, pct, signedPct } from '../format';
import { modelExplain, modelName } from '../modelText';
import { Card, Empty, ErrorState, InfoTip, Loading, MethodNote, PageHead, Segmented, Toast } from '../components/ui';
import { PredictedVsActualChart } from '../components/Charts';
import { useAdminKey } from '../components/AdminKey';

const TARGETS = Object.keys(INDICATOR_LABELS);
const BASIS = { ACTUAL: 'Actual result', GOVT_RE: 'State revised estimate', GOVT_BE: 'State budget estimate' };
const PAGE = 25;

const BAR_MAX = 30;   // % error mapped to full width, shared by every row so bars compare across items

function ErrorBars({ model, rule }) {
  if (model == null) return null;
  const w = (v) => `${Math.min(100, (100 * v) / BAR_MAX)}%`;
  return (
    <div className="err-bars" aria-hidden="true">
      <span style={{ width: w(model), background: 'var(--indigo)' }} />
      {rule != null && <span style={{ width: w(rule), background: '#b7c2d3' }} />}
    </div>
  );
}

function OverviewTable({ models, summary }) {
  const rows = TARGETS.map((t) => {
    const active = models.find((m) => m.indicator === t && m.active);
    if (!active) return { t };
    const root = models.find((m) => m.indicator === t && m.version === (active.parent_version || active.version)) || active;
    const holdout = active.metrics?.holdout_h1?.mape;
    const naive = active.metrics?.holdout_by_model?.naive_last?.h1?.mape;
    const s = summary.find((x) => x.indicator === t && x.model_version === root.version && x.comparison_basis === 'ACTUAL'
      && x.kind === 'BACKTEST' && x.horizon === 1);
    const cal = active.metrics?.calibration_evaluation;
    return { t, active, holdout, naive, s, cal };
  });
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th>Forecast item</th><th>Method in use</th>
            <th className="r">Typical error <InfoTip>Average size of the error in one-year-ahead forecasts for 2021-22 and 2022-23. These years were held back and never used to choose or tune the model.</InfoTip></th>
            <th style={{ width: 170 }}>Compared <InfoTip>Bars on one scale: model error (indigo) above the simple rule's error (grey). Shorter is better.</InfoTip></th>
            <th className="r">Simple rule error <InfoTip>Error of the “same as last year” rule over the same years, as a yardstick.</InfoTip></th>
            <th className="r">Bias <InfoTip>Average signed error over all past one-year forecasts: negative means forecasts were too low.</InfoTip></th>
            <th className="r">Predictions checked</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ t, active, holdout, naive, s, cal }) => (
            <tr key={t}>
              <td><strong>{indicatorLabel(t)}</strong></td>
              <td>{active ? <>{modelName(active.model_type)} <span className="muted small">v{active.version}</span>
                {active.calibrated && <span className="badge forecast" style={{ marginLeft: 6 }}>bias-corrected</span>}
                <InfoTip>{modelExplain(active.model_type)}{cal && ` Bias correction lowered the error on held-back years from ${pct(cal.mape_before)} to ${pct(cal.mape_after)}.`}</InfoTip></> : <span className="muted">No model</span>}</td>
              <td className="r"><strong>{pct(cal?.improved ? cal.mape_after : holdout)}</strong></td>
              <td><ErrorBars model={cal?.improved ? cal.mape_after : holdout} rule={naive} /></td>
              <td className="r muted">{pct(naive)}</td>
              <td className="r">{signedPct(s?.mean_signed_percentage_error)}</td>
              <td className="r">{s?.n ?? '–'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function PredictionAccuracy() {
  const { stateCode, stateName } = useApp();
  const [indicator, setIndicator] = useState('revenue_expenditure');
  const [basis, setBasis] = useState('ACTUAL');
  const [page, setPage] = useState(0);
  const [toast, setToast] = useState('');
  const models = useAsync(() => api.models(), []);
  const summary = useAsync(() => api.accuracySummary(), []);
  const stateDevs = useAsync(() => api.deviations({ state: stateCode, indicator }), [stateCode, indicator]);
  const records = useAsync(() => api.deviations({ indicator, basis }), [indicator, basis]);
  const evaluate = useAction(() => api.evaluate());
  const forecastNow = useAction((state, ind) => api.forecast({ state_code: state, indicator: ind, horizon: 3 }));
  const admin = useAdminKey();
  const modelAction = useAction((kind, ind) => admin.withAdminKey((key) => (kind === 'retrain' ? api.retrain(ind, key) : api.calibrate(ind, key))));
  const sync = useAction(() => admin.withAdminKey((key) => api.syncModels(key)));

  const chartRows = useMemo(() => {
    const devs = (stateDevs.data || []).filter((d) => d.comparison_basis === 'ACTUAL' && d.horizon === 1 && d.kind === 'BACKTEST');
    const byYear = new Map();
    devs.forEach((d) => byYear.set(d.fiscal_year, { fy: d.fiscal_year, actual: d.actual_value, predicted: d.predicted_value, err: d.percentage_error }));
    return [...byYear.values()].sort((a, b) => a.fy.localeCompare(b.fy));
  }, [stateDevs.data]);
  const govtRows = (stateDevs.data || []).filter((d) => d.comparison_basis !== 'ACTUAL');
  const recs = records.data || [];
  const pageRows = recs.slice(page * PAGE, (page + 1) * PAGE);

  const doEvaluate = async () => {
    const r = await evaluate.run();
    if (r) { setToast(r.deviations_added ? `${r.deviations_added} new predictions scored` : 'No new results to score: all predictions with published results are already checked'); summary.reload(); records.reload(); stateDevs.reload(); }
  };
  const doModel = async (kind, ind) => {
    const msg = kind === 'retrain'
      ? `Retrain the ${indicatorLabel(ind)} model on the current data? This creates a new model version; it replaces the current one only if it is at least as accurate.`
      : `Correct the ${indicatorLabel(ind)} model for its past bias? This creates a new version from recorded errors; it is used only if it reduces error on the two most recent years.`;
    if (!window.confirm(msg)) return;
    const r = await modelAction.run(kind, ind);
    if (r) { setToast(`Created ${indicatorLabel(ind)} v${r.version}: ${r.active ? 'now in use' : 'kept, not used (no improvement)'}`); models.reload(); summary.reload(); }
  };

  const doSync = async () => {
    const r = await sync.run();
    if (r) { setToast(`Model list refreshed: ${r.length} versions`); models.reload(); summary.reload(); }
  };

  return (
    <>
      <PageHead eyebrow="Review" title="Prediction Accuracy"
        lede="Every forecast the system makes is recorded and later compared with what actually happened. This page shows how accurate the forecasts have been and which model version is in use."
        actions={<button className="btn btn-secondary" onClick={doEvaluate} disabled={evaluate.running}>{evaluate.running ? 'Checking…' : 'Check predictions against new results'}</button>} />
      <ErrorState error={evaluate.error} />

      <Card title="Accuracy by forecast item" sub="One-year-ahead forecasts, all states">
        {(models.error || summary.error) && <ErrorState error={models.error || summary.error} onRetry={() => { models.reload(); summary.reload(); }} />}
        {(models.loading || summary.loading) && !(models.data && summary.data) && <Loading height={180} />}
        {models.data && summary.data && <OverviewTable models={models.data} summary={summary.data} />}
      </Card>

      <div className="grid grid-main" style={{ marginTop: 16 }}>
        <Card title={`${stateName}: predicted vs actual`} sub="Each dot is what the model predicted one year before the actual figure was published"
          actions={(
            <select className="select" aria-label="Forecast item" value={indicator} onChange={(e) => { setIndicator(e.target.value); setPage(0); }}>
              {TARGETS.map((t) => <option key={t} value={t}>{indicatorLabel(t)}</option>)}
            </select>
          )}>
          {stateDevs.error && <ErrorState error={stateDevs.error} onRetry={stateDevs.reload} />}
          {stateDevs.loading && !stateDevs.data && <Loading height={260} />}
          {stateDevs.data && chartRows.length === 0 && <Empty title="No checked predictions">There are no past predictions with published results for this state and item.</Empty>}
          {chartRows.length > 0 && <PredictedVsActualChart rows={chartRows} />}
        </Card>

        <Card title="Compared with the state's own estimates" sub="Latest forecasts against the figures in the state budget papers">
          {govtRows.length === 0 && !stateDevs.loading && (
            <Empty title="No forecast for this item yet">
              <p style={{ marginBottom: 10 }}>Run the current model for {stateName}; its forecast is compared with the state's estimates straight away.</p>
              <button className="btn btn-small" disabled={forecastNow.running}
                onClick={async () => { if (await forecastNow.run(stateCode, indicator)) stateDevs.reload(); }}>
                {forecastNow.running ? 'Forecasting…' : `Forecast ${indicatorLabel(indicator).toLowerCase()}`}
              </button>
              <ErrorState error={forecastNow.error} />
            </Empty>
          )}
          {govtRows.length > 0 && (
            <table className="data">
              <thead><tr><th>Year</th><th>Compared with</th><th className="r">Model</th><th className="r">State</th><th className="r">Difference</th></tr></thead>
              <tbody>
                {govtRows.map((d) => (
                  <tr key={`${d.prediction_id}-${d.comparison_basis}`}>
                    <td>{d.fiscal_year}</td><td>{BASIS[d.comparison_basis]}</td>
                    <td className="r">{crore(d.predicted_value)}</td><td className="r">{crore(d.actual_value)}</td>
                    <td className="r">{signedPct(d.percentage_error)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="small muted" style={{ marginTop: 10 }}>
            State estimates are projections, not results; a difference is not an error by either side. These forecasts are
            scored against actual figures once the RBI publishes them.
          </p>
        </Card>
      </div>

      <Card title="Prediction records" sub={`${indicatorLabel(indicator)}, all states · ${recs.length} records`}
        actions={<Segmented label="Compared with" value={basis} onChange={(v) => { setBasis(v); setPage(0); }}
          options={[{ value: 'ACTUAL', label: 'Actual results' }, { value: 'GOVT_RE', label: 'Revised estimates' }, { value: 'GOVT_BE', label: 'Budget estimates' }]} />}>
        {records.error && <ErrorState error={records.error} onRetry={records.reload} />}
        {records.loading && !records.data && <Loading height={200} />}
        {records.data && recs.length === 0 && <Empty title="No records">No predictions have been compared on this basis yet.</Empty>}
        {recs.length > 0 && (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>Prediction ID</th><th>State</th><th>Forecast for</th><th>Made with data to</th><th className="r">Years ahead</th>
                  <th className="r">Predicted</th><th className="r">{BASIS[basis]}</th><th className="r">Error</th><th className="r">% error</th><th>Model</th><th>Type</th></tr></thead>
                <tbody>
                  {pageRows.map((d) => (
                    <tr key={`${d.prediction_id}-${d.comparison_basis}`}>
                      <td className="num">#{d.prediction_id}</td><td>{d.state_code}</td><td>{d.fiscal_year}</td><td>{d.origin_fiscal_year}</td>
                      <td className="r">{d.horizon}</td><td className="r">{crore(d.predicted_value)}</td><td className="r">{crore(d.actual_value)}</td>
                      <td className="r">{crore(d.absolute_error)}</td><td className="r">{signedPct(d.percentage_error)}</td>
                      <td>v{d.model_version}</td><td className="small">{d.kind === 'BACKTEST' ? 'Historical test' : 'Live forecast'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="form-row" style={{ justifyContent: 'space-between', marginTop: 10, alignItems: 'center' }}>
              <span className="small muted">Showing {page * PAGE + 1}–{Math.min((page + 1) * PAGE, recs.length)} of {recs.length}</span>
              <div className="form-row">
                <button className="btn btn-secondary btn-small" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
                <button className="btn btn-secondary btn-small" disabled={(page + 1) * PAGE >= recs.length} onClick={() => setPage(page + 1)}>Next</button>
              </div>
            </div>
          </>
        )}
      </Card>

      <Card title="Model versions" sub="Each retraining or bias correction creates a new version; the one in use is marked"
        actions={<button className="btn btn-secondary btn-small" onClick={doSync} disabled={sync.running}>{sync.running ? 'Refreshing…' : 'Refresh from ML service'}</button>}>
        <ErrorState error={modelAction.error || sync.error} />
        {models.data && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Forecast item</th><th>Version</th><th>Method</th><th>Status</th><th>Trained on</th><th>Created</th><th>Based on</th><th /></tr></thead>
              <tbody>
                {models.data.map((m) => (
                  <tr key={`${m.indicator}-${m.version}`}>
                    <td>{indicatorLabel(m.indicator)}</td>
                    <td>v{m.version}</td>
                    <td>{modelName(m.model_type)}{m.calibrated && <span className="badge forecast" style={{ marginLeft: 6 }}>bias-corrected</span>}</td>
                    <td>{m.active ? <span className="badge good">In use</span> : <span className="badge">Stored</span>}</td>
                    <td className="small">{m.train_year_from} to {m.train_year_to}</td>
                    <td className="small muted">{dateTime(m.trained_at)}</td>
                    <td className="small">{m.parent_version ? `v${m.parent_version}` : 'Original'}</td>
                    <td>
                      {m.active && (
                        <div className="form-row" style={{ gap: 8 }}>
                          <button className="btn-link small" disabled={modelAction.running} onClick={() => doModel('calibrate', m.indicator)}>Correct bias</button>
                          <button className="btn-link small" disabled={modelAction.running} onClick={() => doModel('retrain', m.indicator)}>Retrain</button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <MethodNote title="How the feedback loop works">
        <ol style={{ margin: 0, paddingLeft: 18 }}>
          <li>Every forecast is stored with a Prediction ID and the model version that made it.</li>
          <li>When actual figures are published (a new RBI Handbook edition is loaded), each stored forecast is compared with the actual result and its error is recorded.</li>
          <li>Recorded errors show whether a model is systematically too high or too low for each state.</li>
          <li>A bias-corrected or retrained version is created from those errors and used only if it is more accurate on the most recent years it has not seen.</li>
        </ol>
        <p>Historical tests: for each year from 2015-16 to 2022-23, models were trained only on data available at the time and
          asked to predict the following years. These are the “Historical test” records above. Forecasts for 2023-24 onward can
          only be compared with the states' own estimates until actual figures are published.</p>
      </MethodNote>
      <Toast message={toast} onDone={() => setToast('')} />
    </>
  );
}
