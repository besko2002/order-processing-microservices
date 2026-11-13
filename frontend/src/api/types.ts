/**
 * TypeScript mirrors of the backend DTO records. Field names and nullability follow the Java
 * records exactly. `BigDecimal` arrives as a JSON number, `OffsetDateTime` as an ISO-8601 string.
 */

export type Role = 'USER' | 'ADMIN';

export type OrderStatus =
  | 'PENDING'
  | 'STOCK_RESERVED'
  | 'PAYMENT_PENDING'
  | 'CONFIRMED'
  | 'CANCELLATION_PENDING'
  | 'CANCELLED'
  | 'REJECTED';

export type PaymentStatus = 'AUTHORIZED' | 'FAILED' | 'REFUNDED';

export type ReservationStatus = 'RESERVED' | 'RELEASED' | 'COMMITTED';

/** `PageResponse<T>` from the services: five keys plus `last`. `page` is 0-based. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

// ----------------------------------------------------------------- auth

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  name: string;
  email: string;
  password: string;
}

/** `UserResponse` from auth-service. */
export interface UserResponse {
  id: string;
  name: string;
  email: string;
  role: Role;
}

/** `AuthResponse` from auth-service: returned by register and login. */
export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserResponse;
}

// ----------------------------------------------------------------- catalogue

/** `ProductResponse`: what the public catalogue shows. */
export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string | null;
  price: number;
  availableQuantity: number;
}

/** `ProductAdminResponse`: the admin view with operational fields. */
export interface ProductAdmin extends Product {
  reservedQuantity: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description: string | null;
  price: number;
  availableQuantity: number;
}

export interface UpdateProductRequest {
  name: string;
  description: string | null;
  price: number;
}

// ----------------------------------------------------------------- orders

export interface OrderItemRequest {
  productId: string;
  quantity: number;
}

export interface CreateOrderRequest {
  items: OrderItemRequest[];
}

/** `OrderItemResponse`. */
export interface OrderItem {
  productId: string;
  productName: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

/** `OrderResponse`. `failureReason` is absent while the order is fine. */
export interface Order {
  id: string;
  orderNumber: string;
  userId: string;
  status: OrderStatus;
  totalAmount: number;
  currency: string;
  failureReason?: string;
  createdAt: string;
  updatedAt: string;
  items: OrderItem[];
}

/**
 * `OrderEventResponse`: one event or command order-service emitted for an order. The payload is
 * never sent. `publishedAt` is absent while the row still waits in the outbox; `traceId` is absent
 * when the stored traceparent is missing or malformed.
 */
export interface OrderEvent {
  eventType: string;
  topic: string;
  createdAt: string;
  publishedAt?: string;
  attempts: number;
  traceId?: string;
  correlationId?: string;
}

// ----------------------------------------------------------------- payments

/** `PaymentResponse` from payment-service. */
export interface Payment {
  id: string;
  orderId: string;
  customerId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  failureReason?: string;
  providerRef: string;
  createdAt: string;
  updatedAt: string;
}

// ----------------------------------------------------------------- notifications

/** `NotificationResponse` from notification-service. */
export interface Notification {
  id: string;
  orderId: string;
  type: string;
  channel: string;
  recipient: string;
  subject: string;
  body: string;
  status: string;
  createdAt: string;
}

// ----------------------------------------------------------------- inventory admin

/** `ReservationResponse`. */
export interface Reservation {
  id: string;
  orderId: string;
  productId: string;
  sku: string | null;
  quantity: number;
  status: ReservationStatus;
  createdAt: string;
  updatedAt: string;
}

export type ReconciliationOutcome = string;

/** `ReconciliationResponse`. */
export interface ReconciliationResponse {
  entries: { orderId: string; outcome: ReconciliationOutcome }[];
}
