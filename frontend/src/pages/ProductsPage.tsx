import { useEffect, useState } from 'react';
import { api } from '../api/api';
import type { Product } from '../api/types';
import { ErrorBanner, EmptyState, Loading, Notice } from '../components/states';
import { formatMoney } from '../lib/format';
import { useCart } from '../lib/useCart';
import { useResource } from '../lib/useResource';

const PAGE_SIZE = 20;

/** Public catalogue: name, SKU, price and available quantity, with add-to-cart. */
export function ProductsPage(): JSX.Element {
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useResource(
    (signal) => api.listProducts({ page, size: PAGE_SIZE }, signal),
    [page],
  );
  const { add, cart } = useCart();
  const [added, setAdded] = useState<string | null>(null);

  useEffect(() => {
    if (added === null) {
      return;
    }
    const timer = window.setTimeout(() => {
      setAdded(null);
    }, 2500);
    return () => {
      window.clearTimeout(timer);
    };
  }, [added]);

  const inCart = (product: Product): number =>
    cart.find((line) => line.productId === product.id)?.quantity ?? 0;

  return (
    <section>
      <div className="page-head">
        <h1>Products</h1>
        <span className="muted">{cart.length > 0 ? `${String(cart.length)} line(s) in your cart` : 'Your cart is empty'}</span>
      </div>

      {added !== null ? <Notice>Added “{added}” to your cart.</Notice> : null}
      {error !== null ? <ErrorBanner error={error} onRetry={reload} /> : null}
      {loading && data === null ? <Loading label="Loading products…" /> : null}

      {data !== null && data.content.length === 0 ? (
        <EmptyState>No products are available yet.</EmptyState>
      ) : null}

      {data !== null && data.content.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>SKU</th>
              <th className="num">Price</th>
              <th className="num">Available</th>
              <th>Cart</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((product) => (
              <tr key={product.id}>
                <td>
                  <strong>{product.name}</strong>
                  {product.description !== null ? <div className="muted small">{product.description}</div> : null}
                </td>
                <td><code>{product.sku}</code></td>
                <td className="num">{formatMoney(product.price)}</td>
                <td className="num">{product.availableQuantity}</td>
                <td>
                  <button
                    type="button"
                    disabled={product.availableQuantity === 0}
                    onClick={() => {
                      add(product);
                      setAdded(product.name);
                    }}
                  >
                    {product.availableQuantity === 0 ? 'Sold out' : 'Add to cart'}
                  </button>
                  {inCart(product) > 0 ? <span className="muted small"> ×{inCart(product)} in cart</span> : null}
                </td>
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
          <span className="muted">
            Page {data.page + 1} of {data.totalPages}
          </span>
          <button type="button" className="secondary" disabled={data.last} onClick={() => setPage(page + 1)}>
            Next
          </button>
        </nav>
      ) : null}
    </section>
  );
}
