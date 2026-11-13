import type { OrderStatus, PaymentStatus } from '../api/types';
import { sagaTimeline } from '../lib/saga';

/**
 * The four Saga steps with their state, plus the failure or waiting banner when there is one.
 * `payment` is `undefined` while the payment request is in flight, `null` when no payment exists.
 */
export function SagaTimeline({
  status,
  payment,
}: {
  status: OrderStatus;
  payment: PaymentStatus | null | undefined;
}): JSX.Element {
  const timeline = sagaTimeline(status, payment);
  return (
    <div className="saga">
      <ol className="saga-steps" aria-label="Saga progress">
        {timeline.steps.map((step) => (
          <li key={step.key} className={`saga-step saga-${step.state}`} data-step={step.key}>
            <span className="saga-dot" aria-hidden="true" />
            <div>
              <div className="saga-label">{step.label}</div>
              <div className="saga-detail">{step.detail}</div>
            </div>
          </li>
        ))}
      </ol>
      {timeline.terminal !== null ? (
        <p className={`saga-terminal saga-terminal-${timeline.terminal.tone}`}>
          {timeline.terminal.label}
        </p>
      ) : null}
    </div>
  );
}
