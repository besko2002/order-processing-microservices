import type { OrderEvent } from '../api/types';
import { formatDateTime } from '../lib/format';
import {
  DEFAULT_JAEGER_URL,
  SERVICE_LABELS,
  SERVICE_ORDER,
  eventInfo,
  firstTraceId,
  jaegerTraceUrl,
  relayDelayMs,
  servicesTouched,
  topicConsumers,
} from '../lib/journey';

/** Jaeger UI base, overridable at build time with `VITE_JAEGER_URL`. */
const JAEGER_URL: string = import.meta.env.VITE_JAEGER_URL ?? DEFAULT_JAEGER_URL;

/**
 * "Behind the scenes": the services and Kafka topics this order travelled through, one row per
 * event or command order-service wrote to its outbox. Shows who emitted it, what it is, where it
 * went, what triggered it, and the relay timing, plus a link to the full trace in Jaeger.
 */
export function BehindTheScenes({ events }: { events: OrderEvent[] }): JSX.Element {
  const traceId = firstTraceId(events);
  const touched = servicesTouched(events);

  return (
    <div className="journey">
      <ServiceDiagram touched={touched} />

      {events.length === 0 ? (
        <p className="muted">No events recorded (events are kept for 7 days).</p>
      ) : (
        <>
          <p className="muted journey-note">
            Each step below was written to order-service&apos;s outbox in the same database
            transaction as the order&apos;s state change, then relayed to Kafka. The
            &quot;triggered by&quot; line is the saga rule, not a message observed on the broker.
          </p>
          <ol className="journey-steps" aria-label="Events and commands for this order">
            {events.map((event, index) => (
              <EventStep key={`${event.eventType}-${index}`} event={event} index={index} />
            ))}
          </ol>
        </>
      )}

      <div className="journey-trace">
        {traceId !== null ? (
          <a
            className="button-link"
            href={jaegerTraceUrl(JAEGER_URL, traceId)}
            target="_blank"
            rel="noreferrer"
          >
            Open the full distributed trace in Jaeger
          </a>
        ) : null}
        <p className="muted journey-note">
          The Jaeger link needs the observability stack (
          <code>--profile observability</code>) with <code>OTLP_TRACING_EXPORT_ENABLED=true</code>.
          Jaeger keeps only recent traces, so an older order may no longer be there.
        </p>
      </div>
    </div>
  );
}

function ServiceDiagram({ touched }: { touched: Set<string> }): JSX.Element {
  return (
    <ul className="journey-services" aria-label="Services involved">
      {SERVICE_ORDER.map((service) => {
        const active = touched.has(service);
        return (
          <li
            key={service}
            className={active ? 'journey-service journey-service-active' : 'journey-service'}
            data-service={service}
            data-active={active ? 'true' : 'false'}
          >
            {SERVICE_LABELS[service]}
            <span className="sr-only">{active ? ' (involved in this order)' : ''}</span>
          </li>
        );
      })}
    </ul>
  );
}

function EventStep({ event, index }: { event: OrderEvent; index: number }): JSX.Element {
  const info = eventInfo(event.eventType);
  const consumers = topicConsumers(event.topic);
  const delay = relayDelayMs(event);

  return (
    <li className="journey-step" data-event={event.eventType}>
      <div className="journey-step-head">
        <span className="journey-index">{index + 1}</span>
        <div>
          <div className="journey-name">
            <code>{event.eventType}</code>
          </div>
          <div className="journey-meaning">{info.meaning}</div>
        </div>
      </div>

      <dl className="journey-facts">
        <div>
          <dt>Emitted by</dt>
          <dd>order-service</dd>
        </div>
        <div>
          <dt>Kafka topic</dt>
          <dd>
            <code>{event.topic}</code>
            {consumers.length > 0 ? (
              <span className="muted"> → {consumers.map((c) => SERVICE_LABELS[c]).join(', ')}</span>
            ) : null}
          </dd>
        </div>
        <div>
          <dt>Triggered by</dt>
          <dd>
            {info.triggeredBy}
            <span className="muted"> (saga rule)</span>
          </dd>
        </div>
        <div>
          <dt>Written to outbox</dt>
          <dd>{formatDateTime(event.createdAt)}</dd>
        </div>
        <div>
          <dt>Sent to Kafka</dt>
          <dd data-testid="published-at">
            {event.publishedAt !== undefined ? (
              formatDateTime(event.publishedAt)
            ) : (
              <span className="journey-pending">
                waiting in the outbox (attempt {event.attempts})
              </span>
            )}
          </dd>
        </div>
        <div>
          <dt>Relay delay</dt>
          <dd data-testid="relay-delay">{delay !== null ? `${delay} ms` : '—'}</dd>
        </div>
      </dl>
    </li>
  );
}
