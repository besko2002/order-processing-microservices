import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, buildHeaders, setUnauthorizedHandler } from './api';
import { ApiError, errorMessage, toApiError } from './ApiError';
import { writeToken } from './token';
import { installFetchMock } from '../test/fetchMock';

const TOKEN = 'token-abc';

afterEach(() => {
  setUnauthorizedHandler(null);
});

describe('api client: auth header', () => {
  it('sends the bearer token on authenticated calls', async () => {
    writeToken(TOKEN);
    const mock = installFetchMock({ 'GET /api/auth/me': { body: { id: '1', name: 'A', email: 'a@x', role: 'USER' } } });

    await api.me();

    expect(mock.callsTo('GET /api/auth/me')[0]?.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('does not send a token on public catalogue calls', async () => {
    writeToken(TOKEN);
    const mock = installFetchMock({ 'GET /api/products': { body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, last: true } } });

    await api.listProducts();

    expect(mock.calls[0]?.headers.Authorization).toBeUndefined();
  });

  it('omits the header when no token is stored', () => {
    expect(buildHeaders(true)).not.toHaveProperty('Authorization');
  });
});

describe('api client: ApiError parsing', () => {
  it('turns the backend ApiError JSON into a message and field errors', async () => {
    installFetchMock({
      'POST /api/auth/login': {
        status: 400,
        body: {
          timestamp: '2026-01-01T00:00:00Z',
          status: 400,
          error: 'Bad Request',
          message: 'Validation failed',
          path: '/api/auth/login',
          fieldErrors: { email: 'must not be blank' },
        },
      },
    });

    const error = await api.login({ email: '', password: '' }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).message).toBe('Validation failed');
    expect((error as ApiError).fieldErrors).toEqual({ email: 'must not be blank' });
  });

  it('falls back to a status message when the body is not JSON', () => {
    const error = toApiError(500, 'Internal Server Error', null);
    expect(error.message).toBe('Request failed (500 Internal Server Error).');
  });

  it('reads Retry-After seconds from a 503', () => {
    const error = toApiError(503, 'Service Unavailable', null, '7');
    expect(error.retryAfter).toBe(7);
    expect(errorMessage(error)).toMatch(/temporarily unavailable/i);
  });

  it('shows a friendly message on 429 instead of the raw body', () => {
    expect(errorMessage(toApiError(429, 'Too Many Requests', { message: 'raw' }))).toMatch(/too many requests/i);
  });

  it('reports a network failure as a readable error', async () => {
    const spy = vi.fn(() => Promise.reject(new TypeError('Failed to fetch')));
    vi.stubGlobal('fetch', spy);

    const error = await api.listProducts().catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).isNetworkError).toBe(true);
    expect(errorMessage(error)).toMatch(/could not reach the server/i);
  });
});

describe('api client: session end on 401', () => {
  it('calls the unauthorized handler when an authenticated call returns 401', async () => {
    writeToken(TOKEN);
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    installFetchMock({ 'GET /api/orders/me': { status: 401, body: { message: 'Authentication required' } } });

    await expect(api.myOrders()).rejects.toBeInstanceOf(ApiError);

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does not end the session on a 401 from the public login call', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    installFetchMock({ 'POST /api/auth/login': { status: 401, body: { message: 'Bad credentials' } } });

    await expect(api.login({ email: 'a@b.c', password: 'nope' })).rejects.toBeInstanceOf(ApiError);

    expect(handler).not.toHaveBeenCalled();
  });
});

describe('api client: checkout idempotency', () => {
  it('sends the Idempotency-Key header and the item payload on checkout', async () => {
    writeToken(TOKEN);
    const mock = installFetchMock({
      'POST /api/orders': { status: 201, body: { id: 'o-1', status: 'PENDING', items: [] } },
    });
    const productId = '9afeb881-d199-40bd-b81a-60aa7877b067';

    await api.placeOrder({ items: [{ productId, quantity: 2 }] }, 'key-123');

    const call = mock.callsTo('POST /api/orders')[0];
    expect(call?.headers['Idempotency-Key']).toBe('key-123');
    expect(call?.body).toEqual({ items: [{ productId, quantity: 2 }] });
    expect(call?.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('reuses the same key when the same checkout is retried', async () => {
    writeToken(TOKEN);
    const mock = installFetchMock({
      'POST /api/orders': { status: 201, body: { id: 'o-1', status: 'PENDING', items: [] } },
    });

    await api.placeOrder({ items: [{ productId: 'p', quantity: 1 }] }, 'same-key');
    await api.placeOrder({ items: [{ productId: 'p', quantity: 1 }] }, 'same-key');

    const keys = mock.callsTo('POST /api/orders').map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['same-key', 'same-key']);
  });
});

describe('api client: order events', () => {
  it('calls /api/orders/{id}/events with the bearer token and returns the rows', async () => {
    writeToken(TOKEN);
    const rows = [
      {
        eventType: 'OrderCreated',
        topic: 'order.events',
        createdAt: '2026-01-01T00:00:00Z',
        publishedAt: '2026-01-01T00:00:01Z',
        attempts: 0,
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
      },
    ];
    const mock = installFetchMock({ 'GET /api/orders/o-1/events': { body: rows } });

    const result = await api.orderEvents('o-1');

    expect(result).toEqual(rows);
    const call = mock.callsTo('GET /api/orders/o-1/events')[0];
    expect(call?.method).toBe('GET');
    expect(call?.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });
});
