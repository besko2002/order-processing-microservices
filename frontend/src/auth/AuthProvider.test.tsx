import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { AuthProvider } from './AuthProvider';
import { ProtectedRoute, PublicOnlyRoute } from './RouteGuards';
import { useAuth } from './useAuth';
import { readToken, writeToken } from '../api/token';
import { installFetchMock, type MockReply } from '../test/fetchMock';
import type { UserResponse } from '../api/types';
import { autoRetryDelayMs, DEFAULT_AUTO_RETRY_MS } from '../lib/restore';
import { ApiError } from '../api/ApiError';

const TOKEN = 'stored-token';
const USER: UserResponse = { id: 'u1', name: 'Ann', email: 'ann@x.io', role: 'USER' };
const ME_KEY = 'GET /api/auth/me';

/** What the app shows at each route, plus a probe that reports the auth status. */
function Probe(): JSX.Element {
  const { status } = useAuth();
  return <p>status:{status}</p>;
}

function renderApp(path = '/orders'): void {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<p>login page</p>} />
          <Route element={<PublicOnlyRoute />}>
            <Route path="/register" element={<p>register page</p>} />
          </Route>
          <Route element={<ProtectedRoute />}>
            <Route path="/orders" element={<p>my orders</p>} />
          </Route>
        </Routes>
        <Probe />
      </MemoryRouter>
    </AuthProvider>,
  );
}

/** A reply that makes the mocked fetch answer with the given HTTP error. */
function httpError(status: number, extra: Partial<MockReply> = {}): MockReply {
  return { status, body: { message: `error ${String(status)}` }, ...extra };
}

describe('AuthProvider: session restore on a cold start', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('a 401 from /me clears the token and sends the visitor to /login', async () => {
    writeToken(TOKEN);
    installFetchMock({ [ME_KEY]: httpError(401) });

    renderApp('/orders');

    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(readToken()).toBeNull();
  });

  it('a successful /me makes the visitor authenticated and keeps the token', async () => {
    writeToken(TOKEN);
    installFetchMock({ [ME_KEY]: { body: USER } });

    renderApp('/orders');

    expect(await screen.findByText('my orders')).toBeInTheDocument();
    expect(readToken()).toBe(TOKEN);
  });

  it.each([
    ['a 429 rate-limit', httpError(429)],
    ['a 503 with Retry-After', httpError(503, { headers: { 'Retry-After': '60' } })],
    ['a 500', httpError(500)],
  ])('%s keeps the token, does not redirect, and offers Retry', async (_label, reply) => {
    writeToken(TOKEN);
    installFetchMock({ [ME_KEY]: reply });

    renderApp('/orders');

    expect(await screen.findByText('Could not restore your session')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    expect(screen.queryByText('login page')).not.toBeInTheDocument();
    expect(readToken()).toBe(TOKEN);
  });

  it('a network error keeps the token, does not redirect, and offers Retry', async () => {
    writeToken(TOKEN);
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));

    renderApp('/orders');

    expect(await screen.findByText('Could not restore your session')).toBeInTheDocument();
    expect(screen.getByText(/Could not reach the server/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('login page')).not.toBeInTheDocument();
    expect(readToken()).toBe(TOKEN);
  });

  it('after a transient failure, Retry succeeds and the app becomes authenticated', async () => {
    writeToken(TOKEN);
    let calls = 0;
    const mock = installFetchMock({
      [ME_KEY]: () => {
        calls += 1;
        return calls === 1 ? httpError(500) : { body: USER };
      },
    });

    renderApp('/orders');
    const retry = await screen.findByRole('button', { name: 'Retry' });
    await userEvent.click(retry);

    expect(await screen.findByText('my orders')).toBeInTheDocument();
    expect(mock.callsTo(ME_KEY)).toHaveLength(2);
    expect(readToken()).toBe(TOKEN);
  });

  it('Log out from the restore screen clears the token and goes to /login', async () => {
    writeToken(TOKEN);
    installFetchMock({ [ME_KEY]: httpError(500) });

    renderApp('/orders');
    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }));

    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(readToken()).toBeNull();
  });

  it('retries a 429 once on its own after the Retry-After delay', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    writeToken(TOKEN);
    let calls = 0;
    installFetchMock({
      [ME_KEY]: () => {
        calls += 1;
        return calls === 1 ? httpError(429, { headers: { 'Retry-After': '1' } }) : { body: USER };
      },
    });

    renderApp('/orders');
    expect(await screen.findByText('Could not restore your session')).toBeInTheDocument();
    expect(calls).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    await waitFor(() => {
      expect(screen.getByText('my orders')).toBeInTheDocument();
    });
    expect(calls).toBe(2);
  });

  it('does not auto-retry a 500, only a 429 or 503', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    writeToken(TOKEN);
    const mock = installFetchMock({ [ME_KEY]: httpError(500) });

    renderApp('/orders');
    expect(await screen.findByText('Could not restore your session')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_AUTO_RETRY_MS * 3);
    });

    expect(mock.callsTo(ME_KEY)).toHaveLength(1);
  });
});

describe('restore delay helper', () => {
  it('honours Retry-After in seconds, capped at ten seconds', () => {
    expect(autoRetryDelayMs(new ApiError(429, 'x', {}, null, 3))).toBe(3000);
    expect(autoRetryDelayMs(new ApiError(503, 'x', {}, null, 600))).toBe(10000);
  });

  it('falls back to the default delay when Retry-After is absent', () => {
    expect(autoRetryDelayMs(new ApiError(429, 'x'))).toBe(DEFAULT_AUTO_RETRY_MS);
  });
});
