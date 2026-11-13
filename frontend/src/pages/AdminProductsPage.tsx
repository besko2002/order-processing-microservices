import { useState, type FormEvent } from 'react';
import { api } from '../api/api';
import { ApiError } from '../api/ApiError';
import type { Product, ProductAdmin } from '../api/types';
import { ErrorBanner, EmptyState, Loading, Notice } from '../components/states';
import { formatMoney } from '../lib/format';
import { useResource } from '../lib/useResource';

type Draft = {
  sku: string;
  name: string;
  description: string;
  price: string;
  availableQuantity: string;
};

const EMPTY_DRAFT: Draft = { sku: '', name: '', description: '', price: '', availableQuantity: '0' };

function toPrice(value: string): number {
  return Math.round(Number(value) * 100) / 100;
}

function optionalText(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/** Create, edit, adjust stock and delete products. ADMIN only. */
export function AdminProductsPage(): JSX.Element {
  const { data, error, loading, reload } = useResource(
    (signal) => api.listProducts({ page: 0, size: 100 }, signal),
    [],
  );
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState<Product | null>(null);
  const [formError, setFormError] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [stockDeltas, setStockDeltas] = useState<Record<string, string>>({});

  const startEdit = (product: Product): void => {
    setEditing(product);
    setDraft({
      sku: product.sku,
      name: product.name,
      description: product.description ?? '',
      price: String(product.price),
      availableQuantity: String(product.availableQuantity),
    });
    setFormError(null);
    setMessage(null);
  };

  const resetForm = (): void => {
    setEditing(null);
    setDraft(EMPTY_DRAFT);
    setFormError(null);
  };

  const submit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setBusy(true);
    setFormError(null);
    setMessage(null);
    try {
      if (editing === null) {
        const created: ProductAdmin = await api.createProduct({
          sku: draft.sku.trim(),
          name: draft.name.trim(),
          description: optionalText(draft.description),
          price: toPrice(draft.price),
          availableQuantity: Number(draft.availableQuantity),
        });
        setMessage(`Created ${created.sku}.`);
      } else {
        const updated = await api.updateProduct(editing.id, {
          name: draft.name.trim(),
          description: optionalText(draft.description),
          price: toPrice(draft.price),
        });
        setMessage(`Saved ${updated.sku}.`);
      }
      resetForm();
      reload();
    } catch (cause) {
      setFormError(cause);
    } finally {
      setBusy(false);
    }
  };

  const adjust = async (product: Product): Promise<void> => {
    const delta = Number(stockDeltas[product.id] ?? '');
    if (!Number.isInteger(delta) || delta === 0) {
      setFormError(new ApiError(400, 'Enter a whole number of units: positive to add, negative to remove.'));
      return;
    }
    setBusy(true);
    setFormError(null);
    setMessage(null);
    try {
      const updated = await api.adjustStock(product.id, delta);
      setMessage(`${updated.sku} stock is now ${String(updated.availableQuantity)}.`);
      setStockDeltas((current) => ({ ...current, [product.id]: '' }));
      reload();
    } catch (cause) {
      setFormError(cause);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (product: Product): Promise<void> => {
    if (!window.confirm(`Delete ${product.sku}? This cannot be undone.`)) {
      return;
    }
    setBusy(true);
    setFormError(null);
    setMessage(null);
    try {
      await api.deleteProduct(product.id);
      setMessage(`Deleted ${product.sku}.`);
      reload();
    } catch (cause) {
      // 409: an order is holding stock of this product; the backend message says so.
      setFormError(cause);
    } finally {
      setBusy(false);
    }
  };

  const fieldError = (field: string): string | undefined =>
    formError instanceof ApiError ? formError.fieldErrors[field] : undefined;

  return (
    <section>
      <div className="page-head">
        <h1>Manage products</h1>
        <button type="button" className="secondary" onClick={reload}>
          Refresh
        </button>
      </div>

      {message !== null ? <Notice>{message}</Notice> : null}
      {formError !== null ? <ErrorBanner error={formError} /> : null}

      <form className="card form-grid" onSubmit={(event) => void submit(event)} noValidate>
        <h2>{editing === null ? 'New product' : `Edit ${editing.sku}`}</h2>
        <label className="field">
          <span>SKU</span>
          <input
            value={draft.sku}
            disabled={editing !== null}
            onChange={(event) => setDraft({ ...draft, sku: event.target.value })}
            required
          />
          {fieldError('sku') !== undefined ? <small className="field-error">{fieldError('sku')}</small> : null}
        </label>
        <label className="field">
          <span>Name</span>
          <input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} required />
          {fieldError('name') !== undefined ? <small className="field-error">{fieldError('name')}</small> : null}
        </label>
        <label className="field">
          <span>Description</span>
          <input value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
        </label>
        <label className="field">
          <span>Price</span>
          <input
            type="number"
            step="0.01"
            min="0"
            value={draft.price}
            onChange={(event) => setDraft({ ...draft, price: event.target.value })}
            required
          />
          {fieldError('price') !== undefined ? <small className="field-error">{fieldError('price')}</small> : null}
        </label>
        {editing === null ? (
          <label className="field">
            <span>Starting stock</span>
            <input
              type="number"
              min="0"
              step="1"
              value={draft.availableQuantity}
              onChange={(event) => setDraft({ ...draft, availableQuantity: event.target.value })}
              required
            />
          </label>
        ) : null}
        <div className="actions">
          {editing !== null ? (
            <button type="button" className="secondary" onClick={resetForm}>
              Cancel edit
            </button>
          ) : null}
          <button type="submit" disabled={busy}>
            {editing === null ? 'Create product' : 'Save changes'}
          </button>
        </div>
      </form>

      <h2>Catalogue</h2>
      {error !== null ? <ErrorBanner error={error} onRetry={reload} /> : null}
      {loading && data === null ? <Loading label="Loading products…" /> : null}
      {data !== null && data.content.length === 0 ? <EmptyState>No products yet.</EmptyState> : null}

      {data !== null && data.content.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Name</th>
              <th className="num">Price</th>
              <th className="num">Available</th>
              <th>Adjust stock</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.content.map((product) => (
              <tr key={product.id}>
                <td><code>{product.sku}</code></td>
                <td>{product.name}</td>
                <td className="num">{formatMoney(product.price)}</td>
                <td className="num">{product.availableQuantity}</td>
                <td>
                  <div className="inline-actions">
                    <input
                      type="number"
                      step="1"
                      aria-label={`Stock change for ${product.sku}`}
                      placeholder="+/- units"
                      className="qty"
                      value={stockDeltas[product.id] ?? ''}
                      onChange={(event) =>
                        setStockDeltas((current) => ({ ...current, [product.id]: event.target.value }))
                      }
                    />
                    <button type="button" className="secondary" disabled={busy} onClick={() => void adjust(product)}>
                      Apply
                    </button>
                  </div>
                </td>
                <td>
                  <div className="inline-actions">
                    <button type="button" className="secondary" onClick={() => startEdit(product)}>
                      Edit
                    </button>
                    <button type="button" className="danger" disabled={busy} onClick={() => void remove(product)}>
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}
