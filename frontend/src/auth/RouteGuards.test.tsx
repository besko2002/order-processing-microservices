import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { AuthContextValue } from './authContext';
import { AuthContext } from './authContext';
import { AdminRoute, ProtectedRoute } from './RouteGuards';
import type { UserResponse } from '../api/types';

const USER: UserResponse = { id: 'u1', name: 'Ann', email: 'ann@x.io', role: 'USER' };
const ADMIN: UserResponse = { id: 'a1', name: 'Root', email: 'admin@orders.local', role: 'ADMIN' };

function value(user: UserResponse | null): AuthContextValue {
  return {
    status: user === null ? 'anonymous' : 'authenticated',
    user,
    role: user?.role ?? null,
    isAdmin: user?.role === 'ADMIN',
    notice: null,
    restoreError: null,
    retryRestore: () => undefined,
    login: () => Promise.resolve(),
    register: () => Promise.resolve(),
    logout: () => undefined,
    clearNotice: () => undefined,
  };
}

function renderAt(path: string, user: UserResponse | null): void {
  render(
    <AuthContext.Provider value={value(user)}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<p>login page</p>} />
          <Route path="/403" element={<p>forbidden page</p>} />
          <Route element={<ProtectedRoute />}>
            <Route element={<AdminRoute />}>
              <Route path="/admin/orders" element={<p>admin orders</p>} />
            </Route>
            <Route path="/orders" element={<p>my orders</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('route guards', () => {
  it('sends an anonymous visitor to /login from a protected page', () => {
    renderAt('/orders', null);
    expect(screen.getByText('login page')).toBeInTheDocument();
  });

  it('lets a signed-in user reach a protected page', () => {
    renderAt('/orders', USER);
    expect(screen.getByText('my orders')).toBeInTheDocument();
  });

  it('blocks a normal user from an admin route', () => {
    renderAt('/admin/orders', USER);
    expect(screen.getByText('forbidden page')).toBeInTheDocument();
    expect(screen.queryByText('admin orders')).not.toBeInTheDocument();
  });

  it('lets an admin reach an admin route', () => {
    renderAt('/admin/orders', ADMIN);
    expect(screen.getByText('admin orders')).toBeInTheDocument();
  });

  it('sends an anonymous visitor to /login from an admin route', () => {
    renderAt('/admin/orders', null);
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
