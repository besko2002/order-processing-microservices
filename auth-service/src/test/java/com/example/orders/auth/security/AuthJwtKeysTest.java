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
import java.security.interfaces.RSAPrivateKey;
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
    void maintainsStableKeyIdAndKeyPairAcrossIndependentInstances() throws Exception {
        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String stableKid = "auth-stable-kid-2026";
        AuthProperties props = propertiesWith(privatePem, publicPem, stableKid);

        AuthJwtKeys firstInstance = new AuthJwtKeys(props);
        AuthJwtKeys secondInstance = new AuthJwtKeys(props);

        assertThat(firstInstance.keyId()).isEqualTo(stableKid);
        assertThat(secondInstance.keyId()).isEqualTo(stableKid);
        assertThat(firstInstance.keyId()).isEqualTo(secondInstance.keyId());

        assertThat(firstInstance.ephemeral()).isFalse();
        assertThat(secondInstance.ephemeral()).isFalse();
        assertThat(firstInstance.publicKey().getModulus())
                .isEqualTo(secondInstance.publicKey().getModulus());
        assertThat(firstInstance.publicKey().getPublicExponent())
                .isEqualTo(secondInstance.publicKey().getPublicExponent());

        String token = new NimbusJwtEncoder(firstInstance.jwkSource())
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(firstInstance.keyId()).build(),
                        JwtClaimsSet.builder()
                                .issuer("order-processing-auth")
                                .subject("00000000-0000-0000-0000-000000000002")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build()))
                .getTokenValue();

        SignedJWT parsedToken = SignedJWT.parse(token);
        assertThat(parsedToken.getHeader().getKeyID()).isEqualTo(stableKid);
        JWSVerifier verifier = new RSASSAVerifier(secondInstance.publicKey());
        assertThat(parsedToken.verify(verifier)).isTrue();

        AuthJwtKeys ephemeral1 = new AuthJwtKeys(propertiesWith("", "", ""));
        AuthJwtKeys ephemeral2 = new AuthJwtKeys(propertiesWith("", "", ""));
        assertThat(ephemeral1.keyId()).isNotEqualTo(ephemeral2.keyId());
        assertThat(ephemeral1.publicKey().getModulus())
                .isNotEqualTo(ephemeral2.publicKey().getModulus());
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
    void acceptsKeyPathsFromFileSystem(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path privFile = tempDir.resolve("jwt-priv.pem");
        java.nio.file.Path pubFile = tempDir.resolve("jwt-pub.pem");
        java.nio.file.Files.writeString(privFile, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        java.nio.file.Files.writeString(pubFile, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        AuthJwtKeys keys = new AuthJwtKeys(propertiesWith(privFile.toString(), pubFile.toString(), "file-kid"));

        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.keyId()).isEqualTo("file-kid");
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

    @Test
    void rejectsOnlyPublicWithoutPrivate() {
        AuthProperties onlyPublic =
                propertiesWith("", pem("PUBLIC KEY", keyPair.getPublic().getEncoded()), "kid");

        assertThatThrownBy(() -> new AuthJwtKeys(onlyPublic))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured together");
    }

    @Test
    void rejectsMismatchedRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair secondPair = generator.generateKeyPair();

        AuthProperties mismatched = propertiesWith(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", secondPair.getPublic().getEncoded()),
                "mismatched-kid");

        assertThatThrownBy(() -> new AuthJwtKeys(mismatched))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching RSA key pair");
    }

    @Test
    void rejectsKeyPairWithSameModulusButDifferentPublicExponent() throws Exception {
        RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        java.math.BigInteger alternateExponent = pub.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))
                ? java.math.BigInteger.valueOf(3)
                : java.math.BigInteger.valueOf(65537);

        java.security.spec.RSAPublicKeySpec spec =
                new java.security.spec.RSAPublicKeySpec(priv.getModulus(), alternateExponent);
        RSAPublicKey mismatchedPub =
                (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);

        AuthProperties mismatchedExponent = propertiesWith(
                pem("PRIVATE KEY", priv.getEncoded()),
                pem("PUBLIC KEY", mismatchedPub.getEncoded()),
                "mismatched-exp-kid");

        assertThatThrownBy(() -> new AuthJwtKeys(mismatchedExponent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching RSA key pair");
    }

    @Test
    void rejectsNonexistentKeyPath() {
        AuthProperties nonexistent =
                propertiesWith("/nonexistent/file/jwt-private.pem", "/nonexistent/file/jwt-public.pem", "kid");

        assertThatThrownBy(() -> new AuthJwtKeys(nonexistent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("nonexistent")
                .hasMessageContaining("AUTH_JWT_PRIVATE_KEY");
    }

    @Test
    void rejectsDirectoryKeyPath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path dirPath = tempDir.resolve("dir-as-key");
        java.nio.file.Files.createDirectory(dirPath);

        AuthProperties dirKey = propertiesWith(dirPath.toString(), dirPath.toString(), "kid");

        assertThatThrownBy(() -> new AuthJwtKeys(dirKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(dirPath.toString());
    }

    @Test
    void rejectsUnreadableKeyPathWhenEnforced(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path unreadableFile = tempDir.resolve("unreadable-priv.pem");
        java.nio.file.Files.writeString(unreadableFile, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        unreadableFile.toFile().setReadable(false, false);

        org.junit.jupiter.api.Assumptions.assumeTrue(
                !java.nio.file.Files.isReadable(unreadableFile),
                "Test runner runs with elevated permissions (e.g. root) bypassing POSIX read denials; skipping");

        AuthProperties props = propertiesWith(unreadableFile.toString(), pem("PUBLIC KEY", keyPair.getPublic().getEncoded()), "kid");
        assertThatThrownBy(() -> new AuthJwtKeys(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(unreadableFile.toString());
    }

    @Test
    void rejectsMalformedPemInFileWithoutLeakingContents(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path privFile = tempDir.resolve("malformed-priv.pem");
        java.nio.file.Path pubFile = tempDir.resolve("malformed-pub.pem");
        String secretBadContent = "SECRET_MALFORMED_HEADER_DATA_12345";
        java.nio.file.Files.writeString(privFile, "-----BEGIN PRIVATE KEY-----\n" + secretBadContent + "\n-----END PRIVATE KEY-----");
        java.nio.file.Files.writeString(pubFile, "-----BEGIN PUBLIC KEY-----\n" + secretBadContent + "\n-----END PUBLIC KEY-----");

        AuthProperties props = propertiesWith(privFile.toString(), pubFile.toString(), "kid");

        try {
            new AuthJwtKeys(props);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException ex) {
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            String fullTrace = sw.toString();
            assertThat(fullTrace).doesNotContain(secretBadContent);

            Throwable current = ex;
            while (current != null) {
                assertThat(current.getMessage()).doesNotContain(secretBadContent);
                current = current.getCause();
            }
        }
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
