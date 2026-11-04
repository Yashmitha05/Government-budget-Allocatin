import { useEffect, useId, useState } from 'react';
import { STATUS } from '../format';
import Rosette from './Rosette';

/** Page masthead: indigo band with guilloche ground and a rosette watermark. eyebrow names the page's group. */
export function PageHead({ title, lede, context, actions, eyebrow }) {
  return (
    <header className="masthead">
      <Rosette className="masthead-rosette" opacity={0.09} />
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {lede && <p className="lede">{lede}</p>}
        {context && <div className="context-line">{context}</div>}
      </div>
      {actions && <div className="form-row">{actions}</div>}
    </header>
  );
}

/** Section heading inside a page, with a short gold label. */
export function SectionTitle({ eyebrow, title, children }) {
  return (
    <div className="section-title">
      {eyebrow && <div className="eyebrow">{eyebrow}</div>}
      <h2>{title}</h2>
      {children && <p>{children}</p>}
    </div>
  );
}

export function Card({ title, sub, actions, children, className = '' }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          <div>
            {title && <h2>{title}</h2>}
            {sub && <div className="sub">{sub}</div>}
          </div>
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

/** Small "i" button that reveals a plain-language explanation on hover, focus or tap. */
export function InfoTip({ children, label = 'What does this mean?' }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <span className="tip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button type="button" className="tip-btn" aria-label={label} aria-describedby={open ? id : undefined}
        onFocus={() => setOpen(true)} onBlur={() => setOpen(false)} onClick={() => setOpen((o) => !o)}>i</button>
      {open && <span role="tooltip" id={id} className="tip-bubble">{children}</span>}
    </span>
  );
}

/** Headline figure. accent: CSS colour of the top rule, keyed to what the figure measures. */
export function Stat({ label, tip, value, unit, note, tone, accent }) {
  return (
    <div className="card stat" style={accent ? { '--accent': accent } : undefined}>
      <div className="stat-label">{label}{tip && <InfoTip>{tip}</InfoTip>}</div>
      <div className="stat-value" style={tone ? { color: `var(--${tone})` } : undefined}>
        {value}{unit && <span className="stat-unit">{unit}</span>}
      </div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

export function StatusBadge({ status }) {
  const s = STATUS[status] || { label: status, kind: '' };
  return <span className={`badge ${s.kind}`} title={s.label}>{s.label}</span>;
}

export function Loading({ height = 120, label = 'Loading' }) {
  return <div className="skeleton" style={{ height }} role="status" aria-label={label} />;
}

// Local development names the process to start; the hosted site gives guidance a visitor can act on.
const HINTS = import.meta.env.DEV ? {
  BACKEND_UNREACHABLE: 'Start the backend (port 8080) and reload the page.',
  ML_SERVICE_UNAVAILABLE: 'The analytics service (port 8000) is not responding. Start it and try again.',
  NO_DATASET: 'Load the data first using “Load RBI dataset” at the top of the page.',
} : {
  BACKEND_UNREACHABLE: 'The server may be starting up after a period of inactivity. Wait a minute and reload the page.',
  ML_SERVICE_UNAVAILABLE: 'The analytics service may be starting up after a period of inactivity. Wait a minute and try again.',
  NO_DATASET: 'Load the data first using “Load RBI dataset” at the top of the page.',
};

export function ErrorState({ error, onRetry, compact }) {
  if (!error || error.code === 'ADMIN_CANCELLED') return null;   // the administrator closed the key dialog
  const hint = HINTS[error.code];
  return (
    <div className={`callout bad${compact ? '' : ''}`} role="alert">
      <strong>{error.message}</strong>
      {error.details?.length > 0 && <ul>{error.details.map((d) => <li key={d}>{d}</li>)}</ul>}
      {hint && <div style={{ marginTop: 6 }}>{hint}</div>}
      {onRetry && <div style={{ marginTop: 8 }}><button className="btn btn-secondary btn-small" onClick={onRetry}>Try again</button></div>}
    </div>
  );
}

export function Empty({ title, children }) {
  return (
    <div className="state-box">
      <Rosette className="state-rosette" color="#1a2d55" opacity={0.28} rings={[[44, 18, 18], [28, 11, 14]]} />
      <h3>{title}</h3>
      <div className="small">{children}</div>
    </div>
  );
}

export function MethodNote({ title = 'How this is calculated', children }) {
  return (
    <details className="method">
      <summary>{title}</summary>
      <div className="method-body">{children}</div>
    </details>
  );
}

export function Toast({ message, onDone }) {
  useEffect(() => {
    if (!message) return undefined;
    const t = setTimeout(onDone, 3500);
    return () => clearTimeout(t);
  }, [message, onDone]);
  if (!message) return null;
  return <div className="toast" role="status">{message}</div>;
}

export function Segmented({ value, options, onChange, label }) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" className={value === o.value ? 'on' : ''} aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}
