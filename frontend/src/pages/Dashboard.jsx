import { Link } from 'react-router-dom';
import { api } from '../api';
import { useApp } from '../AppContext';
import { useAsync } from '../hooks';
import { crore, croreShort, pct, rupees, SECTORS, STATUS } from '../format';
import { modelExplain, modelName } from '../modelText';
import { Card, ErrorState, InfoTip, Loading, MethodNote, PageHead, SectionTitle, Stat, StatusBadge } from '../components/ui';
import DeficitRuler, { aboveLimit } from '../components/DeficitRuler';
import { ForecastChart, ShareBar } from '../components/Charts';

function ForecastCard({ f }) {
  const next = f.predictions[0];
  const mape = f.backtest_metrics?.mape;
  const govt = f.government_estimates.find((g) => g.fiscal_year === next?.fiscal_year);
  return (
    <Card title={f.indicator === 'own_tax_revenue' ? 'Own tax revenue' : 'Revenue expenditure'}
      sub={f.indicator === 'own_tax_revenue'
        ? 'Taxes the state collects itself (GST share, stamp duty, excise, vehicle tax…)'
        : 'Day-to-day spending: salaries, pensions, interest, schemes, subsidies'}>
      <div className="figure-pair">
        <div className="figure" style={{ '--accent': 'var(--series-forecast)' }}>
          <div className="figure-label">Model forecast, {next?.fiscal_year}</div>
          <div className="figure-value">{croreShort(next?.value)}</div>
          <div className="figure-note num">range {croreShort(next?.lower)} – {croreShort(next?.upper)}</div>
        </div>
        {govt && (
          <div className="figure" style={{ '--accent': 'var(--series-govt)' }}>
            <div className="figure-label">State's {STATUS[govt.data_status]?.label.toLowerCase()}</div>
            <div className="figure-value">{croreShort(govt.value)}</div>
            <div className="figure-note">from the {next.fiscal_year} budget papers</div>
          </div>
        )}
      </div>
      <ForecastChart forecast={f} height={230} />
      <div className="small muted" style={{ marginTop: 8 }}>
        Method: <strong>{modelName(f.model.model_type)}</strong>{f.model.calibrated && ' (adjusted for its past bias)'}
        <InfoTip>{modelExplain(f.model.model_type)}</InfoTip>
        {mape != null && <> · typical one-year error in testing: <strong>{pct(mape)}</strong></>}
      </div>
    </Card>
  );
}

export default function Dashboard() {
  const { stateCode, stateName } = useApp();
  const { data: d, error, loading, reload } = useAsync(() => api.dashboard(stateCode), [stateCode]);

  if (error) return (<><PageHead eyebrow="Dashboard" title={`${stateName}: budget at a glance`} /><ErrorState error={error} onRetry={reload} /></>);
  if (loading && !d) return (
    <>
      <PageHead eyebrow="Dashboard" title={`${stateName}: budget at a glance`} lede="Loading the latest budget and forecasts…" />
      <div className="grid grid-4">{[1, 2, 3, 4].map((i) => <Loading key={i} height={110} />)}</div>
      <div style={{ height: 16 }} /><Loading height={320} />
    </>
  );

  const committedShare = d.revenue_receipts ? (100 * d.committed_expenditure) / d.revenue_receipts : null;
  const statusLabel = STATUS[d.data_status]?.label.toLowerCase();

  return (
    <>
      <PageHead
        eyebrow={`Dashboard · ${d.fiscal_year}`}
        title={`${d.state_name}: budget at a glance`}
        lede="The latest budget, how much the state is borrowing compared with the legal limit, and where spending and tax revenue are heading."
        context={<><StatusBadge status={d.data_status} /><span>Budget year {d.fiscal_year} ({statusLabel}, as presented in the state budget)</span></>}
      />

      <div className="grid grid-4">
        <Stat accent="var(--series-actual)" label="Total spending" value={croreShort(d.total_expenditure)}
          tip="Revenue expenditure plus capital outlay: everything that counts towards the fiscal deficit."
          note={`${croreShort(d.discretionary_expenditure)} of this can be reallocated`} />
        <Stat accent="var(--series-govt)" label="Own tax revenue" value={croreShort(d.own_tax_revenue)}
          tip="Taxes collected by the state itself. Excludes the state's share of central taxes and central grants."
          note={`Total revenue receipts ${croreShort(d.revenue_receipts)}`} />
        <Stat accent={aboveLimit(d.gfd_pct_gsdp) ? 'var(--bad)' : 'var(--good)'} label="Fiscal deficit" value={pct(d.gfd_pct_gsdp, 2)} unit="of GSDP"
          tone={aboveLimit(d.gfd_pct_gsdp) ? 'bad' : undefined}
          tip="Borrowing needed to fund the budget (gross fiscal deficit) as a share of the state's GDP."
          note={`${croreShort(d.gross_fiscal_deficit)} borrowed`} />
        <Stat accent="var(--series-forecast)" label="Interest and pensions" value={pct(committedShare)} unit="of revenue"
          tip="Committed payments that cannot be reallocated in the short run, as a share of revenue receipts. Higher values leave less room for new spending."
          note={`${croreShort(d.committed_expenditure)} committed`} />
      </div>

      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <Card title="Borrowing against the legal limit"
          sub={`${pct(d.deficit_limit_used_pct, 0)} of the 3% FRBM limit is used in ${d.fiscal_year}`}>
          <DeficitRuler marks={[{ label: `${d.fiscal_year} budget`, value: d.gfd_pct_gsdp, color: 'var(--brand)' }]} />
          {aboveLimit(d.gfd_pct_gsdp) && (
            <div className="callout bad" style={{ marginTop: 14 }}>
              The budget plans to borrow more than 3% of GSDP. Use <Link to="/optimization">Optimization</Link> to find the
              smallest change that brings it within the limit.
            </div>
          )}
        </Card>
        <Card title="Where the reallocatable budget goes" sub={`${croreShort(d.discretionary_expenditure)} after interest and pensions`}>
          <ShareBar items={d.sectors.map((s) => ({ sector: s.sector, share: s.share_pct }))} />
          <table className="data" style={{ marginTop: 12 }}>
            <thead><tr><th>Sector</th><th className="r">Amount</th><th className="r">Share</th><th className="r">Per person</th></tr></thead>
            <tbody>
              {d.sectors.map((s) => (
                <tr key={s.sector}>
                  <td style={{ whiteSpace: 'nowrap' }}><span className="legend-dot" style={{ background: SECTORS[s.sector].color, marginRight: 8 }} />{SECTORS[s.sector].label}</td>
                  <td className="r">{crore(s.amount)}</td>
                  <td className="r">{pct(s.share_pct)}</td>
                  <td className="r">{rupees(s.per_capita)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="small muted" style={{ marginTop: 10 }}>
            Try a different split in the <Link to="/scenarios">Scenario Simulator</Link>.
          </div>
        </Card>
      </div>

      <SectionTitle eyebrow="Outlook" title="Where spending and revenue are heading">
        Forecasts start after {d.forecasts[0]?.origin_fiscal_year}, the last year with actual figures, so they can be
        compared with the state's own revised and budget estimates for later years.
      </SectionTitle>
      <div className="grid grid-2">
        {d.forecasts.map((f) => <ForecastCard key={f.indicator} f={f} />)}
      </div>

      <MethodNote title="How to read this page">
        <p><strong>Actual</strong> figures come from audited state accounts. <strong>Revised (RE)</strong> and
          <strong> budget estimates (BE)</strong> are the state government's own projections in its budget papers;
          they are not outcomes.</p>
        <p><strong>Reallocatable budget</strong> = social sector + capital outlay + other revenue spending, i.e. total
          spending minus interest and pensions. Shares are of this amount.</p>
        <p><strong>Forecast range</strong>: in out-of-time tests, 8 in 10 actual outcomes fell inside a range of this width.</p>
        {d.health_estimated && <p>Health spending for this year is estimated from the last published health share (2019-20).</p>}
      </MethodNote>
    </>
  );
}
