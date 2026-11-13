import { createContext } from 'react';
import type { LoginRequest, RegisterRequest, Role, UserResponse } from '../api/types';

/**
 * `restoreFailed`: a stored token exists but the server could not confirm it (network error, 429,
 * 503 or another 5xx). The token is kept; the visitor is neither signed in nor signed out.
 */
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous' | 'restoreFailed';

export interface AuthContextValue {
  status: AuthStatus;
  user: UserResponse | null;
  role: Role | null;
  isAdmin: boolean;
  /** One-off notice shown on the login page, e.g. after a 401. */
  notice: string | null;
  /** The error from the last failed restore; meaningful only while `status` is `restoreFailed`. */
  restoreError: unknown;
  /** Asks the server again who the stored token belongs to. */
  retryRestore: () => void;
  login: (payload: LoginRequest) => Promise<void>;
  register: (payload: RegisterRequest) => Promise<void>;
  logout: () => void;
  clearNotice: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
