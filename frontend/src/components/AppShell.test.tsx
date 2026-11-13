import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { AuthContextValue } from '../auth/authContext';
import { AuthContext } from '../auth/authContext';
import { AppShell } from '../pages/AppShell';
import type { UserResponse } from '../api/types';

function renderShell(user: UserResponse): void {
  const auth: AuthContextValue = {
    status: 'authenticated',
    user,
    role: user.role,
    isAdmin: user.role === 'ADMIN',
    notice: null,
    restoreError: null,
    retryRestore: () => undefined,
    login: () => Promise.resolve(),
    register: () => Promise.resolve(),
    logout: () => undefined,
    clearNotice: () => undefined,
  };
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<AppShell />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('AppShell menu', () => {
  it('hides the admin menu from a normal user', () => {
    renderShell({ id: 'u1', name: 'Ann', email: 'ann@x.io', role: 'USER' });
    expect(screen.queryByRole('link', { name: 'All orders' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Manage products' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Reservations' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'My orders' })).toBeInTheDocument();
  });

  it('shows the admin menu to an admin', () => {
    renderShell({ id: 'a1', name: 'Root', email: 'admin@orders.local', role: 'ADMIN' });
    expect(screen.getByRole('link', { name: 'All orders' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Manage products' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Reservations' })).toBeInTheDocument();
  });
});
