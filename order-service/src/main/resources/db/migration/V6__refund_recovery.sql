-- Recovery bookkeeping for refunds that never completed.
--
-- `refund_status` tells the saga a RefundPayment is outstanding, but on its own the flag cannot
-- tell the recovery sweep WHEN the command was last made: without a timestamp the only options
-- are hammering payment-service on every tick or never retrying. This column records the moment
-- the refund was last commanded (first request or recovery re-queue — whichever was more recent);
-- the sweep re-commands refunds that have not been answered within the retry interval. Bookkeeping
-- and the re-queued command are written in one transaction, so the timestamp can never claim an
-- attempt the outbox does not show. The row's optimistic-locking version keeps concurrent
-- recovery attempts from double-booking at the same instant — the loser retries on fresh data and
-- finds the newer timestamp, which throttles the second attempt instead of duplicating it.
--
-- Legacy in-flight refunds (requested before this column existed) are stamped from updated_at,
-- which is when the original command was queued: they become retryable after one interval, not
-- the instant of the migration.

ALTER TABLE orders ADD COLUMN refund_retry_at TIMESTAMPTZ;

UPDATE orders
SET refund_retry_at = updated_at
WHERE refund_status = 'REQUESTED'
  AND refund_retry_at IS NULL;

-- The recovery sweep's query: refunds the saga still owes for orders that are cancelled (or
-- cancel while the refund is outstanding). Partial and ordered by the retry timestamp, so one
-- sweep takes at most the 50 oldest-due refunds and a backlog the sweep cannot catch up with
-- still advances oldest-first instead of starving behind the same head of the queue.
CREATE INDEX idx_orders_refund_recovery ON orders (refund_retry_at)
    WHERE refund_status = 'REQUESTED'
      AND status IN ('CANCELLED', 'CANCELLATION_PENDING');
