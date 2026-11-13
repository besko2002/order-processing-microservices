import type { OrderStatus, PaymentStatus } from '../api/types';
import { humanizeEnum } from '../lib/format';

type Tone = 'good' | 'progress' | 'bad' | 'neutral';

const ORDER_TONE: Record<OrderStatus, Tone> = {
  PENDING: 'progress',
  STOCK_RESERVED: 'progress',
  PAYMENT_PENDING: 'progress',
  CANCELLATION_PENDING: 'progress',
  CONFIRMED: 'good',
  CANCELLED: 'bad',
  REJECTED: 'bad',
};

const PAYMENT_TONE: Record<PaymentStatus, Tone> = {
  AUTHORIZED: 'good',
  FAILED: 'bad',
  REFUNDED: 'neutral',
};

export function OrderStatusChip({ status }: { status: OrderStatus }): JSX.Element {
  return (
    <span className={`chip chip-${ORDER_TONE[status]}`} data-testid="order-status">
      {humanizeEnum(status)}
    </span>
  );
}

export function PaymentStatusChip({ status }: { status: PaymentStatus }): JSX.Element {
  return <span className={`chip chip-${PAYMENT_TONE[status]}`}>{humanizeEnum(status)}</span>;
}
