import { describe, expect, it } from 'vitest';
import type { OrderStatus, PaymentStatus } from '../api/types';
import { canCancel, isFinalStatus, sagaTimeline } from './saga';
import { shouldPoll } from './poll';

const ALL_STATUSES: OrderStatus[] = [
  'PENDING',
  'STOCK_RESERVED',
  'PAYMENT_PENDING',
  'CONFIRMED',
  'CANCELLATION_PENDING',
  'CANCELLED',
  'REJECTED',
];

describe('sagaTimeline', () => {
  it('maps every backend status to four steps', () => {
    for (const status of ALL_STATUSES) {
      const { steps } = sagaTimeline(status);
      expect(steps.map((step) => step.key)).toEqual(['placed', 'stock', 'payment', 'confirmed']);
    }
  });

  it('shows an in-progress order with the current step on stock while waiting', () => {
    const { steps, terminal } = sagaTimeline('PENDING');
    expect(steps[0]?.state).toBe('done');
    expect(steps[1]?.state).toBe('current');
    expect(terminal).toBeNull();
  });

  it('marks stock reserved and payment in progress for STOCK_RESERVED and PAYMENT_PENDING', () => {
    const reserved = sagaTimeline('STOCK_RESERVED').steps;
    expect(reserved[1]?.state).toBe('done');
    expect(reserved[2]?.state).toBe('current');

    const paying = sagaTimeline('PAYMENT_PENDING').steps;
    expect(paying[1]?.state).toBe('done');
    expect(paying[2]?.state).toBe('current');
  });

  it('completes every step for CONFIRMED with no terminal banner', () => {
    const timeline = sagaTimeline('CONFIRMED');
    expect(timeline.steps.every((step) => step.state === 'done')).toBe(true);
    expect(timeline.terminal).toBeNull();
  });

  it('marks stock as failed for REJECTED (no stock)', () => {
    const timeline = sagaTimeline('REJECTED');
    expect(timeline.steps[1]?.state).toBe('failed');
    expect(timeline.steps[2]?.state).toBe('pending');
    expect(timeline.terminal).toEqual({ label: 'Rejected', tone: 'failed' });
  });

  it('shows the refund wait for CANCELLATION_PENDING without calling it cancelled', () => {
    const timeline = sagaTimeline('CANCELLATION_PENDING', 'AUTHORIZED');
    expect(timeline.terminal).toEqual({ label: 'Waiting for the refund', tone: 'pending' });
    expect(timeline.steps[2]?.detail).toBe('Authorised — refund requested');
    expect(timeline.steps[3]?.state).toBe('pending');
  });
});

describe('sagaTimeline: CANCELLED is told by the payment', () => {
  const payment = (status: PaymentStatus | null | undefined) => sagaTimeline('CANCELLED', status);

  it('a confirmed-then-cancelled order shows the charge authorised then refunded, not failed', () => {
    const timeline = payment('REFUNDED');
    expect(timeline.steps[1]).toMatchObject({ state: 'done', detail: 'Reserved, then released' });
    expect(timeline.steps[2]).toMatchObject({ state: 'done', detail: 'Authorised, then refunded' });
    expect(timeline.steps[2]?.state).not.toBe('failed');
    expect(timeline.terminal).toEqual({ label: 'Cancelled — payment refunded', tone: 'failed' });
  });

  it('a declined payment is shown as a failed payment with the provider reason', () => {
    const timeline = payment('FAILED');
    expect(timeline.steps[1]).toMatchObject({ state: 'done', detail: 'Reserved, then released' });
    expect(timeline.steps[2]).toMatchObject({
      state: 'failed',
      detail: 'Declined by the payment provider',
    });
    expect(timeline.terminal).toEqual({ label: 'Cancelled — payment declined', tone: 'failed' });
  });

  it('an authorised charge with the refund still outstanding is not a failure', () => {
    const timeline = payment('AUTHORIZED');
    expect(timeline.steps[2]).toMatchObject({
      state: 'pending',
      detail: 'Authorised — refund outstanding',
    });
    expect(timeline.steps[2]?.state).not.toBe('failed');
    expect(timeline.terminal).toEqual({ label: 'Cancelled — refund outstanding', tone: 'pending' });
  });

  it('an order cancelled before any payment does not claim stock was reserved or payment failed', () => {
    const timeline = payment(null);
    expect(timeline.steps[1]).toMatchObject({ state: 'pending', detail: 'Released if it was held' });
    expect(timeline.steps[1]?.detail).not.toMatch(/Reserved/);
    expect(timeline.steps[2]).toMatchObject({ state: 'pending', detail: 'Not attempted' });
    expect(timeline.steps.some((step) => step.state === 'failed')).toBe(false);
    expect(timeline.terminal).toEqual({ label: 'Cancelled before payment', tone: 'failed' });
  });

  it('while the payment request is in flight it shows a neutral state, never a failure', () => {
    const timeline = payment(undefined);
    expect(timeline.steps.some((step) => step.state === 'failed')).toBe(false);
    expect(timeline.steps[2]?.detail).toBe('Checking payment…');
    expect(timeline.terminal).toBeNull();
  });

  it('no CANCELLED combination ever draws the stock as "Stock was reserved, then released" without a payment', () => {
    expect(payment(null).steps[1]?.detail).not.toBe('Reserved, then released');
  });
});

describe('sagaTimeline: payment defaults to not yet answered', () => {
  it('keeps the existing statuses unchanged when no payment is passed', () => {
    expect(sagaTimeline('PAYMENT_PENDING').steps[2]?.state).toBe('current');
    expect(sagaTimeline('CONFIRMED').steps[2]?.detail).toBe('Payment authorised');
  });
});

describe('cancel button visibility', () => {
  it.each<[OrderStatus, boolean]>([
    ['PENDING', true],
    ['STOCK_RESERVED', true],
    ['PAYMENT_PENDING', true],
    ['CONFIRMED', true],
    ['CANCELLATION_PENDING', false],
    ['CANCELLED', false],
    ['REJECTED', false],
  ])('shows cancel for %s: %s', (status, expected) => {
    expect(canCancel(status)).toBe(expected);
  });
});

describe('polling policy', () => {
  it('keeps polling only while the order is not final', () => {
    expect(isFinalStatus('CONFIRMED')).toBe(true);
    expect(isFinalStatus('CANCELLED')).toBe(true);
    expect(isFinalStatus('REJECTED')).toBe(true);
    expect(isFinalStatus('CANCELLATION_PENDING')).toBe(false);

    expect(shouldPoll('PAYMENT_PENDING')).toBe(true);
    expect(shouldPoll('CONFIRMED')).toBe(false);
    expect(shouldPoll(null)).toBe(false);
  });
});
