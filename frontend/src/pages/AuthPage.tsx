import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { ErrorBanner, Notice } from '../components/states';

type Mode = 'login' | 'register';

/** Sign-in and self-registration on one screen; the mode is the route. */
export function AuthPage({ mode }: { mode: Mode }): JSX.Element {
  const { login, register, notice, clearNotice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setError(null);
    clearNotice();
    setBusy(true);
    try {
      if (mode === 'login') {
        await login({ email, password });
      } else {
        await register({ name, email, password });
      }
      navigate(from, { replace: true });
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
    }
  };

  const fieldError = (field: string): string | undefined =>
    error instanceof ApiError ? error.fieldErrors[field] : undefined;

  return (
    <div className="centered-page">
      <form className="card auth-card" onSubmit={onSubmit} noValidate>
        <h1>{mode === 'login' ? 'Sign in' : 'Create an account'}</h1>
        {notice !== null ? <Notice>{notice}</Notice> : null}
        {error !== null ? <ErrorBanner error={error} /> : null}

        {mode === 'register' ? (
          <label className="field">
            <span>Name</span>
            <input value={name} onChange={(event) => setName(event.target.value)} required />
            {fieldError('name') !== undefined ? <small className="field-error">{fieldError('name')}</small> : null}
          </label>
        ) : null}

        <label className="field">
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
          {fieldError('email') !== undefined ? <small className="field-error">{fieldError('email')}</small> : null}
        </label>

        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
          {mode === 'register' ? <small className="hint">At least 8 characters.</small> : null}
          {fieldError('password') !== undefined ? <small className="field-error">{fieldError('password')}</small> : null}
        </label>

        <button type="submit" disabled={busy}>
          {busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>

        <p className="muted">
          {mode === 'login' ? (
            <>
              No account? <Link to="/register">Register</Link>
            </>
          ) : (
            <>
              Already registered? <Link to="/login">Sign in</Link>
            </>
          )}
        </p>
      </form>
    </div>
  );
}
