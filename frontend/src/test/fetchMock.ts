import { vi, type Mock } from 'vitest';

/** A canned HTTP reply for the fetch mock. */
export interface MockReply {
  status?: number;
  statusText?: string;
  /** Serialised as the JSON response body; omit for an empty body. */
  body?: unknown;
  /** Response headers, e.g. `Retry-After`. */
  headers?: Record<string, string>;
}

export interface RecordedCall {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

export type RouteHandler = MockReply | ((call: RecordedCall) => MockReply);

export interface FetchMock {
  spy: Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>;
  calls: RecordedCall[];
  callsTo: (key: string) => RecordedCall[];
}

const STATUS_TEXT: Record<number, string> = {
  200: 'OK',
  201: 'Created',
  204: 'No Content',
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  503: 'Service Unavailable',
};

function makeResponse(reply: MockReply): Response {
  const status = reply.status ?? 200;
  const text = reply.body === undefined ? '' : JSON.stringify(reply.body);
  const response = {
    ok: status >= 200 && status < 300,
    status,
    statusText: reply.statusText ?? STATUS_TEXT[status] ?? '',
    headers: new Headers(reply.headers),
    text: () => Promise.resolve(text),
  };
  return response as unknown as Response;
}

function parseBody(body: BodyInit | null | undefined): unknown {
  if (typeof body !== 'string') {
    return null;
  }
  try {
    return JSON.parse(body) as unknown;
  } catch {
    return body;
  }
}

/**
 * Replaces global fetch with a table-driven mock. Keys look like `"GET /api/auth/me"`; a key that
 * includes a query string matches it exactly, otherwise the path alone matches. A request with no
 * handler rejects, so an unexpected call fails the test instead of passing silently.
 */
export function installFetchMock(table: Record<string, RouteHandler>): FetchMock {
  const calls: RecordedCall[] = [];

  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: RecordedCall = { method, url, headers, body: parseBody(init?.body) };
    calls.push(call);

    const handler = table[`${method} ${url}`] ?? table[`${method} ${url.split('?')[0]}`];
    if (handler === undefined) {
      return Promise.reject(new Error(`Unexpected request: ${method} ${url}`));
    }
    return Promise.resolve(makeResponse(typeof handler === 'function' ? handler(call) : handler));
  });

  vi.stubGlobal('fetch', spy);

  return {
    spy,
    calls,
    callsTo: (key: string) => calls.filter((call) => `${call.method} ${call.url}` === key),
  };
}
