package com.example.orders.auth.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Minimal PEM reader for the two key settings. Accepts a real multi-line PEM as well as a
 * single-line value with literal {@code \n} escapes, which is how PEM material usually
 * survives a {@code .env} file or a container environment variable.
 */
final class PemKeys {

    private PemKeys() {
    }

    /**
     * @param pem PKCS#8 encoded private key
     * @return the parsed RSA private key
     */
    static RSAPrivateKey readPrivateKey(String pem) {
        try {
            return (RSAPrivateKey) rsa().generatePrivate(new PKCS8EncodedKeySpec(der(resolvePem(pem))));
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "auth.jwt.private-key (AUTH_JWT_PRIVATE_KEY) is not a valid PKCS#8 RSA PEM private key", e);
        }
    }

    /**
     * @param pem X.509 (SubjectPublicKeyInfo) encoded public key
     * @return the parsed RSA public key
     */
    static RSAPublicKey readPublicKey(String pem) {
        try {
            return (RSAPublicKey) rsa().generatePublic(new X509EncodedKeySpec(der(resolvePem(pem))));
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "auth.jwt.public-key (AUTH_JWT_PUBLIC_KEY) is not a valid X.509 RSA PEM public key", e);
        }
    }

    private static String resolvePem(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("-----BEGIN")) {
            return trimmed;
        }
        if (!trimmed.contains("\n") && !trimmed.contains("\r")) {
            try {
                java.nio.file.Path path = java.nio.file.Path.of(trimmed);
                if (java.nio.file.Files.isRegularFile(path)) {
                    return java.nio.file.Files.readString(path);
                }
            } catch (Exception ignored) {
                // Ignore and fall through to standard DER decoding to preserve safe generic errors
            }
        }
        return trimmed;
    }

    private static KeyFactory rsa() {
        try {
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyFactory unavailable on this JVM", e);
        }
    }

    private static byte[] der(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replace("\\r", "")
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
