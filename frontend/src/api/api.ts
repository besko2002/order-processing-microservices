import { ApiError, NETWORK_ERROR_MESSAGE, NETWORK_ERROR_STATUS, toApiError } from './ApiError';
import { readToken } from './token';
import type {
  AuthResponse,
  CreateOrderRequest,
  CreateProductRequest,
  LoginRequest,
  Notification,
  Order,
  OrderEvent,
  OrderStatus,
  PageResponse,
  Payment,
  Product,
  ProductAdmin,
  ReconciliationResponse,
  RegisterRequest,
  Reservation,
  UpdateProductRequest,
  UserResponse,
} from './types';

type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler | null = null;

/**
 * Registers the callback fired when an *authenticated* call comes back 401: the token expired or
 * was rejected. The auth provider uses it to drop the session and send the user to /login.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Serialised as a JSON request body. */
  json?: unknown;
  /** Extra headers, e.g. `Idempotency-Key`. */
  headers?: Record<string, string>;
  /** Set to false for the public endpoints (login, register, catalogue). */
  auth?: boolean;
  signal?: AbortSignal;
}

function parseJsonText(text: string): unknown {
  if (text.trim().length === 0) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

async function readBody(response: Response): Promise<unknown> {
  try {
    return parseJsonText(await response.text());
  } catch {
    return null;
  }
}

/** Builds the request headers, attaching the bearer token when `auth` is set. */
export function buildHeaders(
  auth: boolean,
  extra: Record<string, string> = {},
): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (auth) {
    const token = readToken();
    if (token !== null && token.length > 0) {
      headers.Authorization = `Bearer ${token}`;
    }
  }
  return headers;
}

function notifyAuthFailure(error: ApiError, auth: boolean): void {
  if (auth && error.status === 401) {
    unauthorizedHandler?.();
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', json, headers: extra, auth = true, signal } = options;
  const headers = buildHeaders(auth, extra);

  let body: string | undefined;
  if (json !== undefined) {
    body = JSON.stringify(json);
    headers['Content-Type'] = 'application/json';
  }

  let response: Response;
  try {
    response = await fetch(path, { method, headers, body, signal });
  } catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') {
      throw cause;
    }
    throw new ApiError(NETWORK_ERROR_STATUS, NETWORK_ERROR_MESSAGE);
  }

  if (!response.ok) {
    const error = toApiError(
      response.status,
      response.statusText,
      await readBody(response),
      response.headers.get('Retry-After'),
    );
    notifyAuthFailure(error, auth);
    throw error;
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await readBody(response)) as T;
}

const seg = (value: string): string => encodeURIComponent(value);

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.append(key, String(value));
  }
  const text = search.toString();
  return text.length > 0 ? `?${text}` : '';
}

/** Generates a fresh idempotency key for one checkout attempt. */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

export const api = {
  // ----------------------------------------------------------------- auth
  login: (payload: LoginRequest): Promise<AuthResponse> =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', json: payload, auth: false }),

  register: (payload: RegisterRequest): Promise<AuthResponse> =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', json: payload, auth: false }),

  me: (signal?: AbortSignal): Promise<UserResponse> =>
    request<UserResponse>('/api/auth/me', { signal }),

  // ----------------------------------------------------------------- catalogue
  listProducts: (
    params: { page?: number; size?: number; q?: string } = {},
    signal?: AbortSignal,
  ): Promise<PageResponse<Product>> =>
    request<PageResponse<Product>>(`/api/products${query({ ...params })}`, {
      auth: false,
      signal,
    }),

  getProduct: (id: string, signal?: AbortSignal): Promise<Product> =>
    request<Product>(`/api/products/${seg(id)}`, { auth: false, signal }),

  // ----------------------------------------------------------------- orders
  placeOrder: (payload: CreateOrderRequest, idempotencyKey: string): Promise<Order> =>
    request<Order>('/api/orders', {
      method: 'POST',
      json: payload,
      headers: { 'Idempotency-Key': idempotencyKey },
    }),

  myOrders: (
    params: { page?: number; size?: number } = {},
    signal?: AbortSignal,
  ): Promise<PageResponse<Order>> =>
    request<PageResponse<Order>>(`/api/orders/me${query({ ...params })}`, { signal }),

  getOrder: (id: string, signal?: AbortSignal): Promise<Order> =>
    request<Order>(`/api/orders/${seg(id)}`, { signal }),

  orderEvents: (id: string, signal?: AbortSignal): Promise<OrderEvent[]> =>
    request<OrderEvent[]>(`/api/orders/${seg(id)}/events`, { signal }),

  cancelOrder: (id: string): Promise<Order> =>
    request<Order>(`/api/orders/${seg(id)}/cancel`, { method: 'POST' }),

  allOrders: (
    params: { status?: OrderStatus; page?: number; size?: number } = {},
    signal?: AbortSignal,
  ): Promise<PageResponse<Order>> =>
    request<PageResponse<Order>>(`/api/orders${query({ ...params })}`, { signal }),

  // ----------------------------------------------------------------- payments
  paymentForOrder: (orderId: string, signal?: AbortSignal): Promise<Payment> =>
    request<Payment>(`/api/payments/order/${seg(orderId)}`, { signal }),

  // ----------------------------------------------------------------- notifications
  myNotifications: (
    params: { page?: number; size?: number } = {},
    signal?: AbortSignal,
  ): Promise<PageResponse<Notification>> =>
    request<PageResponse<Notification>>(`/api/notifications/me${query({ ...params })}`, {
      signal,
    }),

  // ----------------------------------------------------------------- inventory admin
  createProduct: (payload: CreateProductRequest): Promise<ProductAdmin> =>
    request<ProductAdmin>('/api/inventory/products', { method: 'POST', json: payload }),

  updateProduct: (id: string, payload: UpdateProductRequest): Promise<ProductAdmin> =>
    request<ProductAdmin>(`/api/inventory/products/${seg(id)}`, { method: 'PUT', json: payload }),

  adjustStock: (id: string, delta: number): Promise<ProductAdmin> =>
    request<ProductAdmin>(`/api/inventory/products/${seg(id)}/stock`, {
      method: 'PATCH',
      json: { delta },
    }),

  deleteProduct: (id: string): Promise<void> =>
    request<void>(`/api/inventory/products/${seg(id)}`, { method: 'DELETE' }),

  reservationsOfOrder: (orderId: string, signal?: AbortSignal): Promise<Reservation[]> =>
    request<Reservation[]>(`/api/inventory/reservations${query({ orderId })}`, { signal }),

  reconcileReservations: (orderIds: string[], reason: string): Promise<ReconciliationResponse> =>
    request<ReconciliationResponse>('/api/inventory/reservations/reconcile', {
      method: 'POST',
      json: { orderIds, reason },
    }),
};
