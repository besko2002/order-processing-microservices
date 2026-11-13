import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/api';
import type { OrderStatus } from '../api/types';
import { OrderStatusChip } from '../components/StatusChip';
import { EmptyState, ErrorBanner, Loading } from '../components/states';
import { formatDateTime, formatMoney, humanizeEnum } from '../lib/format';
import { useResource } from '../lib/useResource';

const PAGE_SIZE = 20;

const STATUSES: OrderStatus[] = [
  'PENDING',
  'STOCK_RESERVED',
  'PAYMENT_PENDING',
  'CONFIRMED',
  'CANCELLATION_PENDING',
  'CANCELLED',
  'REJECTED',
];

/** Every order in the system, with an optional status filter. ADMIN only. */
export function AdminOrdersPage(): JSX.Element {
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useResource(
    (signal) =>
      api.allOrders(
        { status: status === '' ? undefined : status, page, size: PAGE_SIZE },
        signal,
      ),
    [status, page],
  );

  return (
    <section>
      <div className="page-head">
        <h1>All orders</h1>
        <div className="toolbar">
          <label className="inline-field">
            <span>Status</span>
            <select
              value={status}
              onChange={(event) => {
                setPage(0);
                setStatus(event.target.value as OrderStatus | '');
              }}
            >
              <option value="">All</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {humanizeEnum(value)}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="secondary" onClick={reload}>
            Refresh
          </button>
        </div>
      </div>

      {error !== null ? <ErrorBanner error={error} onRetry={reload} /> : null}
      {loading && data === null ? <Loading label="Loading orders…" /> : null}

      {data !== null && data.content.length === 0 ? <EmptyState>No orders match.</EmptyState> : null}

      {data !== null && data.content.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Order</th>
              <th>Customer</th>
              <th>Placed</th>
              <th className="num">Total</th>
              <th>Status</th>
              <th>Reason</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((order) => (
              <tr key={order.id}>
                <td>
                  <Link to={`/orders/${order.id}`}>{order.orderNumber}</Link>
                  <div className="muted small"><code>{order.id}</code></div>
                </td>
                <td><code>{order.userId}</code></td>
                <td>{formatDateTime(order.createdAt)}</td>
                <td className="num">{formatMoney(order.totalAmount, order.currency)}</td>
                <td><OrderStatusChip status={order.status} /></td>
                <td className="small">{order.failureReason ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {data !== null && data.totalPages > 1 ? (
        <nav className="pager" aria-label="Pagination">
          <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </button>
          <span className="muted">Page {data.page + 1} of {data.totalPages}</span>
          <button type="button" className="secondary" disabled={data.last} onClick={() => setPage(page + 1)}>
            Next
          </button>
        </nav>
      ) : null}
    </section>
  );
}
