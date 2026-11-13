import { useState } from 'react';
import { api } from '../api/api';
import type { Reservation } from '../api/types';
import { ErrorBanner, Loading, Notice } from '../components/states';
import { formatDateTime, humanizeEnum } from '../lib/format';

type Outcome = { orderId: string; outcome: string };

/**
 * Stock reservations for one order, plus the reconcile action. The API lists reservations per
 * order (`GET /api/inventory/reservations?orderId=`), so the page asks for the order id.
 * Reconciliation releases stock only for orders the server verifies as cancelled or rejected.
 */
export function AdminReservationsPage(): JSX.Element {
  const [orderIdInput, setOrderIdInput] = useState('');
  const [orderId, setOrderId] = useState<string | null>(null);
  const [rows, setRows] = useState<Reservation[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const [reconcileIds, setReconcileIds] = useState('');
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);
  const [reconcileError, setReconcileError] = useState<unknown>(null);
  const [reconciling, setReconciling] = useState(false);

  const lookup = async (): Promise<void> => {
    const id = orderIdInput.trim();
    if (id === '') {
      return;
    }
    setLoading(true);
    setError(null);
    setOrderId(id);
    try {
      setRows(await api.reservationsOfOrder(id));
    } catch (cause) {
      setRows(null);
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  const parsedIds = reconcileIds
    .split(/[\s,]+/)
    .map((value) => value.trim())
    .filter((value) => value.length > 0);

  const runReconcile = async (): Promise<void> => {
    setReconciling(true);
    setReconcileError(null);
    try {
      const result = await api.reconcileReservations(
        parsedIds,
        reason.trim() === '' ? 'Operator reconciliation from the admin UI' : reason.trim(),
      );
      setOutcomes(result.entries.map((entry) => ({ orderId: entry.orderId, outcome: entry.outcome })));
      setConfirming(false);
    } catch (cause) {
      setReconcileError(cause);
      setConfirming(false);
    } finally {
      setReconciling(false);
    }
  };

  return (
    <section>
      <div className="page-head">
        <h1>Stock reservations</h1>
      </div>

      <div className="card">
        <h2>Look up an order</h2>
        <form
          className="inline-form"
          onSubmit={(event) => {
            event.preventDefault();
            void lookup();
          }}
        >
          <label className="field grow">
            <span>Order id</span>
            <input value={orderIdInput} onChange={(event) => setOrderIdInput(event.target.value)} placeholder="Order UUID" />
          </label>
          <button type="submit" disabled={loading || orderIdInput.trim() === ''}>
            {loading ? 'Looking up…' : 'Show reservations'}
          </button>
        </form>

        {error !== null ? <ErrorBanner error={error} onRetry={() => void lookup()} /> : null}
        {loading ? <Loading label="Loading reservations…" /> : null}
        {rows !== null && orderId !== null ? (
          rows.length === 0 ? (
            <p className="muted">No stock is reserved for order <code>{orderId}</code>.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th className="num">Quantity</th>
                  <th>Status</th>
                  <th>Held since</th>
                  <th>Last change</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td><code>{row.sku ?? row.productId}</code></td>
                    <td className="num">{row.quantity}</td>
                    <td>{humanizeEnum(row.status)}</td>
                    <td>{formatDateTime(row.createdAt)}</td>
                    <td>{formatDateTime(row.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        ) : null}
      </div>

      <div className="card">
        <h2>Reconcile leaked holds</h2>
        <p className="muted small">
          Gives back stock still held for cancelled or rejected orders. The server checks each order
          with order-service first and skips anything still active. Paste order ids separated by spaces
          or commas.
        </p>
        <label className="field">
          <span>Order ids</span>
          <textarea
            rows={3}
            value={reconcileIds}
            onChange={(event) => {
              setReconcileIds(event.target.value);
              setConfirming(false);
            }}
          />
        </label>
        <label className="field">
          <span>Reason (optional, stored for audit)</span>
          <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} />
        </label>

        {reconcileError !== null ? <ErrorBanner error={reconcileError} /> : null}

        {!confirming ? (
          <button
            type="button"
            className="secondary"
            disabled={parsedIds.length === 0 || reconciling}
            onClick={() => setConfirming(true)}
          >
            Reconcile {parsedIds.length} order(s)…
          </button>
        ) : (
          <div className="banner banner-info" role="alertdialog" aria-label="Confirm reconcile">
            <p>
              Reconcile {parsedIds.length} order id(s)? Stock is released only for orders that are verified
              as cancelled or rejected.
            </p>
            <div className="actions">
              <button type="button" className="secondary" onClick={() => setConfirming(false)}>
                Cancel
              </button>
              <button type="button" className="danger" disabled={reconciling} onClick={() => void runReconcile()}>
                {reconciling ? 'Reconciling…' : 'Yes, reconcile'}
              </button>
            </div>
          </div>
        )}

        {outcomes !== null ? (
          <table className="table">
            <thead>
              <tr>
                <th>Order id</th>
                <th>Outcome</th>
              </tr>
            </thead>
            <tbody>
              {outcomes.map((row) => (
                <tr key={row.orderId}>
                  <td><code>{row.orderId}</code></td>
                  <td>{humanizeEnum(row.outcome)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {outcomes !== null ? <Notice>Reconciliation finished. Check the outcome of each order above.</Notice> : null}
      </div>
    </section>
  );
}
