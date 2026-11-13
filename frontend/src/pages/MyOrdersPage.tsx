import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/api';
import { OrderStatusChip } from '../components/StatusChip';
import { EmptyState, ErrorBanner, Loading } from '../components/states';
import { formatDateTime, formatMoney } from '../lib/format';
import { useResource } from '../lib/useResource';

const PAGE_SIZE = 20;

export function MyOrdersPage(): JSX.Element {
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useResource(
    (signal) => api.myOrders({ page, size: PAGE_SIZE }, signal),
    [page],
  );

  return (
    <section>
      <div className="page-head">
        <h1>My orders</h1>
        <button type="button" className="secondary" onClick={reload}>
          Refresh
        </button>
      </div>

      {error !== null ? <ErrorBanner error={error} onRetry={reload} /> : null}
      {loading && data === null ? <Loading label="Loading orders…" /> : null}

      {data !== null && data.content.length === 0 ? (
        <EmptyState>
          You have not placed an order yet. <Link to="/">Browse products</Link>.
        </EmptyState>
      ) : null}

      {data !== null && data.content.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Order</th>
              <th>Placed</th>
              <th className="num">Total</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((order) => (
              <tr key={order.id}>
                <td>
                  <Link to={`/orders/${order.id}`}>{order.orderNumber}</Link>
                  <div className="muted small"><code>{order.id}</code></div>
                </td>
                <td>{formatDateTime(order.createdAt)}</td>
                <td className="num">{formatMoney(order.totalAmount, order.currency)}</td>
                <td><OrderStatusChip status={order.status} /></td>
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
