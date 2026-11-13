import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { OrderEvent } from '../api/types';
import { BehindTheScenes } from './BehindTheScenes';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

const created: OrderEvent = {
  eventType: 'OrderCreated',
  topic: 'order.events',
  createdAt: '2026-01-01T19:08:34.864Z',
  publishedAt: '2026-01-01T19:08:35.439Z',
  attempts: 0,
  traceId: TRACE,
};

const processPayment: OrderEvent = {
  eventType: 'ProcessPayment',
  topic: 'payment.commands',
  createdAt: '2026-01-01T19:08:36.370Z',
  attempts: 2,
};

describe('BehindTheScenes', () => {
  it('shows the emitter, topic, consumers, trigger and relay delay for each event', () => {
    render(<BehindTheScenes events={[created]} />);

    const step = screen.getByText('OrderCreated').closest('li');
    expect(step).not.toBeNull();
    const scope = within(step as HTMLElement);
    expect(scope.getByText('order-service')).toBeInTheDocument();
    expect(scope.getByText('order.events')).toBeInTheDocument();
    expect(scope.getByText(/inventory-service, notification-service/)).toBeInTheDocument();
    expect(scope.getByText(/POST \/api\/orders/)).toBeInTheDocument();
    expect(scope.getByTestId('relay-delay')).toHaveTextContent('575 ms');
  });

  it('marks a row still waiting in the outbox with its attempt count and no delay', () => {
    render(<BehindTheScenes events={[processPayment]} />);

    expect(screen.getByText('waiting in the outbox (attempt 2)')).toBeInTheDocument();
    expect(screen.getByTestId('relay-delay')).toHaveTextContent('—');
  });

  it('links to the Jaeger trace built from the first trace id', () => {
    render(<BehindTheScenes events={[processPayment, created]} />);

    const link = screen.getByRole('link', { name: /open the full distributed trace in jaeger/i });
    expect(link).toHaveAttribute('href', `http://localhost:16686/trace/${TRACE}`);
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('omits the Jaeger link when no row carries a trace id', () => {
    render(<BehindTheScenes events={[processPayment]} />);

    expect(screen.queryByRole('link', { name: /jaeger/i })).toBeNull();
  });

  it('shows the empty state when no events were recorded', () => {
    render(<BehindTheScenes events={[]} />);

    expect(
      screen.getByText('No events recorded (events are kept for 7 days).'),
    ).toBeInTheDocument();
  });

  it('renders an unknown event type without crashing', () => {
    render(
      <BehindTheScenes
        events={[{ eventType: 'Mystery<script>', topic: 'odd.topic', createdAt: '2026-01-01T00:00:00Z', attempts: 0 }]}
      />,
    );

    expect(screen.getByText('Mystery<script>')).toBeInTheDocument();
    expect(screen.getByText('An event this screen does not describe yet.')).toBeInTheDocument();
  });

  it('highlights only the services this order touched', () => {
    render(<BehindTheScenes events={[created]} />);

    expect(document.querySelector('[data-service="order-service"]')).toHaveAttribute('data-active', 'true');
    expect(document.querySelector('[data-service="inventory-service"]')).toHaveAttribute('data-active', 'true');
    expect(document.querySelector('[data-service="payment-service"]')).toHaveAttribute('data-active', 'false');
  });

  it('highlights api-gateway when the order has recorded events', () => {
    render(<BehindTheScenes events={[created]} />);

    expect(document.querySelector('[data-service="api-gateway"]')).toHaveAttribute('data-active', 'true');
  });

  it('leaves every chip grey, including api-gateway, when there are no events', () => {
    render(<BehindTheScenes events={[]} />);

    for (const chip of document.querySelectorAll('[data-service]')) {
      expect(chip).toHaveAttribute('data-active', 'false');
    }
  });
});
