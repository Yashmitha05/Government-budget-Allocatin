import {
  Area, Bar, BarChart, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Scatter, Tooltip, XAxis, YAxis,
} from 'recharts';
import { crore, croreAxis, SECTORS, STATUS } from '../format';

const AXIS = { stroke: 'var(--line-strong)', tick: { fill: 'var(--ink-3)', fontSize: 11.5 }, tickLine: false };

/** Axis from 0 with round steps (1, 2, 2.5 or 5 × 10^k) and 3–5 intervals. Undefined if values go negative. */
function niceAxis(values) {
  const nums = values.filter((v) => typeof v === 'number' && Number.isFinite(v));
  if (!nums.length || Math.min(...nums) < 0) return {};
  const max = Math.max(...nums) || 1;
  const raw = max / 4;
  const p = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((s) => s * p).find((s) => s >= raw);
  const top = Math.ceil(max / step) * step;
  const ticks = [];
  for (let t = 0; t <= top + step / 2; t += step) ticks.push(t);
  return { domain: [0, top], ticks };
}

/** ₹-crore tick labels in one unit per axis: all in lakh crore (L) when the top tick reaches 1 lakh, else thousands (k). */
const croreTicks = (axis) => {
  const top = axis.domain?.[1];
  if (top == null) return croreAxis;
  return (v) => (v === 0 ? '0' : top >= 100000 ? `${+(v / 100000).toFixed(2)}L` : croreAxis(v));
};
const GRID = <CartesianGrid stroke="var(--line)" vertical={false} />;

function Diamond({ cx, cy }) {
  if (cx == null || cy == null) return null;
  return <rect x={cx - 5} y={cy - 5} width={10} height={10} transform={`rotate(45 ${cx} ${cy})`}
    fill="#fff" stroke="var(--series-govt)" strokeWidth={2} />;
}

export function SeriesLegend({ hasGovt = true, hasForecast = true }) {
  return (
    <div className="chart-legend" aria-hidden="true">
      <span className="legend-item"><span className="legend-swatch" style={{ borderTopColor: 'var(--series-actual)' }} />Actual</span>
      {hasGovt && <span className="legend-item"><span className="legend-diamond" />State estimate (RE/BE)</span>}
      {hasForecast && (
        <span className="legend-item">
          <span className="legend-swatch" style={{ borderTopColor: 'var(--series-forecast)', borderTopStyle: 'dashed' }} />
          Model forecast and likely range
        </span>
      )}
    </div>
  );
}

function SeriesTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="tt-title">{label}</div>
      {d.actual != null && <div className="tt-row"><span>Actual</span><strong className="num">{crore(d.actual)}</strong></div>}
      {d.govt != null && <div className="tt-row"><span>{STATUS[d.govtStatus]?.label || 'Estimate'}</span><strong className="num">{crore(d.govt)}</strong></div>}
      {d.forecast != null && d.isForecast && <div className="tt-row"><span>Model forecast</span><strong className="num">{crore(d.forecast)}</strong></div>}
      {d.band && <div className="tt-row muted"><span>Likely range</span><span className="num">{crore(d.band[0])} – {crore(d.band[1])}</span></div>}
    </div>
  );
}

/**
 * History (actual), the state's own RE/BE, and the model forecast with its 80% range, on one ₹-crore axis.
 * forecast: backend ForecastResponse.
 */
export function ForecastChart({ forecast, height = 280, fromYear = '2012-13' }) {
  const rows = new Map();
  const row = (fy) => { if (!rows.has(fy)) rows.set(fy, { fy }); return rows.get(fy); };
  forecast.history.filter((h) => h.fiscal_year >= fromYear).forEach((h) => { row(h.fiscal_year).actual = h.value; });
  forecast.government_estimates.forEach((g) => { const r = row(g.fiscal_year); r.govt = g.value; r.govtStatus = g.data_status; });
  const last = forecast.history[forecast.history.length - 1];
  if (last) { const r = row(last.fiscal_year); r.forecast = last.value; }   // joins the forecast line to the last actual
  forecast.predictions.forEach((p) => {
    const r = row(p.fiscal_year);
    r.forecast = p.value; r.isForecast = true;
    if (p.lower != null && p.upper != null) r.band = [p.lower, p.upper];
  });
  const data = [...rows.values()].sort((a, b) => a.fy.localeCompare(b.fy));
  const axisY = niceAxis(data.flatMap((d) => [d.actual, d.govt, d.forecast, ...(d.band || [])]));

  return (
    <div>
      <SeriesLegend hasGovt={forecast.government_estimates.length > 0} />
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
          {GRID}
          <XAxis dataKey="fy" {...AXIS} interval="preserveStartEnd" minTickGap={18} />
          <YAxis {...AXIS} axisLine={false} tickFormatter={croreTicks(axisY)} width={48} {...axisY} />
          <Tooltip content={<SeriesTooltip />} cursor={{ stroke: 'var(--line-strong)' }} />
          <Area dataKey="band" stroke="none" fill="var(--forecast-band)" isAnimationActive={false} connectNulls />
          <Line dataKey="actual" stroke="var(--series-actual)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls />
          <Line dataKey="forecast" stroke="var(--series-forecast)" strokeWidth={2} strokeDasharray="6 4"
            dot={{ r: 3.5, fill: 'var(--series-forecast)', stroke: '#fff', strokeWidth: 1.5 }} isAnimationActive={false} connectNulls />
          <Scatter dataKey="govt" shape={<Diamond />} isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/** Proportion of discretionary spending by sector. sectors: [{sector, amount|value, share_pct}] */
export function ShareBar({ items }) {
  return (
    <div>
      <div className="share-bar" role="img" aria-label={items.map((i) => `${SECTORS[i.sector]?.label}: ${i.share.toFixed(1)}%`).join(', ')}>
        {items.map((i) => (
          <div key={i.sector} style={{ width: `${i.share}%`, background: SECTORS[i.sector]?.color }} title={`${SECTORS[i.sector]?.label}: ${i.share.toFixed(1)}%`} />
        ))}
      </div>
    </div>
  );
}

function CompareTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="chart-tooltip">
      <div className="tt-title">{label}</div>
      {payload.map((p) => (
        <div className="tt-row" key={p.dataKey}><span>{p.name}</span><strong className="num">{crore(p.value)}</strong></div>
      ))}
    </div>
  );
}

/** Grouped horizontal bars: two allocations per sector (e.g. current vs recommended). */
export function AllocationCompareChart({ rows, aLabel, bLabel, height = 200 }) {
  const data = rows.map((r) => ({ name: SECTORS[r.sector]?.label || r.sector, a: r.a, b: r.b }));
  const axisX = niceAxis(data.flatMap((d) => [d.a, d.b]));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 0, left: 8 }} barGap={2} barCategoryGap="28%">
        <CartesianGrid stroke="var(--line)" horizontal={false} />
        <XAxis type="number" {...AXIS} tickFormatter={croreTicks(axisX)} {...axisX} />
        <YAxis type="category" dataKey="name" {...AXIS} axisLine={false} width={108} />
        <Tooltip content={<CompareTooltip />} cursor={{ fill: 'rgba(20,33,61,0.04)' }} />
        <Legend iconType="square" iconSize={10} wrapperStyle={{ fontSize: 12.5, color: 'var(--ink-2)' }} />
        <Bar dataKey="a" name={aLabel} fill="#9fb3c8" radius={[0, 4, 4, 0]} isAnimationActive={false} />
        <Bar dataKey="b" name={bLabel} fill="var(--series-actual)" radius={[0, 4, 4, 0]} isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}

function AccuracyTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="tt-title">{label}</div>
      {d.actual != null && <div className="tt-row"><span>Actual</span><strong className="num">{crore(d.actual)}</strong></div>}
      {d.predicted != null && <div className="tt-row"><span>Predicted a year earlier</span><strong className="num">{crore(d.predicted)}</strong></div>}
      {d.err != null && <div className="tt-row muted"><span>Error</span><span className="num">{d.err > 0 ? '+' : ''}{d.err.toFixed(1)}%</span></div>}
    </div>
  );
}

/** Predicted (one year ahead) vs actual for one state and indicator. */
export function PredictedVsActualChart({ rows, height = 260 }) {
  const axisY = niceAxis(rows.flatMap((r) => [r.actual, r.predicted]));
  return (
    <div>
      <div className="chart-legend" aria-hidden="true">
        <span className="legend-item"><span className="legend-swatch" style={{ borderTopColor: 'var(--series-actual)' }} />Actual</span>
        <span className="legend-item"><span className="legend-dot" style={{ background: 'var(--series-forecast)' }} />Predicted one year earlier</span>
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
          {GRID}
          <XAxis dataKey="fy" {...AXIS} />
          <YAxis {...AXIS} axisLine={false} tickFormatter={croreTicks(axisY)} width={48} {...axisY} />
          <Tooltip content={<AccuracyTooltip />} cursor={{ stroke: 'var(--line-strong)' }} />
          <Line dataKey="actual" stroke="var(--series-actual)" strokeWidth={2} dot={{ r: 3, fill: 'var(--series-actual)' }} isAnimationActive={false} />
          <Line dataKey="predicted" stroke="none" dot={{ r: 5, fill: 'var(--series-forecast)', stroke: '#fff', strokeWidth: 2 }}
            activeDot={{ r: 6 }} isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/** One indicator through time, actual years solid and RE/BE years as estimate markers. */
export function HistoryChart({ rows, unitFormatter = croreAxis, height = 240 }) {
  const data = rows.map((r) => ({
    fy: r.fiscal_year,
    actual: STATUS[r.data_status]?.kind === 'estimate' ? null : r.value,
    govt: STATUS[r.data_status]?.kind === 'estimate' ? r.value : null,
    govtStatus: r.data_status,
  }));
  const lastActual = [...data].reverse().find((d) => d.actual != null);
  data.forEach((d) => { if (d === lastActual) d.govtJoin = d.actual; else if (d.govt != null) d.govtJoin = d.govt; });
  const axisY = niceAxis(rows.map((r) => r.value));   // empty (auto axis) when any value is negative
  return (
    <div>
      <SeriesLegend hasGovt={data.some((d) => d.govt != null)} hasForecast={false} />
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
          {GRID}
          <XAxis dataKey="fy" {...AXIS} interval="preserveStartEnd" minTickGap={18} />
          <YAxis {...AXIS} axisLine={false} tickFormatter={unitFormatter === croreAxis ? croreTicks(axisY) : unitFormatter} width={52} {...axisY} />
          <Tooltip content={<SeriesTooltip />} cursor={{ stroke: 'var(--line-strong)' }} />
          <Line dataKey="actual" stroke="var(--series-actual)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
          <Line dataKey="govtJoin" stroke="var(--series-govt)" strokeWidth={1.5} strokeDasharray="3 3" dot={false} isAnimationActive={false} connectNulls />
          <Scatter dataKey="govt" shape={<Diamond />} isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
