package com.example.orders.support.messaging;

import com.example.orders.support.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Carries the correlation id from the Kafka header into the MDC for the duration of one record, so
 * a saga step logged in inventory-service can be grepped with the same id as the HTTP request that
 * started it.
 *
 * <p>The id is sanitised exactly like an incoming HTTP header: the value comes from another process
 * and ends up in log lines, so it is never trusted verbatim. The MDC is always cleared again —
 * listener threads are long-lived, and a leaked id would mislabel every later record.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitise(EventEnvelopeReader.correlationIdOf(record)));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
