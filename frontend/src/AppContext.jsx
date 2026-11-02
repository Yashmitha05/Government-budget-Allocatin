import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { useAsync } from './hooks';

const Ctx = createContext(null);
const STORAGE_KEY = 'sbi.state';

function readStored() {
  try { return localStorage.getItem(STORAGE_KEY); } catch { return null; }
}

export function AppProvider({ children }) {
  const states = useAsync(() => api.states(), []);
  const dataset = useAsync(() => api.dataset(), []);
  const [stateCode, setStateCode] = useState(readStored() || 'KA');

  const budgetStates = useMemo(() => (states.data || [])
    .filter((s) => s.has_budget_data && (s.category === 'STATE' || s.category === 'UT_LEGISLATURE'))
    .sort((a, b) => a.name.localeCompare(b.name)), [states.data]);

  useEffect(() => {
    if (budgetStates.length && !budgetStates.some((s) => s.code === stateCode)) setStateCode(budgetStates[0].code);
  }, [budgetStates, stateCode]);

  useEffect(() => { try { localStorage.setItem(STORAGE_KEY, stateCode); } catch { /* storage unavailable */ } }, [stateCode]);

  const value = {
    stateCode,
    setStateCode,
    states: budgetStates,
    stateName: budgetStates.find((s) => s.code === stateCode)?.name || stateCode,
    statesLoading: states.loading,
    statesError: states.error,
    dataset: dataset.data,
    datasetError: dataset.error,
    datasetLoading: dataset.loading,
    reloadAll: () => { states.reload(); dataset.reload(); },
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export const useApp = () => useContext(Ctx);
