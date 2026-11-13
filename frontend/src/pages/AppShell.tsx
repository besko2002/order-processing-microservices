import { NavLink, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../auth/useAuth';

/** Signed-in layout: a top bar with the menu, then the routed page. */
export function AppShell(): JSX.Element {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = (): void => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <span className="brand">Order Processing</span>
        <nav className="menu" aria-label="Main">
          <NavLink to="/" end>
            Products
          </NavLink>
          <NavLink to="/cart">Cart</NavLink>
          <NavLink to="/orders">My orders</NavLink>
          <NavLink to="/notifications">Notifications</NavLink>
          {isAdmin ? (
            <>
              <NavLink to="/admin/orders">All orders</NavLink>
              <NavLink to="/admin/products">Manage products</NavLink>
              <NavLink to="/admin/reservations">Reservations</NavLink>
            </>
          ) : null}
        </nav>
        <div className="account">
          <span className="who" data-testid="current-user">
            {user?.name ?? user?.email}
            {isAdmin ? <span className="role-tag">ADMIN</span> : null}
          </span>
          <button type="button" className="secondary" onClick={onLogout}>
            Log out
          </button>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}
