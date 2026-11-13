package com.example.orders.support.error;

/**
 * The request itself is unusable in a way bean validation cannot express (an unparsable id, an
 * empty item list). Rendered as {@code 400} without field errors.
 */
public class BadRequestException extends RuntimeException {

    /**
     * @param message safe-to-expose detail explaining what is wrong with the request
     */
    public BadRequestException(String message) {
        super(message);
    }
}
