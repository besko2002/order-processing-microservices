import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api/api';
import { ApiError, errorMessage } from '../api/ApiError';
import type { Order, OrderEvent, Payment } from '../api/types';
import { BehindTheScenes } from '../components/BehindTheScenes';
import { SagaTimeline } from '../components/SagaTimeline';
import { OrderStatusChip, PaymentStatusChip } from '../components/StatusChip';
import { ErrorBanner, Loading, Notice } from '../components/states';
import { CANCEL_UNAVAILABLE_MESSAGE } from '../lib/messages';
import { canCancel, isFinalStatus } from '../lib/saga';
import { formatDateTime, formatMoney } from '../lib/format';
import { POLL_INTERVAL_MS, shouldPoll } from '../lib/poll';
import { useResource } from '../lib/useResource';

/** One order: the Saga timeline, payment status, lines, and the cancel action. */
export function OrderDetailPage(): JSX.Element {
  const { id = '' } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  // The payment answer is kept with the order status it was fetched for. A stale answer for an
  // earlier status is never shown as current, so no reset-in-effect is needed.
  const [paymentAnswer, setPaymentAnswer] = useState<{
    forStatus: string;
    payment: Payment | null;
  } | null>(null);
  const [paymentError, setPaymentError] = useState<unknown>(null);
  const [cancelError, setCancelError] = useState<unknown>(null);
  const [cancelling, setCancelling] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);

  const { data, error } = useResource(
    (signal) => api.getOrder(id, signal),
    [id, tick],
  );

  // Each fresh fetch replaces the shown order. Derived during render, not mirrored in an effect.
  if (data !== null && data !== order) {
    setOrder(data);
    setLastRefreshed(new Date());
  }

  // Keep polling while the order is not final. The timer stops by itself once a final state lands.
  useEffect(() => {
    if (!shouldPoll(order?.status ?? null)) {
      return;
    }
    const timer = window.setTimeout(() => {
      setTick((value) => value + 1);
    }, POLL_INTERVAL_MS);
    return () => {
      window.clearTimeout(timer);
    };
  }, [order]);

  // A failed poll keeps the last good state on screen; the error is shown beside it.
  const pollError = error !== null && order !== null ? error : null;

  // Payment is fetched once the order exists; it is re-read on each order refresh too.
  useEffect(() => {
    if (order === null) {
      return;
    }
    const controller = new AbortController();
    const forStatus = order.status;
    api
      .paymentForOrder(order.id, controller.signal)
      .then((found) => {
        setPaymentAnswer({ forStatus, payment: found });
        setPaymentError(null);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        // 404 simply means no payment was attempted yet (e.g. rejected for stock).
        if (cause instanceof ApiError && cause.status === 404) {
          setPaymentAnswer({ forStatus, payment: null });
          setPaymentError(null);
          return;
        }
        setPaymentError(cause);
      });
    return () => {
      controller.abort();
    };
  }, [order?.id, order?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  // The "behind the scenes" events are re-read with each order refresh (the same tick). Keyed by
  // the order id and status they were fetched for, so a stale list is never shown as current.
  const [eventsAnswer, setEventsAnswer] = useState<{
    orderId: string;
    forStatus: string;
    events: OrderEvent[];
  } | null>(null);
  const [eventsError, setEventsError] = useState<unknown>(null);

  useEffect(() => {
    if (order === null) {
      return;
    }
    const controller = new AbortController();
    const orderId = order.id;
    const forStatus = order.status;
    api
      .orderEvents(orderId, controller.signal)
      .then((events) => {
        setEventsAnswer({ orderId, forStatus, events });
        setEventsError(null);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setEventsError(cause);
      });
    return () => {
      controller.abort();
    };
  }, [order?.id, order?.status, tick]); // eslint-disable-line react-hooks/exhaustive-deps

  const onCancel = async (): Promise<void> => {
    if (order === null || cancelling) {
      return;
    }
    if (!window.confirm('Cancel this order?')) {
      return;
    }
    setCancelling(true);
    setCancelError(null);
    try {
      setOrder(await api.cancelOrder(order.id));
      setTick((value) => value + 1);
    } catch (cause) {
      // 409: the state moved on, e.g. it was already being cancelled. Show the backend message.
      setCancelError(
        cause instanceof ApiError && cause.isConflict
          ? new ApiError(409, `${errorMessage(cause)} ${CANCEL_UNAVAILABLE_MESSAGE}`)
          : cause,
      );
    } finally {
      setCancelling(false);
    }
  };

  if (order === null) {
    if (error !== null) {
      return (
        <section>
          <Link to="/orders">← My orders</Link>
          <ErrorBanner error={error} onRetry={() => setTick((value) => value + 1)} />
        </section>
      );
    }
    return <Loading label="Loading order…" />;
  }

  const final = isFinalStatus(order.status);
  // `undefined` while the payment request for the current status is in flight; `null` when it
  // answered 404 (no payment was ever attempted).
  const answerIsCurrent = paymentAnswer !== null && paymentAnswer.forStatus === order.status;
  const payment = answerIsCurrent ? paymentAnswer.payment : null;
  const paymentStatus = answerIsCurrent ? (payment?.status ?? null) : undefined;

  return (
    <section>
      <Link to="/orders">← My orders</Link>
      <div className="page-head">
        <h1>{order.orderNumber}</h1>
        <OrderStatusChip status={order.status} />
      </div>

      <dl className="facts">
        <div><dt>Order id</dt><dd><code>{order.id}</code></dd></div>
        <div><dt>Placed</dt><dd>{formatDateTime(order.createdAt)}</dd></div>
        <div><dt>Last update</dt><dd>{formatDateTime(order.updatedAt)}</dd></div>
        <div><dt>Total</dt><dd data-testid="order-total">{formatMoney(order.totalAmount, order.currency)}</dd></div>
      </dl>

      {order.failureReason !== undefined && order.failureReason !== null ? (
        <div className="banner banner-error" role="alert">
          Reason: {order.failureReason}
        </div>
      ) : null}

      {!final ? (
        <Notice>
          This order is still processing. Refreshing every {POLL_INTERVAL_MS / 1000} seconds
          {lastRefreshed !== null ? ` · last refresh ${lastRefreshed.toLocaleTimeString()}` : ''}.
        </Notice>
      ) : null}
      {pollError !== null && !final ? <ErrorBanner error={pollError} /> : null}

      <h2>Progress</h2>
      <SagaTimeline status={order.status} payment={paymentStatus} />

      <h2>Behind the scenes</h2>
      {eventsError !== null ? <ErrorBanner error={eventsError} /> : null}
      {eventsAnswer !== null && eventsAnswer.orderId === order.id ? (
        <BehindTheScenes events={eventsAnswer.events} />
      ) : (
        <p className="muted">Loading the order&apos;s events…</p>
      )}

      <h2>Payment</h2>
      {paymentError !== null ? <ErrorBanner error={paymentError} /> : null}
      {payment !== null ? (
        <dl className="facts">
          <div><dt>Status</dt><dd><PaymentStatusChip status={payment.status} /></dd></div>
          <div><dt>Amount</dt><dd>{formatMoney(payment.amount, payment.currency)}</dd></div>
          <div><dt>Provider reference</dt><dd><code>{payment.providerRef}</code></dd></div>
          <div><dt>Updated</dt><dd>{formatDateTime(payment.updatedAt)}</dd></div>
          {payment.failureReason !== undefined ? (
            <div><dt>Payment note</dt><dd>{payment.failureReason}</dd></div>
          ) : null}
        </dl>
      ) : paymentStatus === undefined ? (
        <p className="muted">Checking payment…</p>
      ) : (
        <p className="muted">No payment has been attempted for this order.</p>
      )}

      <h2>Items</h2>
      <table className="table">
        <thead>
          <tr>
            <th>Product</th>
            <th className="num">Unit price</th>
            <th className="num">Quantity</th>
            <th className="num">Line total</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.productId}>
              <td>{item.productName}</td>
              <td className="num">{formatMoney(item.unitPrice, order.currency)}</td>
              <td className="num">{item.quantity}</td>
              <td className="num">{formatMoney(item.lineTotal, order.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {canCancel(order.status) ? (
        <div className="actions">
          {cancelError !== null ? <ErrorBanner error={cancelError} /> : null}
          <button type="button" className="danger" onClick={() => void onCancel()} disabled={cancelling}>
            {cancelling ? 'Cancelling…' : 'Cancel order'}
          </button>
        </div>
      ) : null}
      {cancelError !== null && !canCancel(order.status) ? <ErrorBanner error={cancelError} /> : null}
    </section>
  );
}
