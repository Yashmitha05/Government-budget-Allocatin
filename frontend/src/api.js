// Backend REST client. All JSON is snake_case; errors arrive as {status, error, message, details}.
const BASE = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api').replace(/\/$/, '');

export class ApiError extends Error {
  constructor(status, code, message, details = []) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

async function request(method, path, body) {
  let res;
  try {
    res = await fetch(BASE + path, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'BACKEND_UNREACHABLE', import.meta.env.DEV
      ? 'The application server is not reachable. Check that the backend is running on ' + BASE.replace(/\/api$/, '') + '.'
      : 'The application server is not reachable.');
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON body */ }
  if (!res.ok) {
    throw new ApiError(res.status, data?.error || 'HTTP_' + res.status,
      data?.message || `Request failed (HTTP ${res.status})`, data?.details || []);
  }
  return data;
}

const q = (params) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') s.append(k, v); });
  const str = s.toString();
  return str ? '?' + str : '';
};

export const api = {
  states: () => request('GET', '/states'),
  indicators: () => request('GET', '/indicators'),
  dataset: () => request('GET', '/datasets/current'),
  importDataset: () => request('POST', '/admin/datasets/import'),
  impactModels: () => request('GET', '/impact-models'),

  dashboard: (state) => request('GET', '/dashboard/summary' + q({ state })),
  records: (state, indicator, fromYear, toYear) =>
    request('GET', '/budget-records' + q({ state, indicator, from_year: fromYear, to_year: toYear })),
  compare: (state, year, previousYear) => request('GET', '/budget-records/compare' + q({ state, year, previous_year: previousYear })),
  baseline: (state, year) => request('GET', '/budget-records/baseline' + q({ state, year })),

  forecast: (body) => request('POST', '/forecasts', body),

  createScenario: (body) => request('POST', '/scenarios', body),
  scenarios: (state) => request('GET', '/scenarios' + q({ state })),
  scenario: (id) => request('GET', `/scenarios/${id}`),
  compareScenarios: (ids) => request('GET', '/scenarios/compare' + q({ ids: ids.join(',') })),
  deleteScenario: (id) => request('DELETE', `/scenarios/${id}`),

  optimize: (body) => request('POST', '/optimizations', body),
  optimizations: (state) => request('GET', '/optimizations' + q({ state })),

  stressTest: (body) => request('POST', '/stress-tests', body),
  stressTests: (state) => request('GET', '/stress-tests' + q({ state })),

  deviations: (params) => request('GET', '/feedback/deviations' + q(params)),
  accuracySummary: () => request('GET', '/feedback/summary'),
  evaluate: () => request('POST', '/feedback/evaluate'),
  models: (indicator) => request('GET', '/models' + q({ indicator })),
  retrain: (indicator) => request('POST', `/models/${indicator}/retrain`),
  calibrate: (indicator) => request('POST', `/models/${indicator}/calibrate`),
};
