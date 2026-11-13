import type { OrderEvent } from '../api/types';

/**
 * The "behind the scenes" view of one order: which service emitted each event or command, on which
 * Kafka topic, who consumes that topic, what triggered it, and how long the outbox relay took.
 *
 * Everything here is the saga's documented topology (README "The order Saga"), not something read
 * from the broker. The "triggered by" text is the saga rule, labelled as such in the UI, and is
 * never presented as an observed row.
 */

export type ServiceName =
  | 'api-gateway'
  | 'order-service'
  | 'inventory-service'
  | 'payment-service'
  | 'notification-service'
  | 'kafka';

/** Display order of the legend and diagram. */
export const SERVICE_ORDER: readonly ServiceName[] = [
  'api-gateway',
  'order-service',
  'inventory-service',
  'payment-service',
  'notification-service',
  'kafka',
];

export const SERVICE_LABELS: Readonly<Record<ServiceName, string>> = {
  'api-gateway': 'api-gateway',
  'order-service': 'order-service',
  'inventory-service': 'inventory-service',
  'payment-service': 'payment-service',
  'notification-service': 'notification-service',
  kafka: 'Kafka',
};

/** Consumers of each topic, from the README topic map. */
const TOPIC_CONSUMERS: Readonly<Record<string, readonly ServiceName[]>> = {
  'order.events': ['inventory-service', 'notification-service'],
  'inventory.events': ['order-service'],
  'inventory.commands': ['inventory-service'],
  'payment.commands': ['payment-service'],
  'payment.events': ['order-service'],
};

export interface EventInfo {
  /** One-line plain-English meaning. */
  meaning: string;
  /** Saga rule that makes this event happen. Labelled "triggered by" in the UI. */
  triggeredBy: string;
}

const EVENT_INFO: Readonly<Record<string, EventInfo>> = {
  OrderCreated: {
    meaning: 'Announces that the order exists.',
    triggeredBy: 'your POST /api/orders, routed through api-gateway',
  },
  ProcessPayment: {
    meaning: 'Asks payment-service to charge the order.',
    triggeredBy: 'inventory-service replied StockReserved on inventory.events',
  },
  OrderConfirmed: {
    meaning: 'Tells everyone the order is confirmed.',
    triggeredBy: 'payment-service replied PaymentCompleted on payment.events',
  },
  CommitStock: {
    meaning: 'Asks inventory-service to turn the held stock into a sale.',
    triggeredBy: 'payment-service replied PaymentCompleted on payment.events',
  },
  ReleaseStock: {
    meaning: 'Asks inventory-service to give the held stock back.',
    triggeredBy:
      'payment-service replied PaymentFailed on payment.events, or you cancelled a confirmed order',
  },
  RefundPayment: {
    meaning: 'Asks payment-service to refund the charge.',
    triggeredBy: 'you cancelled a CONFIRMED order (or a refund is being retried)',
  },
  OrderCancelled: {
    meaning: 'Announces that the order was cancelled.',
    triggeredBy:
      'payment-service replied PaymentFailed or PaymentRefunded, or the cancellation was completed',
  },
  OrderRejected: {
    meaning: 'Announces that the order was rejected for lack of stock.',
    triggeredBy: 'inventory-service replied StockRejected on inventory.events',
  },
};

/** Used for any event type this UI does not know yet, so it still renders safely. */
const UNKNOWN_EVENT: EventInfo = {
  meaning: 'An event this screen does not describe yet.',
  triggeredBy: 'the order saga (see the README for the rule)',
};

export function eventInfo(eventType: string): EventInfo {
  return Object.hasOwn(EVENT_INFO, eventType) ? EVENT_INFO[eventType] : UNKNOWN_EVENT;
}

/** Consumers of a topic; an unknown topic has none that we can name. */
export function topicConsumers(topic: string): readonly ServiceName[] {
  return Object.hasOwn(TOPIC_CONSUMERS, topic) ? TOPIC_CONSUMERS[topic] : [];
}

/**
 * The services an order touched: the gateway that accepted the customer's request, the emitter, the
 * Kafka broker, and the consumers of each topic used. An order with no recorded events touched
 * nothing, so the set is empty and the diagram stays grey.
 */
export function servicesTouched(events: readonly OrderEvent[]): Set<ServiceName> {
  const touched = new Set<ServiceName>();
  if (events.length === 0) {
    return touched;
  }
  // Every order was created through api-gateway, so any recorded event implies it.
  touched.add('api-gateway');
  touched.add('order-service');
  touched.add('kafka');
  for (const event of events) {
    for (const consumer of topicConsumers(event.topic)) {
      touched.add(consumer);
    }
  }
  return touched;
}

/**
 * Milliseconds between the write and the publish, or `null` while the row is still waiting or the
 * timestamps cannot be read.
 */
export function relayDelayMs(event: Pick<OrderEvent, 'createdAt' | 'publishedAt'>): number | null {
  if (event.publishedAt === undefined) {
    return null;
  }
  const created = Date.parse(event.createdAt);
  const published = Date.parse(event.publishedAt);
  if (Number.isNaN(created) || Number.isNaN(published)) {
    return null;
  }
  return published - created;
}

/** First non-empty trace id among the rows, or `null` when none carries one. */
export function firstTraceId(events: readonly OrderEvent[]): string | null {
  for (const event of events) {
    if (event.traceId !== undefined && event.traceId.length > 0) {
      return event.traceId;
    }
  }
  return null;
}

/** Default Jaeger UI base, overridable with `VITE_JAEGER_URL`. */
export const DEFAULT_JAEGER_URL = 'http://localhost:16686';

/**
 * Link to one trace in the Jaeger UI. A trailing slash on the base is tolerated.
 *
 * @param baseUrl Jaeger UI base URL
 * @param traceId 32-hex trace id
 */
export function jaegerTraceUrl(baseUrl: string, traceId: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/trace/${encodeURIComponent(traceId)}`;
}
