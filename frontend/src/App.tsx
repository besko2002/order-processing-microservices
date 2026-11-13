import { Route, Routes } from 'react-router';
import { AdminRoute, ProtectedRoute, PublicOnlyRoute } from './auth/RouteGuards';
import { AdminOrdersPage } from './pages/AdminOrdersPage';
import { AdminProductsPage } from './pages/AdminProductsPage';
import { AdminReservationsPage } from './pages/AdminReservationsPage';
import { AppShell } from './pages/AppShell';
import { AuthPage } from './pages/AuthPage';
import { CartPage } from './pages/CartPage';
import { ForbiddenPage, NotFoundPage } from './pages/ForbiddenPage';
import { MyOrdersPage } from './pages/MyOrdersPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { ProductsPage } from './pages/ProductsPage';

/**
 * Public routes (login, register) redirect a signed-in user to the catalogue. Everything else sits
 * behind ProtectedRoute, and the admin routes additionally behind AdminRoute.
 */
export function App(): JSX.Element {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />
      </Route>

      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<AppShell />}>
          <Route index element={<ProductsPage />} />
          <Route path="cart" element={<CartPage />} />
          <Route path="orders" element={<MyOrdersPage />} />
          <Route path="orders/:id" element={<OrderDetailPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          <Route path="403" element={<ForbiddenPage />} />

          <Route element={<AdminRoute />}>
            <Route path="admin/orders" element={<AdminOrdersPage />} />
            <Route path="admin/products" element={<AdminProductsPage />} />
            <Route path="admin/reservations" element={<AdminReservationsPage />} />
          </Route>

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
