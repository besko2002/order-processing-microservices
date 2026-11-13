-- Persisted cancellation intent for stock.
--
-- `OrderCreated` and `ReleaseStock` travel on different Kafka topics (order.events vs.
-- inventory.commands), and Kafka gives no ordering guarantee across topics. Until now, a
-- ReleaseStock that arrived before its OrderCreated was answered with UNKNOWN_ORDER —
-- remembered in the log, nowhere else — and the OrderCreated that followed could still reserve
-- stock for a cancelled order. The late StockReserved was then dropped by the orchestrator and
-- the hold sat in reserved_columns forever: an inventory leak.
--
-- This table is the durable half of the fix: a ReleaseStock always leaves a row here, whatever
-- the reservation state was (rows released, or never reserved at all). An OrderCreated checks
-- it before reserving — when a row exists, the order was cancelled before its stock could be
-- reserved, and the answer is a StockRejected instead of a reservation.
--
-- The row's existence is the fact; there is no lifecycle. The order will never un-cancel, so
-- there is nothing to undo: duplicate releases (ON CONFLICT, below) and any number of duplicate
-- or replayed OrderCreated events keep landing on StockRejected. One row per order id, written
-- for orders that were cancelled while their saga was young — order ids, not all orders.
CREATE TABLE order_release_intents
(
    order_id   UUID         PRIMARY KEY,
    reason     VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
