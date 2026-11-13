package com.example.orders.support.error;

/** The addressed resource does not exist: rendered as {@code 404}. */
public class NotFoundException extends RuntimeException {

    /**
     * @param message safe-to-expose detail, e.g. {@code "Product 7f0... does not exist"}
     */
    public NotFoundException(String message) {
        super(message);
    }

    /**
     * @param resource resource type, e.g. {@code "Product"}
     * @param id       identifier that was not found
     * @return an exception with a uniform message
     */
    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(resource + " " + id + " does not exist");
    }
}
