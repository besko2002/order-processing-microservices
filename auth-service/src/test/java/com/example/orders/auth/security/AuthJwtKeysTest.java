package com.example.orders.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orders.auth.config.AuthProperties;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Slice test for key configuration: no Spring context needed, {@link AuthJwtKeys} is a plain
 * object driven only by {@link AuthProperties}.
 */
class AuthJwtKeysTest {

    private static final AuthProperties.Admin ADMIN =
            new AuthProperties.Admin("admin@orders.local", "Admin@12345", "Platform Admin");

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void usesConfiguredPemKeyPairAndKeyId() {
        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                "auth-test-kid"));

        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.keyId()).isEqualTo("auth-test-kid");
        assertThat(keys.publicKey().getModulus())
                .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
    }

    @Test
    void configuredPrivateKeyIsTheOneUsedForSigning() throws Exception {
        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                "auth-test-kid"));

        String token = new NimbusJwtEncoder(keys.jwkSource())
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build(),
                        JwtClaimsSet.builder()
                                .issuer("order-processing-auth")
                                .subject("00000000-0000-0000-0000-000000000001")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build()))
                .getTokenValue();

        SignedJWT signed = SignedJWT.parse(token);
        JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) keyPair.getPublic());
        assertThat(signed.verify(verifier)).isTrue();
        assertThat(signed.getHeader().getKeyID()).isEqualTo("auth-test-kid");
    }

    @Test
    void acceptsSingleLinePemWithEscapedNewlines() {
        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()).replace("\n", "\\n");
        String publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded()).replace("\n", "\\n");

        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith(privatePem, publicPem, "escaped-kid"));

        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.publicKey().getModulus())
                .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
    }

    @Test
    void publishesOnlyThePublicHalfInTheJwkSet() {
        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                "auth-test-kid"));

        assertThat(keys.publicJwkSet().getKeys()).hasSize(1);
        JWK jwk = keys.publicJwkSet().getKeys().get(0);
        assertThat(jwk.getKeyType()).isEqualTo(KeyType.RSA);
        assertThat(jwk.isPrivate()).isFalse();
        assertThat(((RSAKey) jwk).getPrivateExponent()).isNull();
    }

    @Test
    void generatesEphemeralKeyPairWhenNothingIsConfigured() {
        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith("", "", ""));

        assertThat(keys.ephemeral()).isTrue();
        assertThat(keys.publicKey().getModulus().bitLength()).isEqualTo(2048);
        assertThat(keys.keyId()).isNotBlank();
        assertThat(keys.publicJwkSet().getKeys()).hasSize(1);
    }

    @Test
    void rejectsHalfConfiguredKeyPair() {
        AuthProperties onlyPrivate =
                propertiesWith(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()), "", "kid");

        assertThatThrownBy(() -> new AuthJwtKeys(onlyPrivate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured together");
    }

    @Test
    void rejectsGarbagePem() {
        AuthProperties garbage = propertiesWith("not-a-key", "not-a-key", "kid");

        assertThatThrownBy(() -> new AuthJwtKeys(garbage))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AuthProperties propertiesWith(String privateKey, String publicKey, String keyId) {
        return new AuthProperties(
                new AuthProperties.Jwt(
                        "order-processing-auth", Duration.ofHours(1), keyId, privateKey, publicKey),
                ADMIN);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der) + "\n"
                + "-----END " + label + "-----\n";
    }
}
