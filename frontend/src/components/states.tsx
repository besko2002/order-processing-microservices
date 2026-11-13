import type { ReactNode } from 'react';
import { errorMessage } from '../api/ApiError';

export function Loading({ label = 'Loading…' }: { label?: string }): JSX.Element {
  return (
    <div className="state state-loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }): JSX.Element {
  return <div className="state state-empty">{children}</div>;
}

export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }): JSX.Element {
  return (
    <div className="banner banner-error" role="alert">
      <span>{errorMessage(error)}</span>
      {onRetry !== undefined ? (
        <button type="button" className="link-button" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  );
}

export function Notice({ children }: { children: ReactNode }): JSX.Element {
  return (
    <div className="banner banner-info" role="status">
      {children}
    </div>
  );
}
