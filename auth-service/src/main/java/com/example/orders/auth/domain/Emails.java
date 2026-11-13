package com.example.orders.auth.domain;

import java.util.Locale;

/** Single place that decides what "the same email" means across the service. */
public final class Emails {

    private Emails() {
    }

    /**
     * Trims surrounding whitespace and lowercases, so validation, uniqueness checks and
     * lookups all operate on the same canonical form.
     *
     * @param raw email as supplied by the client, may be {@code null}
     * @return normalised email, or {@code null} when {@code raw} is {@code null}
     */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }
}
