import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { api, ADMIN_HEADER } from '../api';
import { AdminKeyProvider, AdminKeyStatus, useAdminKey } from '../components/AdminKey';

const KEY = 'test-admin-key-0123456789abcdef-0123456789';

afterEach(() => { cleanup(); vi.restoreAllMocks(); document.title = ''; });

const ok = (body = {}) => new Response(JSON.stringify(body), { status: 200 });
const unauthorized = () => new Response(JSON.stringify({ status: 401, error: 'UNAUTHORIZED', message: 'A valid administrator key is required for this action' }), { status: 401 });
const sentHeaders = (spy, i = 0) => new Headers(spy.mock.calls[i][1].headers || {});

describe('admin header in the API client', () => {
  const adminCalls = {
    'POST /admin/datasets/import': (k) => api.importDataset(k),
    'POST /models/sync': (k) => api.syncModels(k),
    'POST /models/{indicator}/retrain': (k) => api.retrain('revenue_expenditure', k),
    'POST /models/{indicator}/calibrate': (k) => api.calibrate('revenue_expenditure', k),
    'DELETE /scenarios/{id}': (k) => api.deleteScenario(7, k),
  };

  it.each(Object.entries(adminCalls))('%s sends X-Admin-Api-Key', async (route, call) => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(ok());
    await call(KEY);
    const [method, path] = route.split(' ');
    expect(spy.mock.calls[0][1].method).toBe(method);
    expect(spy.mock.calls[0][0]).toMatch(new RegExp(path.replace(/\{[a-z]+\}/g, '[^/]+') + '$'));
    expect(sentHeaders(spy).get(ADMIN_HEADER)).toBe(KEY);
    expect(spy.mock.calls[0][0]).not.toContain(KEY);          // never in the URL
  });

  it('public GET and POST calls never carry the key', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => ok([]));
    await api.states();
    await api.models();
    await api.scenarios('KA');
    await api.forecast({ state_code: 'KA' });
    await api.createScenario({ state_code: 'KA' });
    await api.evaluate();
    spy.mock.calls.forEach((_, i) => expect(sentHeaders(spy, i).has(ADMIN_HEADER)).toBe(false));
    expect(sentHeaders(spy, 3).get('Content-Type')).toBe('application/json');   // JSON bodies unchanged
  });
});

function Harness({ call }) {
  const admin = useAdminKey();
  return (
    <>
      <AdminKeyStatus />
      <button onClick={() => admin.withAdminKey(call).then(() => { document.title = 'done'; }, (e) => { document.title = e.code; })}>Run</button>
    </>
  );
}

const enterKey = (value) => {
  fireEvent.change(screen.getByLabelText('Key'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
};

describe('administrator key dialog', () => {
  it('asks once, reuses the key in memory, and never writes it to storage, cookies or the page', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const call = vi.fn().mockResolvedValue({});
    render(<AdminKeyProvider><Harness call={call} /></AdminKeyProvider>);

    fireEvent.click(screen.getByText('Run'));
    expect(screen.getByRole('dialog', { name: 'Administrator key' })).toBeInTheDocument();
    expect(screen.getByLabelText('Key')).toHaveAttribute('type', 'password');
    enterKey(KEY);
    await waitFor(() => expect(call).toHaveBeenCalledWith(KEY));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('Administrator key in use')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Run'));
    await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();   // second action needs no prompt

    expect(document.body.innerHTML).not.toContain(KEY);
    expect(setItem).not.toHaveBeenCalled();
    expect(document.cookie).not.toContain(KEY);
  });

  it('clears a rejected key and asks again, then retries with the new one', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(unauthorized());
    const rejection = await api.importDataset('wrong').catch((e) => e);   // the real ApiError a 401 produces
    const call = vi.fn().mockRejectedValueOnce(rejection).mockResolvedValue({});
    render(<AdminKeyProvider><Harness call={call} /></AdminKeyProvider>);

    fireEvent.click(screen.getByText('Run'));
    enterKey('wrong-key');
    expect(await screen.findByText('The key was not accepted. Enter it again.')).toBeInTheDocument();
    expect(screen.queryByText('Administrator key in use')).not.toBeInTheDocument();
    enterKey(KEY);
    await waitFor(() => expect(document.title).toBe('done'));
    expect(call.mock.calls.map((c) => c[0])).toEqual(['wrong-key', KEY]);
  });

  it('cancelling stops the action without calling the backend', async () => {
    const call = vi.fn();
    render(<AdminKeyProvider><Harness call={call} /></AdminKeyProvider>);
    fireEvent.click(screen.getByText('Run'));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(document.title).toBe('ADMIN_CANCELLED'));
    expect(call).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('"Forget key" clears it, so the next action asks again', async () => {
    const call = vi.fn().mockResolvedValue({});
    render(<AdminKeyProvider><Harness call={call} /></AdminKeyProvider>);
    fireEvent.click(screen.getByText('Administrator'));
    enterKey(KEY);
    await screen.findByText('Administrator key in use');
    act(() => { fireEvent.click(screen.getByText('Forget key')); });
    expect(screen.getByText('Administrator')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Run'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(call).not.toHaveBeenCalled();
  });
});
