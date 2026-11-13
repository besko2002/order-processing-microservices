import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, newIdempotencyKey } from '../api/api';
import { ApiError } from '../api/ApiError';
import { EmptyState, ErrorBanner } from '../components/states';
import { MAX_CART_LINES, MAX_LINE_QUANTITY } from '../lib/cart';
import { formatMoney } from '../lib/format';
import { useCart } from '../lib/useCart';

/**
 * The cart and checkout. One Idempotency-Key is generated per checkout attempt and reused on a
 * retry of the same attempt, so a double-click or a lost reply cannot place two orders.
 */
export function CartPage(): JSX.Element {
  const { cart, total, setLine, clear } = useCart();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [attemptKey, setAttemptKey] = useState<string | null>(null);

  const checkout = async (): Promise<void> => {
    if (submitting || cart.length === 0) {
      return;
    }
    setSubmitting(true);
    setError(null);
    // A new key per attempt; a retry after a failed attempt reuses the key it already sent.
    const key = attemptKey ?? newIdempotencyKey();
    setAttemptKey(key);
    try {
      const order = await api.placeOrder(
        { items: cart.map((line) => ({ productId: line.productId, quantity: line.quantity })) },
        key,
      );
      clear();
      setAttemptKey(null);
      navigate(`/orders/${order.id}`);
    } catch (cause) {
      // A conflict means the key was already used for different items: start a new attempt.
      if (cause instanceof ApiError && cause.isConflict) {
        setAttemptKey(null);
      }
      setError(cause);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section>
      <div className="page-head">
        <h1>Cart</h1>
      </div>

      {cart.length === 0 ? (
        <EmptyState>
          Your cart is empty. <Link to="/">Browse products</Link>.
        </EmptyState>
      ) : (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Product</th>
                <th className="num">Unit price</th>
                <th className="num">Quantity</th>
                <th className="num">Line total</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {cart.map((line) => (
                <tr key={line.productId}>
                  <td>{line.name}</td>
                  <td className="num">{formatMoney(line.unitPrice)}</td>
                  <td className="num">
                    <input
                      type="number"
                      min={0}
                      max={MAX_LINE_QUANTITY}
                      aria-label={`Quantity of ${line.name}`}
                      value={line.quantity}
                      onChange={(event) => setLine(line.productId, Number(event.target.value))}
                      className="qty"
                    />
                  </td>
                  <td className="num">{formatMoney(line.unitPrice * line.quantity)}</td>
                  <td>
                    <button type="button" className="secondary" onClick={() => setLine(line.productId, 0)}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="summary">
            <div>
              <span className="muted">Estimated total</span>
              <strong data-testid="cart-total">{formatMoney(total)}</strong>
            </div>
            <p className="muted small">
              The price is confirmed by the server at checkout. Up to {MAX_CART_LINES} lines and{' '}
              {MAX_LINE_QUANTITY} units per line.
            </p>
            {error !== null ? <ErrorBanner error={error} /> : null}
            <div className="actions">
              <button type="button" className="secondary" onClick={clear} disabled={submitting}>
                Empty cart
              </button>
              <button type="button" onClick={() => void checkout()} disabled={submitting}>
                {submitting ? 'Placing order…' : 'Place order'}
              </button>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
