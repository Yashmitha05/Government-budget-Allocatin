// Number and label formatting. Money is held in ₹ crore; Indian digit grouping (en-IN).

const IN0 = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
const IN1 = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const IN2 = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

/** ₹2,90,531 cr */
export const crore = (v) => (isNum(v) ? `₹${IN0.format(v)} cr` : '–');

/** Headline form: values ≥ 1 lakh crore as "₹2.91 lakh cr", else "₹82,981 cr". */
export const croreShort = (v) => {
  if (!isNum(v)) return '–';
  if (Math.abs(v) >= 100000) return `₹${IN2.format(v / 100000)} lakh cr`;
  return `₹${IN0.format(v)} cr`;
};

/** Axis ticks: 2.5L, 80k */
export const croreAxis = (v) => {
  if (!isNum(v)) return '';
  if (Math.abs(v) >= 100000) return `${+(v / 100000).toFixed(1)}L`;
  if (Math.abs(v) >= 1000) return `${Math.round(v / 1000)}k`;
  return String(Math.round(v));
};

export const pct = (v, digits = 1) => (isNum(v) ? `${v.toFixed(digits)}%` : '–');
export const signedPct = (v, digits = 1) => (isNum(v) ? `${v > 0 ? '+' : ''}${v.toFixed(digits)}%` : '–');
export const signedCrore = (v) => (isNum(v) ? `${v > 0 ? '+' : v < 0 ? '−' : ''}₹${IN0.format(Math.abs(v))} cr` : '–');
export const rupees = (v) => (isNum(v) ? `₹${IN0.format(v)}` : '–');
export const number = (v, digits = 0) => (isNum(v) ? (digits ? IN1.format(v) : IN0.format(v)) : '–');

export const STATUS = {
  ACCOUNTS: { label: 'Actual (accounts)', short: 'Actual', kind: 'actual' },
  ACTUAL: { label: 'Actual', short: 'Actual', kind: 'actual' },
  RE: { label: 'Revised estimate', short: 'RE', kind: 'estimate' },
  BE: { label: 'Budget estimate', short: 'BE', kind: 'estimate' },
  OBSERVED: { label: 'Published', short: 'Published', kind: '' },
  DERIVED: { label: 'Derived', short: 'Derived', kind: '' },
  FORECAST: { label: 'Model forecast', short: 'Forecast', kind: 'forecast' },
};

export const SECTORS = {
  social_sector: { label: 'Social sector', long: 'Social sector (education, health, welfare)', color: 'var(--sector-social)' },
  capital_outlay: { label: 'Capital outlay', long: 'Capital outlay (infrastructure and assets)', color: 'var(--sector-capital)' },
  other_revenue: { label: 'Other spending', long: 'Other revenue spending (administration, economic services)', color: 'var(--sector-other)' },
};

export const INDICATOR_LABELS = {
  revenue_expenditure: 'Revenue expenditure',
  own_tax_revenue: 'Own tax revenue',
  own_non_tax_revenue: 'Own non-tax revenue',
  capital_expenditure: 'Capital expenditure',
  social_sector_expenditure: 'Social sector expenditure',
};

export const indicatorLabel = (code, fallback) => INDICATOR_LABELS[code] || fallback || code;

export const fiscalYearsDesc = (from = 2004, to = 2024) => {
  const out = [];
  for (let y = to; y >= from; y--) out.push(`${y}-${String((y + 1) % 100).padStart(2, '0')}`);
  return out;
};

export const dateTime = (iso) => (iso ? new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '–');
