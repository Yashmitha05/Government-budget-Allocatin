import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api';

// The administrator key is entered at runtime and held only in this provider's memory: never in storage, cookies,
// the URL or the build. It is gone on reload, on "Forget key", and whenever the backend rejects it.
const Ctx = createContext(null);

export const ADMIN_CANCELLED = 'ADMIN_CANCELLED';

export function AdminKeyProvider({ children }) {
  const keyRef = useRef(null);
  const [hasKey, setHasKey] = useState(false);
  const [prompt, setPrompt] = useState(null);   // {rejected, resolve} while the dialog is open

  const setKey = (k) => { keyRef.current = k; setHasKey(Boolean(k)); };
  const clearKey = useCallback(() => setKey(null), []);

  const askForKey = (rejected) => new Promise((resolve) => setPrompt({ rejected, resolve }));

  const closePrompt = (value) => {
    prompt?.resolve(value);
    setPrompt(null);
  };

  /** Run call(key) with the administrator key, asking for it first if needed and again if the backend rejects it. */
  const withAdminKey = useCallback(async (call) => {
    let rejected = false;
    for (;;) {
      if (!keyRef.current) {
        const entered = await askForKey(rejected);
        if (!entered) throw new ApiError(0, ADMIN_CANCELLED, 'Cancelled: an administrator key is needed for this action.');
        setKey(entered);
      }
      try {
        return await call(keyRef.current);
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 401) throw e;
        clearKey();
        rejected = true;
      }
    }
  }, [clearKey]);

  const value = { hasKey, clearKey, withAdminKey, enterKey: async () => { const k = await askForKey(false); if (k) setKey(k); } };
  return (
    <Ctx.Provider value={value}>
      {children}
      {prompt && <AdminKeyDialog rejected={prompt.rejected} onSubmit={closePrompt} onCancel={() => closePrompt(null)} />}
    </Ctx.Provider>
  );
}

export const useAdminKey = () => useContext(Ctx);

function AdminKeyDialog({ rejected, onSubmit, onCancel }) {
  const [value, setValue] = useState('');
  const input = useRef(null);
  useEffect(() => { input.current?.focus(); }, []);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onCancel(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const submit = (e) => {
    e.preventDefault();
    const k = value.trim();
    setValue('');
    if (k) onSubmit(k);
  };

  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onCancel(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-labelledby="admin-key-title" onSubmit={submit}>
        <h2 id="admin-key-title">Administrator key</h2>
        <p className="small muted">
          Loading data, syncing or retraining models, and deleting saved scenarios are restricted to the administrator.
          The key is kept only in this tab&apos;s memory and is forgotten when you reload or close the page.
        </p>
        {rejected && <div className="callout bad" role="alert">The key was not accepted. Enter it again.</div>}
        <div className="field">
          <label htmlFor="admin-key-input">Key</label>
          <input id="admin-key-input" ref={input} className="input" type="password" name="admin-key"
            autoComplete="off" spellCheck={false} value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        <div className="modal-actions">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>Cancel</button>
          <button type="submit" className="btn" disabled={!value.trim()}>Continue</button>
        </div>
      </form>
    </div>
  );
}

/** Top-bar control: shows whether a key is held for this tab and lets the administrator enter or forget it. */
export function AdminKeyStatus() {
  const admin = useAdminKey();
  return admin.hasKey ? (
    <span className="admin-chip">
      Administrator key in use
      <button type="button" className="btn-link" onClick={admin.clearKey}>Forget key</button>
    </span>
  ) : (
    <button type="button" className="btn-link admin-enter" onClick={admin.enterKey}>Administrator</button>
  );
}
