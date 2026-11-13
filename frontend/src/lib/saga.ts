import type { OrderStatus, PaymentStatus } from '../api/types';

/** Statuses after which nothing else will happen to the order. */
const FINAL_STATUSES: ReadonlySet<OrderStatus> = new Set<OrderStatus>([
  'CONFIRMED',
  'CANCELLED',
  'REJECTED',
]);

/** Statuses the backend accepts a cancel in (mirrors the cancel rule in order-service). */
const CANCELLABLE_STATUSES: ReadonlySet<OrderStatus> = new Set<OrderStatus>([
  'PENDING',
  'STOCK_RESERVED',
  'PAYMENT_PENDING',
  'CONFIRMED',
]);

export function isFinalStatus(status: OrderStatus): boolean {
  return FINAL_STATUSES.has(status);
}

/** Whether the Cancel button may be shown for an order in this status. */
export function canCancel(status: OrderStatus): boolean {
  return CANCELLABLE_STATUSES.has(status);
}

export type StepState = 'done' | 'current' | 'pending' | 'failed';

export interface SagaStep {
  key: 'placed' | 'stock' | 'payment' | 'confirmed';
  label: string;
  state: StepState;
  detail: string;
}

export type SagaTimeline = {
  steps: SagaStep[];
  /** Set when the saga ended on a failure branch, e.g. "Rejected" or "Cancelled". */
  terminal: { label: string; tone: 'failed' | 'pending' } | null;
};

const STEP_LABELS = {
  placed: 'Order placed',
  stock: 'Stock reserved',
  payment: 'Payment',
  confirmed: 'Confirmed',
} as const;

/**
 * What the payment side of the saga looks like, as the UI knows it:
 * - `undefined`: the payment request has not answered yet. Draw neutral, never a failure.
 * - `null`: the payment request answered 404, so no payment was ever attempted.
 * - otherwise: the payment-service status.
 */
export type PaymentInput = PaymentStatus | null | undefined;

/**
 * Maps an order status, plus the payment status, onto the four-step Saga timeline. Each status is
 * listed explicitly so a new backend status shows up in review instead of being silently drawn as
 * "in progress". For CANCELLED the payment decides what really happened: a refunded charge, a
 * declined charge, an outstanding refund, or no payment at all are four different stories.
 */
export function sagaTimeline(status: OrderStatus, payment: PaymentInput = undefined): SagaTimeline {
  const step = (
    key: SagaStep['key'],
    state: StepState,
    detail: string,
  ): SagaStep => ({ key, label: STEP_LABELS[key], state, detail });

  switch (status) {
    case 'PENDING':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'current', 'Waiting for inventory'),
          step('payment', 'pending', 'Not started'),
          step('confirmed', 'pending', 'Not yet'),
        ],
        terminal: null,
      };
    case 'STOCK_RESERVED':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'done', 'Stock held for this order'),
          step('payment', 'current', 'Preparing the charge'),
          step('confirmed', 'pending', 'Not yet'),
        ],
        terminal: null,
      };
    case 'PAYMENT_PENDING':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'done', 'Stock held for this order'),
          step('payment', 'current', 'Waiting for payment-service'),
          step('confirmed', 'pending', 'Not yet'),
        ],
        terminal: null,
      };
    case 'CONFIRMED':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'done', 'Stock reserved and committed'),
          step('payment', 'done', 'Payment authorised'),
          step('confirmed', 'done', 'Order confirmed'),
        ],
        terminal: null,
      };
    case 'CANCELLATION_PENDING':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'done', 'Stock was reserved'),
          step('payment', 'done', 'Authorised — refund requested'),
          step('confirmed', 'pending', 'Cancellation requested'),
        ],
        terminal: { label: 'Waiting for the refund', tone: 'pending' },
      };
    case 'REJECTED':
      return {
        steps: [
          step('placed', 'done', 'Accepted by order-service'),
          step('stock', 'failed', 'Not enough stock'),
          step('payment', 'pending', 'Skipped'),
          step('confirmed', 'pending', 'Not reached'),
        ],
        terminal: { label: 'Rejected', tone: 'failed' },
      };
    case 'CANCELLED':
      return cancelledTimeline(payment);
  }
}

/**
 * A CANCELLED order: the stock and the payment each tell their own story, so both are derived from
 * what payment-service recorded, not from the status alone.
 */
function cancelledTimeline(payment: PaymentInput): SagaTimeline {
  const placed: SagaStep = {
    key: 'placed',
    label: STEP_LABELS.placed,
    state: 'done',
    detail: 'Accepted by order-service',
  };
  const confirmed: SagaStep = {
    key: 'confirmed',
    label: STEP_LABELS.confirmed,
    state: 'pending',
    detail: 'Not reached',
  };

  if (payment === undefined) {
    // The payment request has not answered: say so, never draw a failure.
    return {
      steps: [
        placed,
        { key: 'stock', label: STEP_LABELS.stock, state: 'pending', detail: 'Checking payment…' },
        { key: 'payment', label: STEP_LABELS.payment, state: 'pending', detail: 'Checking payment…' },
        confirmed,
      ],
      terminal: null,
    };
  }

  if (payment === null) {
    // No payment was ever attempted (the order was cancelled or timed out before payment).
    return {
      steps: [
        placed,
        { key: 'stock', label: STEP_LABELS.stock, state: 'pending', detail: 'Released if it was held' },
        { key: 'payment', label: STEP_LABELS.payment, state: 'pending', detail: 'Not attempted' },
        confirmed,
      ],
      terminal: { label: 'Cancelled before payment', tone: 'failed' },
    };
  }

  switch (payment) {
    case 'REFUNDED':
      return {
        steps: [
          placed,
          { key: 'stock', label: STEP_LABELS.stock, state: 'done', detail: 'Reserved, then released' },
          { key: 'payment', label: STEP_LABELS.payment, state: 'done', detail: 'Authorised, then refunded' },
          confirmed,
        ],
        terminal: { label: 'Cancelled — payment refunded', tone: 'failed' },
      };
    case 'FAILED':
      return {
        steps: [
          placed,
          { key: 'stock', label: STEP_LABELS.stock, state: 'done', detail: 'Reserved, then released' },
          { key: 'payment', label: STEP_LABELS.payment, state: 'failed', detail: 'Declined by the payment provider' },
          confirmed,
        ],
        terminal: { label: 'Cancelled — payment declined', tone: 'failed' },
      };
    case 'AUTHORIZED':
      return {
        steps: [
          placed,
          { key: 'stock', label: STEP_LABELS.stock, state: 'done', detail: 'Reserved, then released' },
          { key: 'payment', label: STEP_LABELS.payment, state: 'pending', detail: 'Authorised — refund outstanding' },
          confirmed,
        ],
        terminal: { label: 'Cancelled — refund outstanding', tone: 'pending' },
      };
  }
}
