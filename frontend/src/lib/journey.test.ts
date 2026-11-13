import { describe, expect, it } from 'vitest';
import type { OrderEvent } from '../api/types';
import {
  DEFAULT_JAEGER_URL,
  eventInfo,
  firstTraceId,
  jaegerTraceUrl,
  relayDelayMs,
  servicesTouched,
  topicConsumers,
} from './journey';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

function row(overrides: Partial<OrderEvent> = {}): OrderEvent {
  return {
    eventType: 'OrderCreated',
    topic: 'order.events',
    createdAt: '2026-01-01T19:08:34.864Z',
    publishedAt: '2026-01-01T19:08:35.439Z',
    attempts: 0,
    ...overrides,
  };
}

describe('eventInfo: every event type the saga emits', () => {
  const cases: Array<[string, RegExp, RegExp]> = [
    ['OrderCreated', /Announces that the order exists/, /POST \/api\/orders/],
    ['ProcessPayment', /asks payment-service to charge/i, /StockReserved on inventory\.events/],
    ['OrderConfirmed', /confirmed/i, /PaymentCompleted on payment\.events/],
    ['CommitStock', /turn the held stock into a sale/, /PaymentCompleted on payment\.events/],
    ['ReleaseStock', /give the held stock back/, /PaymentFailed on payment\.events/],
    ['RefundPayment', /refund the charge/, /cancelled a CONFIRMED order/],
    ['OrderCancelled', /was cancelled/, /PaymentRefunded/],
    ['OrderRejected', /rejected for lack of stock/, /StockRejected on inventory\.events/],
  ];

  it.each(cases)('describes %s with a meaning and a saga trigger', (eventType, meaning, trigger) => {
    const info = eventInfo(eventType);
    expect(info.meaning).toMatch(meaning);
    expect(info.triggeredBy).toMatch(trigger);
  });

  it('renders an unknown event type safely with a generic description', () => {
    const info = eventInfo('SomeFutureEvent');
    expect(info.meaning).toBe('An event this screen does not describe yet.');
    expect(info.triggeredBy).toContain('saga');
  });

  it('does not treat prototype names as known events', () => {
    expect(eventInfo('constructor').meaning).toBe('An event this screen does not describe yet.');
    expect(eventInfo('__proto__').meaning).toBe('An event this screen does not describe yet.');
  });
});

describe('topicConsumers', () => {
  it('lists the consumers from the README topic map', () => {
    expect(topicConsumers('order.events')).toEqual(['inventory-service', 'notification-service']);
    expect(topicConsumers('inventory.events')).toEqual(['order-service']);
    expect(topicConsumers('inventory.commands')).toEqual(['inventory-service']);
    expect(topicConsumers('payment.commands')).toEqual(['payment-service']);
    expect(topicConsumers('payment.events')).toEqual(['order-service']);
  });

  it('returns no consumers for a topic it does not know', () => {
    expect(topicConsumers('mystery.topic')).toEqual([]);
  });
});

describe('servicesTouched', () => {
  it('touches nothing when the order has no recorded events', () => {
    expect(servicesTouched([])).toEqual(new Set());
  });

  it('marks api-gateway, the emitter and the broker once any event exists', () => {
    const touched = servicesTouched([row()]);
    expect(touched.has('api-gateway')).toBe(true);
    expect(touched.has('order-service')).toBe(true);
    expect(touched.has('kafka')).toBe(true);
  });

  it('adds the consumers of every topic the order used', () => {
    const touched = servicesTouched([
      row({ topic: 'order.events' }),
      row({ eventType: 'ProcessPayment', topic: 'payment.commands' }),
    ]);
    expect(touched.has('inventory-service')).toBe(true);
    expect(touched.has('notification-service')).toBe(true);
    expect(touched.has('payment-service')).toBe(true);
  });
});

describe('relayDelayMs', () => {
  it('is the publish time minus the write time, in milliseconds', () => {
    expect(relayDelayMs(row())).toBe(575);
  });

  it('is null while the row still waits in the outbox', () => {
    expect(relayDelayMs(row({ publishedAt: undefined, attempts: 3 }))).toBeNull();
  });

  it('is null when a timestamp cannot be read', () => {
    expect(relayDelayMs(row({ createdAt: 'not-a-date' }))).toBeNull();
  });
});

describe('firstTraceId and the Jaeger link', () => {
  it('takes the first non-empty trace id', () => {
    expect(firstTraceId([row({ traceId: undefined }), row({ traceId: TRACE })])).toBe(TRACE);
  });

  it('is null when no row carries a trace id', () => {
    expect(firstTraceId([row()])).toBeNull();
    expect(firstTraceId([])).toBeNull();
  });

  it('builds the Jaeger trace URL from the base and the trace id', () => {
    expect(jaegerTraceUrl(DEFAULT_JAEGER_URL, TRACE)).toBe(`http://localhost:16686/trace/${TRACE}`);
  });

  it('tolerates a trailing slash on the configured base', () => {
    expect(jaegerTraceUrl('https://jaeger.example.test/', TRACE)).toBe(
      `https://jaeger.example.test/trace/${TRACE}`,
    );
  });
});
