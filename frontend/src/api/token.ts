// The JWT lives in localStorage because the backend is bearer-only (no cookie session),
// so there is no CSRF surface.
const TOKEN_KEY = 'orders.accessToken';

/** Reads the JWT from localStorage, tolerating privacy modes that throw. */
export function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Stores the JWT, or removes it when `token` is null. */
export function writeToken(token: string | null): void {
  try {
    if (token === null) {
      window.localStorage.removeItem(TOKEN_KEY);
    } else {
      window.localStorage.setItem(TOKEN_KEY, token);
    }
  } catch {
    // Storage unavailable: the session simply does not survive a reload.
  }
}

export { TOKEN_KEY };
