import { isFinalStatus } from './saga';
import type { OrderStatus } from '../api/types';

/** Poll interval for a non-final order. Kept gentle: the gateway allows 10 requests/s per user. */
export const POLL_INTERVAL_MS = 2000;

/** Whether an order detail page should keep refreshing. */
export function shouldPoll(status: OrderStatus | null): boolean {
  return status !== null && !isFinalStatus(status);
}
