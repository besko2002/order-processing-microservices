import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/api';
import { EmptyState, ErrorBanner, Loading } from '../components/states';
import { formatDateTime, humanizeEnum } from '../lib/format';
import { useResource } from '../lib/useResource';

const PAGE_SIZE = 20;

export function NotificationsPage(): JSX.Element {
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useResource(
    (signal) => api.myNotifications({ page, size: PAGE_SIZE }, signal),
    [page],
  );

  return (
    <section>
      <div className="page-head">
        <h1>Notifications</h1>
        <button type="button" className="secondary" onClick={reload}>
          Refresh
        </button>
      </div>

      {error !== null ? <ErrorBanner error={error} onRetry={reload} /> : null}
      {loading && data === null ? <Loading label="Loading notifications…" /> : null}

      {data !== null && data.content.length === 0 ? (
        <EmptyState>No notifications yet. They appear here when an order is confirmed, rejected or cancelled.</EmptyState>
      ) : null}

      {data !== null && data.content.length > 0 ? (
        <ul className="notifications">
          {data.content.map((note) => (
            <li key={note.id} className="card notification">
              <div className="notification-head">
                <strong>{note.subject}</strong>
                <span className="muted small">{formatDateTime(note.createdAt)}</span>
              </div>
              <p>{note.body}</p>
              <p className="muted small">
                {humanizeEnum(note.type)} · {note.channel} to {note.recipient} · {humanizeEnum(note.status)} ·{' '}
                <Link to={`/orders/${note.orderId}`}>order</Link>
              </p>
            </li>
          ))}
        </ul>
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
