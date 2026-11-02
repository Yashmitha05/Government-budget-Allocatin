import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { crore, croreShort, croreAxis, pct, signedPct, signedCrore, fiscalYearsDesc } from '../format';
import { api, ApiError } from '../api';
import DeficitRuler, { aboveLimit } from '../components/DeficitRuler';
import { ErrorState } from '../components/ui';
import IndicatorEffects from '../components/IndicatorEffects';

describe('format', () => {
  it('uses Indian digit grouping and lakh crore', () => {
    expect(crore(290531)).toBe('₹2,90,531 cr');
    expect(croreShort(290531)).toBe('₹2.91 lakh cr');
    expect(croreShort(82981)).toBe('₹82,981 cr');
    expect(croreAxis(200000)).toBe('2L');
    expect(croreAxis(50000)).toBe('50k');
  });
  it('handles missing values and signs', () => {
    expect(crore(null)).toBe('–');
    expect(pct(undefined)).toBe('–');
    expect(signedPct(12)).toBe('+12.0%');
    expect(signedPct(-1.43)).toBe('-1.4%');
    expect(signedCrore(-6348)).toBe('−₹6,348 cr');
  });
  it('lists fiscal years newest first', () => {
    expect(fiscalYearsDesc(2022, 2024)).toEqual(['2024-25', '2023-24', '2022-23']);
    expect(fiscalYearsDesc(1999, 1999)).toEqual(['1999-00']);
  });
});

describe('deficit limit', () => {
  it('treats exactly 3% as within the limit', () => {
    expect(aboveLimit(3.0000000001)).toBe(false);
    expect(aboveLimit(3.01)).toBe(true);
    expect(aboveLimit(null)).toBe(false);
  });
  it('labels each mark', () => {
    render(<DeficitRuler marks={[{ label: 'Current', value: 2.88, color: '#000' }, { label: 'Scenario', value: 3.1, color: '#111' }]} />);
    expect(screen.getByText('Within limit')).toBeInTheDocument();
    expect(screen.getByText('Above limit')).toBeInTheDocument();
    expect(screen.getByText('3.10%')).toBeInTheDocument();
  });
});

describe('api client', () => {
  afterEach(() => vi.restoreAllMocks());

  it('turns backend error bodies into ApiError', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      status: 400, error: 'VALIDATION_ERROR', message: 'Request validation failed', details: ['state_code: must be a state code'],
    }), { status: 400 }));
    const err = await api.forecast({}).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('VALIDATION_ERROR');
    expect(err.details).toEqual(['state_code: must be a state code']);
  });

  it('reports an unreachable backend', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await api.states().catch((e) => e);
    expect(err.code).toBe('BACKEND_UNREACHABLE');
  });

  it('builds query strings without empty parameters', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }));
    await api.records('KA', 'pension', undefined, '');
    expect(spy.mock.calls[0][0]).toMatch(/\/budget-records\?state=KA&indicator=pension$/);
  });
});

describe('components', () => {
  it('shows a recovery hint when the analytics service is down', () => {
    render(<ErrorState error={new ApiError(503, 'ML_SERVICE_UNAVAILABLE', 'The analytics service is currently unavailable.')} />);
    expect(screen.getByRole('alert')).toHaveTextContent('port 8000');
  });

  it('never shows a predicted value for an unsupported relationship', () => {
    render(<IndicatorEffects effects={[{
      indicator: 'infant_mortality_rate', indicator_name: 'Infant mortality rate', driver_sector: 'health',
      current_value: 14, current_as_of: '2023-24', predicted_value: null, lower: null, upper: null, supported: false,
      elasticity: 0.088, elasticity_ci: [-0.114, 0.368], method_note: 'x',
    }]} />);
    expect(screen.getByText(/No reliable effect can be predicted/)).toBeInTheDocument();
    expect(screen.getByText(/Not measurable/)).toBeInTheDocument();
  });
});
