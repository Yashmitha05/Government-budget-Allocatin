import { useCallback, useEffect, useRef, useState } from 'react';

/** Run an async loader when deps change. Ignores stale responses. */
export function useAsync(loader, deps) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const seq = useRef(0);
  const run = useCallback(() => {
    const id = ++seq.current;
    setState((s) => ({ data: s.data, error: null, loading: true }));
    loader()
      .then((data) => { if (id === seq.current) setState({ data, error: null, loading: false }); })
      .catch((error) => { if (id === seq.current) setState({ data: null, error, loading: false }); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { run(); }, [run]);
  return { ...state, reload: run };
}

/** Wrap a user-triggered async action: {run, running, error, result}. */
export function useAction(action) {
  const [state, setState] = useState({ running: false, error: null, result: null });
  const run = useCallback(async (...args) => {
    setState({ running: true, error: null, result: null });
    try {
      const result = await action(...args);
      setState({ running: false, error: null, result });
      return result;
    } catch (error) {
      setState({ running: false, error, result: null });
      return undefined;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const reset = useCallback(() => setState({ running: false, error: null, result: null }), []);
  return { ...state, run, reset };
}
