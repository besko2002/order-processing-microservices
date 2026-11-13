package com.example.orders.auth.security;

import com.example.orders.auth.config.AuthProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.JWSAlgorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Owns the single RS256 key pair used to sign tokens, to validate them again on
 * {@code /api/auth/me}, and to serve {@code GET /.well-known/jwks.json}.
 *
 * <p>Keys come from {@code AUTH_JWT_PRIVATE_KEY} / {@code AUTH_JWT_PUBLIC_KEY}. When both are
 * absent a 2048-bit pair is generated at startup and a WARN is logged: convenient for local
 * development, but every restart invalidates previously issued tokens.
 */
@Component
public class AuthJwtKeys {

    private static final Logger log = LoggerFactory.getLogger(AuthJwtKeys.class);
    private static final int GENERATED_KEY_SIZE = 2048;

    private final RSAKey rsaKey;
    private final boolean ephemeral;

    public AuthJwtKeys(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.jwt();
        boolean hasPrivate = StringUtils.hasText(jwt.privateKey());
        boolean hasPublic = StringUtils.hasText(jwt.publicKey());

        if (hasPrivate != hasPublic) {
            throw new IllegalStateException(
                    "auth.jwt.private-key and auth.jwt.public-key must be configured together "
                            + "(AUTH_JWT_PRIVATE_KEY / AUTH_JWT_PUBLIC_KEY); got only "
                            + (hasPrivate ? "the private key" : "the public key"));
        }

        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (hasPrivate) {
            privateKey = PemKeys.readPrivateKey(jwt.privateKey());
            publicKey = PemKeys.readPublicKey(jwt.publicKey());
            validateKeyPair(privateKey, publicKey);
            this.ephemeral = false;
        } else {
            KeyPair generated = generateRsaKeyPair();
            publicKey = (RSAPublicKey) generated.getPublic();
            privateKey = (RSAPrivateKey) generated.getPrivate();
            this.ephemeral = true;
        }

        String keyId = StringUtils.hasText(jwt.keyId()) ? jwt.keyId().strip() : UUID.randomUUID().toString();
        this.rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        if (this.ephemeral) {
            log.warn("""
                    No RS256 key pair configured (AUTH_JWT_PRIVATE_KEY / AUTH_JWT_PUBLIC_KEY are empty): \
                    generated an EPHEMERAL {}-bit RSA pair with kid={}. Tokens issued by this instance \
                    will NOT survive a restart and other instances cannot verify them. Configure a PEM \
                    key pair (PKCS#8 private / X.509 public) for anything beyond local development.""",
                    GENERATED_KEY_SIZE, keyId);
        } else {
            log.info("Using configured RS256 key pair with kid={}", keyId);
        }
    }

    /** @return the {@code kid} written into every token header and published in the JWK Set */
    public String keyId() {
        return rsaKey.getKeyID();
    }

    /** @return {@code true} when the pair was generated at startup instead of configured */
    public boolean ephemeral() {
        return ephemeral;
    }

    /** @return the public key, used to build this service's own {@code JwtDecoder} */
    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Cannot export the RSA public key", e);
        }
    }

    /** @return source of the signing key for {@code NimbusJwtEncoder} */
    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** @return the public half only — what {@code /.well-known/jwks.json} serves */
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key pair generation unavailable on this JVM", e);
        }
    }

    private static void validateKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException(
                    "auth.jwt.private-key and auth.jwt.public-key do not form a matching RSA key pair "
                            + "(AUTH_JWT_PRIVATE_KEY / AUTH_JWT_PUBLIC_KEY)");
        }
        try {
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            byte[] probe = new byte[] {0x41, 0x55, 0x54, 0x48}; // fixed nonsecret probe "AUTH"
            signer.update(probe);
            byte[] signature = signer.sign();

            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException(
                        "auth.jwt.private-key and auth.jwt.public-key do not form a matching RSA key pair "
                                + "(AUTH_JWT_PRIVATE_KEY / AUTH_JWT_PUBLIC_KEY)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "auth.jwt.private-key and auth.jwt.public-key do not form a matching RSA key pair "
                            + "(AUTH_JWT_PRIVATE_KEY / AUTH_JWT_PUBLIC_KEY)", e);
        }
    }
}
