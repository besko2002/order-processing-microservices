/** Status used when the request never reached the server. */
export const NETWORK_ERROR_STATUS = 0;

export const NETWORK_ERROR_MESSAGE =
  'Could not reach the server. Check your connection and try again.';

const RATE_LIMITED_MESSAGE = 'Too many requests. Please wait a moment and try again.';

const UNAVAILABLE_MESSAGE = 'The service is temporarily unavailable. Please try again shortly.';

/**
 * Error thrown by every API call. `status` is the HTTP status (0 when the request failed before
 * reaching the server). `retryAfter` carries the `Retry-After` header in seconds when present.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;
  readonly body: unknown;
  readonly retryAfter: number | null;

  constructor(
    status: number,
    message: string,
    fieldErrors: Record<string, string> = {},
    body: unknown = null,
    retryAfter: number | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.body = body;
    this.retryAfter = retryAfter;
  }

  get isNetworkError(): boolean {
    return this.status === NETWORK_ERROR_STATUS;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }

  get isUnavailable(): boolean {
    return this.status === 503;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readFieldErrors(value: unknown): Record<string, string> {
  if (!isRecord(value)) {
    return {};
  }
  const result: Record<string, string> = {};
  for (const [key, message] of Object.entries(value)) {
    if (typeof message === 'string') {
      result[key] = message;
    }
  }
  return result;
}

function defaultMessage(status: number, statusText: string): string {
  if (statusText.trim().length > 0) {
    return `Request failed (${String(status)} ${statusText}).`;
  }
  return `Request failed (${String(status)}).`;
}

function parseRetryAfter(value: string | null): number | null {
  if (value === null) {
    return null;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

/**
 * Builds an {@link ApiError} from a parsed (possibly malformed) backend error body. The body is
 * the shared `ApiError` JSON: `{ timestamp, status, error, message, path, fieldErrors? }`.
 */
export function toApiError(
  status: number,
  statusText: string,
  body: unknown,
  retryAfterHeader: string | null = null,
): ApiError {
  const record = isRecord(body) ? body : null;
  const rawMessage = record?.message;
  const message =
    typeof rawMessage === 'string' && rawMessage.trim().length > 0
      ? rawMessage
      : defaultMessage(status, statusText);
  return new ApiError(
    status,
    message,
    readFieldErrors(record?.fieldErrors),
    body,
    parseRetryAfter(retryAfterHeader),
  );
}

/** The message to show for any thrown value, so no screen renders "[object Object]". */
export function errorMessage(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.isRateLimited) {
      return RATE_LIMITED_MESSAGE;
    }
    if (cause.isUnavailable) {
      return UNAVAILABLE_MESSAGE;
    }
    if (cause.isNetworkError) {
      return NETWORK_ERROR_MESSAGE;
    }
    return cause.message;
  }
  if (cause instanceof Error && cause.message.trim().length > 0) {
    return cause.message;
  }
  return 'Something went wrong. Please try again.';
}
