-- Tracking for the "payment completed after the saga gave up" corner.
--
-- A customer cancellation (or the timeout sweep) no longer depends on PaymentCompleted having
-- arrived first: a charge that completes while the order is already cancelled is refunded by the
-- saga on the spot. `RefundStatus` carries the refund's own state, orthogonal to the order status:
-- a cancelled order is final, and yet it can still owe the customer a refund that is in flight or
-- still to be requested. Without a column of its own, a final status would swallow that fact.
--
-- New column with a default, so already-deployed orders get the value out of history that matches
-- the old behaviour (no outstanding refund) without a data migration pass.

ALTER TABLE orders ADD COLUMN refund_status VARCHAR(20) NOT NULL DEFAULT 'NONE';

-- Same style as the status check: names valid values at the storage layer, too.
ALTER TABLE orders
    ADD CONSTRAINT orders_refund_status_check CHECK (refund_status IN (
        'NONE',      -- no refund owed right now
        'REQUESTED', -- RefundPayment queued, PaymentRefunded not here yet
        'REFUNDED'   -- refund confirmed, completion applied
        ));

-- The sweeper also looks for refunds that never came back; this query now joins refund_status.
-- A partial index keeps it proportional to the in-flight set rather than to the order history.
DROP INDEX IF EXISTS idx_orders_unfinished;
CREATE INDEX idx_orders_unfinished ON orders (updated_at)
    WHERE status IN ('PENDING', 'STOCK_RESERVED', 'PAYMENT_PENDING', 'CANCELLATION_PENDING');
