import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, setUnauthorizedHandler } from '../api/api';
import { ApiError } from '../api/ApiError';
import { readToken, writeToken } from '../api/token';
import type { AuthResponse, LoginRequest, RegisterRequest, UserResponse } from '../api/types';
import { SESSION_EXPIRED_NOTICE } from '../lib/messages';
import { autoRetryDelayMs } from '../lib/restore';
import { AuthContext, type AuthContextValue, type AuthStatus } from './authContext';

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [token, setToken] = useState<string | null>(() => readToken());
  const [user, setUser] = useState<UserResponse | null>(null);
  const [status, setStatus] = useState<AuthStatus>(() =>
    readToken() === null ? 'anonymous' : 'loading',
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [restoreError, setRestoreError] = useState<unknown>(null);
  /** Bumped by Retry to run the session restore again for the same stored token. */
  const [restoreAttempt, setRestoreAttempt] = useState(0);

  /** Token we already resolved to a user, so login() does not re-fetch /me. */
  const resolvedToken = useRef<string | null>(null);
  /** Whether the one automatic retry for a 429/503 restore has been spent. */
  const autoRetried = useRef(false);

  const clearSession = useCallback((nextNotice: string | null): void => {
    writeToken(null);
    resolvedToken.current = null;
    setToken(null);
    setUser(null);
    setRestoreError(null);
    setStatus('anonymous');
    setNotice(nextNotice);
  }, []);

  // Any authenticated 401 means the token is no longer accepted: drop the session and let the
  // guards send the visitor to /login.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearSession(SESSION_EXPIRED_NOTICE);
    });
    return () => {
      setUnauthorizedHandler(null);
    };
  }, [clearSession]);

  // On a cold start with a stored token, confirm who we are. Only a 401 ends the session; any other
  // failure keeps the token and offers Retry (429 and 503 are retried once on their own first).
  useEffect(() => {
    if (token === null || resolvedToken.current === token) {
      return;
    }
    const controller = new AbortController();
    let cancelled = false;
    let autoRetry: ReturnType<typeof setTimeout> | null = null;
    setStatus('loading');

    api
      .me(controller.signal)
      .then((me) => {
        if (cancelled) {
          return;
        }
        // Marked only after success: React StrictMode runs effects twice, and marking earlier
        // would skip the second call and leave the app stuck in the loading state.
        resolvedToken.current = token;
        setUser(me);
        setRestoreError(null);
        setStatus('authenticated');
      })
      .catch((cause: unknown) => {
        if (cancelled) {
          return;
        }
        if (cause instanceof ApiError && cause.status === 401) {
          // The 401 handler already cleared the session and set the notice.
          return;
        }
        // Keep the token: the server could not answer, which says nothing about the session.
        setUser(null);
        setRestoreError(cause);
        setStatus('restoreFailed');
        if (cause instanceof ApiError && (cause.isRateLimited || cause.isUnavailable) && !autoRetried.current) {
          autoRetried.current = true;
          autoRetry = setTimeout(() => {
            setRestoreAttempt((value) => value + 1);
          }, autoRetryDelayMs(cause));
        }
      });

    return () => {
      cancelled = true;
      controller.abort();
      if (autoRetry !== null) {
        clearTimeout(autoRetry);
      }
    };
  }, [token, restoreAttempt, clearSession]);

  const retryRestore = useCallback((): void => {
    // A manual retry gets its own automatic retry budget again.
    autoRetried.current = false;
    setRestoreAttempt((value) => value + 1);
  }, []);

  const startSession = useCallback((response: AuthResponse): void => {
    writeToken(response.accessToken);
    resolvedToken.current = response.accessToken;
    setToken(response.accessToken);
    setUser(response.user);
    setStatus('authenticated');
    setNotice(null);
  }, []);

  const login = useCallback(
    async (payload: LoginRequest): Promise<void> => {
      startSession(await api.login(payload));
    },
    [startSession],
  );

  const register = useCallback(
    async (payload: RegisterRequest): Promise<void> => {
      startSession(await api.register(payload));
    },
    [startSession],
  );

  const logout = useCallback((): void => {
    clearSession(null);
  }, [clearSession]);

  const clearNotice = useCallback((): void => {
    setNotice(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      role: user?.role ?? null,
      isAdmin: user?.role === 'ADMIN',
      notice,
      restoreError,
      retryRestore,
      login,
      register,
      logout,
      clearNotice,
    }),
    [status, user, notice, restoreError, retryRestore, login, register, logout, clearNotice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
