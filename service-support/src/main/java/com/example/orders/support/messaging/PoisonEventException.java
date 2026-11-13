package com.example.orders.support.messaging;

/**
 * The record on the topic is not a message of this system: unparsable JSON, a missing
 * {@code eventId}, or an {@code eventType} the shared catalogue does not know.
 *
 * <p>Retrying such a record is pointless — the bytes will not improve — so the error handler is
 * configured to treat this exception as not retryable and hands the record straight to
 * {@code <topic>.DLT}. Keeping it out of the retry loop also matters operationally: a poison record
 * at the head of a partition would otherwise stall every well-formed message behind it for the
 * whole backoff.
 */
public class PoisonEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong with the record
     */
    public PoisonEventException(String message) {
        super(message);
    }

    /**
     * @param message what is wrong with the record
     * @param cause   the parse failure
     */
    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
