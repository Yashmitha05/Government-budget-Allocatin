import { NavLink, Outlet } from 'react-router-dom';
import { useState } from 'react';
import { useApp } from '../AppContext';
import { api } from '../api';
import { ErrorState } from './ui';
import { BrandMark } from './Rosette';

// Grouped by what the user is doing: reading the budget, planning changes, checking the models.
const NAV = [
  { group: 'Understand', items: [
    { to: '/', label: 'Dashboard', hint: 'Budget at a glance', end: true },
    { to: '/budget', label: 'Budget Analysis', hint: 'Records by year and indicator' },
  ] },
  { group: 'Plan', items: [
    { to: '/scenarios', label: 'Scenario Simulator', hint: 'Try different allocations' },
    { to: '/optimization', label: 'Optimization', hint: 'Recommended allocation' },
    { to: '/stress-testing', label: 'Stress Testing', hint: 'Test against shocks' },
  ] },
  { group: 'Review', items: [
    { to: '/accuracy', label: 'Prediction Accuracy', hint: 'Forecasts vs actual results' },
    { to: '/methodology', label: 'Data & Methods', hint: 'Sources, models, limitations' },
  ] },
];

export default function Layout() {
  const app = useApp();
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState(null);
  const noDataset = app.datasetError?.code === 'NO_DATASET';

  const runImport = async () => {
    setImporting(true);
    setImportError(null);
    try { await api.importDataset(); app.reloadAll(); } catch (e) { setImportError(e); }
    setImporting(false);
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <BrandMark />
          <div>
            <div className="brand-title">State Budget Intelligence</div>
            <div className="brand-sub">Planning support for Indian state finances</div>
          </div>
        </div>
        <nav className="nav" aria-label="Main">
          {NAV.map((g) => (
            <div key={g.group} className="nav-section">
              <div className="nav-group">{g.group}</div>
              {g.items.map((n) => (
                <NavLink key={n.to} to={n.to} end={n.end}>
                  <span className="nav-label">{n.label}</span>
                  <span className="nav-hint">{n.hint}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          Source: Reserve Bank of India, Handbook of Statistics on Indian States 2024-25; World Bank WDI.
          Amounts in ₹ crore.
        </div>
      </aside>

      <div className="main">
        <div className="topbar">
          <label htmlFor="state-select">State</label>
          <select id="state-select" className="select" value={app.stateCode} onChange={(e) => app.setStateCode(e.target.value)}
            disabled={!app.states.length} style={{ minWidth: 220 }}>
            {app.states.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
          </select>
          <span className="spacer" />
          {app.dataset && (
            <span className="dataset-chip">
              Data: {app.dataset.label} · loaded {new Date(app.dataset.imported_at).toLocaleDateString('en-IN', { dateStyle: 'medium' })}
            </span>
          )}
          {noDataset && (
            <button className="btn btn-small" onClick={runImport} disabled={importing}>
              {importing ? 'Loading data… (about a minute)' : 'Load RBI dataset'}
            </button>
          )}
        </div>
        <main className="content">
          {app.statesError && <ErrorState error={app.statesError} onRetry={app.reloadAll} />}
          {noDataset && !importing && (
            <div className="callout info" style={{ marginBottom: 16 }}>
              <strong>No data has been loaded yet.</strong> Select “Load RBI dataset” to import the RBI and World Bank
              figures, the trained models and their past prediction records.
            </div>
          )}
          {importError && <ErrorState error={importError} />}
          <Outlet />
        </main>
      </div>
    </div>
  );
}
