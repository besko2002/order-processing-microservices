import { Navigate, Outlet, useLocation } from 'react-router';
import { ErrorBanner } from '../components/states';
import { useAuth } from './useAuth';

function SessionLoading(): JSX.Element {
  return (
    <div className="centered-page" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <p>Restoring your session…</p>
    </div>
  );
}

/**
 * Shown when the stored token could not be checked because the server did not answer (not a 401).
 * The token is kept, so Retry checks again; Log out is the only way to drop the session from here.
 */
function SessionRestoreFailed(): JSX.Element {
  const { restoreError, retryRestore, logout } = useAuth();
  return (
    <div className="centered-page">
      <h1>Could not restore your session</h1>
      <p className="muted">Your sign-in is still saved. Nothing was changed.</p>
      <ErrorBanner error={restoreError} onRetry={retryRestore} />
      <div className="actions">
        <button type="button" onClick={logout}>
          Log out
        </button>
      </div>
    </div>
  );
}

/** Blocks the app until we know the visitor is signed in. */
export function ProtectedRoute(): JSX.Element {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <SessionLoading />;
  }
  if (status === 'restoreFailed') {
    return <SessionRestoreFailed />;
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}

/** Keeps signed-in users away from /login and /register. */
export function PublicOnlyRoute(): JSX.Element {
  const { status } = useAuth();

  if (status === 'loading') {
    return <SessionLoading />;
  }
  if (status === 'restoreFailed') {
    return <SessionRestoreFailed />;
  }
  if (status === 'authenticated') {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}

/** Admin screens. The API enforces the same rule; this only keeps the UI honest. */
export function AdminRoute(): JSX.Element {
  const { isAdmin } = useAuth();
  return isAdmin ? <Outlet /> : <Navigate to="/403" replace />;
}
