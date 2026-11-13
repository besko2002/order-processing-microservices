import type { ApiError } from '../api/ApiError';

/** Wait before the single automatic retry when the server gave no Retry-After. */
export const DEFAULT_AUTO_RETRY_MS = 2000;

/** Never wait longer than this before the automatic retry, whatever the server asked. */
export const MAX_AUTO_RETRY_MS = 10000;

/**
 * Delay before the automatic retry of a session restore: the server's `Retry-After` when given,
 * capped at {@link MAX_AUTO_RETRY_MS}; otherwise {@link DEFAULT_AUTO_RETRY_MS}.
 */
export function autoRetryDelayMs(cause: Pick<ApiError, 'retryAfter'>): number {
  if (cause.retryAfter === null) {
    return DEFAULT_AUTO_RETRY_MS;
  }
  return Math.min(Math.max(cause.retryAfter * 1000, 0), MAX_AUTO_RETRY_MS);
}
