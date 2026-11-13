package com.example.orders.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.auth.config.AuthProperties;
import com.example.orders.auth.domain.Role;
import com.example.orders.auth.domain.UserAccount;
import com.example.orders.auth.domain.UserAccountRepository;
import com.example.orders.auth.error.InvalidCredentialsException;
import com.example.orders.auth.security.AuthJwtKeys;
import com.example.orders.auth.service.AdminSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** End-to-end coverage of phase 2 against a real Postgres and the full application context. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private AdminSeeder adminSeeder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AuthJwtKeys keys;

    @Autowired
    private AuthProperties properties;

    @BeforeEach
    void resetDatabase() {
        users.deleteAll();
        adminSeeder.seed();
    }

    // ------------------------------------------------------------------ register

    @Test
    void registerReturns201WithTokenAndUser() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Ada Lovelace", "ada@example.com", "s3cret-pass")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void issuedTokenCarriesTheExpectedClaims() throws Exception {
        JsonNode response = register("Grace Hopper", "grace@example.com", "s3cret-pass");
        SignedJWT token = SignedJWT.parse(response.get("accessToken").asText());

        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(token.getHeader().getKeyID()).isEqualTo(keys.keyId());
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo(properties.jwt().issuer());
        assertThat(token.getJWTClaimsSet().getSubject())
                .isEqualTo(response.get("user").get("id").asText());
        assertThat(token.getJWTClaimsSet().getStringClaim("email")).isEqualTo("grace@example.com");
        assertThat(token.getJWTClaimsSet().getStringClaim("name")).isEqualTo("Grace Hopper");
        assertThat(token.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("USER");
        assertThat(token.getJWTClaimsSet().getIssueTime()).isNotNull();
        assertThat(token.getJWTClaimsSet().getExpirationTime())
                .isAfter(token.getJWTClaimsSet().getIssueTime());
    }

    @Test
    void passwordIsStoredAsBcryptHashOnly() throws Exception {
        register("Alan Turing", "alan@example.com", "s3cret-pass");

        Optional<UserAccount> stored = users.findByEmail("alan@example.com");
        assertThat(stored).isPresent();
        String hash = stored.orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$").doesNotContain("s3cret-pass");
        assertThat(passwordEncoder.matches("s3cret-pass", hash)).isTrue();
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        register("Ada", "dup@example.com", "s3cret-pass");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Ada Again", "dup@example.com", "s3cret-pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        assertThat(users.countByEmail("dup@example.com")).isEqualTo(1);
    }

    @Test
    void duplicateEmailInADifferentCaseAlsoReturns409() throws Exception {
        register("Ada", "case@example.com", "s3cret-pass");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Ada Again", "CASE@Example.COM", "s3cret-pass")))
                .andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(2); // the admin + one registered user
    }

    @Test
    void emailWithSurroundingSpacesIsAcceptedAndNormalised() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Spaced", "  Spaced.User@Example.COM  ", "s3cret-pass")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("spaced.user@example.com"));

        assertThat(users.findByEmail("spaced.user@example.com")).isPresent();
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());
    }

    @Test
    void malformedJsonReturns400WithoutFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginReturns200WithAToken() throws Exception {
        register("Ada", "login@example.com", "s3cret-pass");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("  LOGIN@example.com ", "s3cret-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("login@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void wrongPasswordAndUnknownEmailFailIdentically() throws Exception {
        register("Ada", "known@example.com", "s3cret-pass");

        MvcResult wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("known@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE))
                .andReturn();

        MvcResult unknownEmail = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com", "s3cret-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE))
                .andReturn();

        JsonNode first = json.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode second = json.readTree(unknownEmail.getResponse().getContentAsString());
        assertThat(second.get("message").asText()).isEqualTo(first.get("message").asText());
        assertThat(second.get("status").asInt()).isEqualTo(first.get("status").asInt());
        assertThat(second.get("error").asText()).isEqualTo(first.get("error").asText());
    }

    // ------------------------------------------------------------------ /me

    @Test
    void meWithAValidTokenReturnsTheCurrentUser() throws Exception {
        JsonNode registered = register("Ada Lovelace", "me@example.com", "s3cret-pass");
        String token = registered.get("accessToken").asText();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registered.get("user").get("id").asText()))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void meWithoutATokenReturns401InTheSharedErrorShape() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/auth/me"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void meWithATamperedTokenReturns401() throws Exception {
        String token = register("Ada", "tamper@example.com", "s3cret-pass")
                .get("accessToken").asText();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tamper(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void meWithAGarbageTokenReturns401() throws Exception {
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void meWithAnExpiredTokenReturns401() throws Exception {
        register("Expired", "expired@example.com", "s3cret-pass");
        UserAccount user = users.findByEmail("expired@example.com").orElseThrow();

        // Signed with the very same key, only the lifetime is in the past.
        Instant issuedAt = Instant.now().minusSeconds(7200);
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build(),
                        JwtClaimsSet.builder()
                                .issuer(properties.jwt().issuer())
                                .subject(user.getId().toString())
                                .issuedAt(issuedAt)
                                .expiresAt(issuedAt.plusSeconds(3600))
                                .claim("email", user.getEmail())
                                .claim("name", user.getName())
                                .claim("roles", List.of(user.getRole().name()))
                                .build()))
                .getTokenValue();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ------------------------------------------------------------------ JWKS

    @Test
    void jwksExposesOnePublicRsaKeyThatVerifiesIssuedTokens() throws Exception {
        String token = register("Ada", "jwks@example.com", "s3cret-pass")
                .get("accessToken").asText();

        String body = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JWKSet jwkSet = JWKSet.parse(body);
        assertThat(jwkSet.getKeys()).hasSize(1);

        JWK jwk = jwkSet.getKeys().get(0);
        assertThat(jwk.getKeyType()).isEqualTo(KeyType.RSA);
        assertThat(jwk.isPrivate()).isFalse();

        SignedJWT signed = SignedJWT.parse(token);
        assertThat(signed.getHeader().getKeyID()).isEqualTo(jwk.getKeyID());
        assertThat(signed.verify(new RSASSAVerifier(((RSAKey) jwk).toRSAPublicKey()))).isTrue();
    }

    // ------------------------------------------------------------------ admin seeding

    @Test
    void seededAdminCanLogInAndHasTheAdminRole() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(properties.admin().email(), properties.admin().password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(properties.admin().email()))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void seededAdminTokenCarriesTheAdminRoleClaim() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(properties.admin().email(), properties.admin().password())))
                .andExpect(status().isOk())
                .andReturn();

        String token = json.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("ADMIN");
    }

    @Test
    void seedingTwiceDoesNotDuplicateTheAdmin() {
        String adminEmail = properties.admin().email();
        assertThat(users.countByEmail(adminEmail)).isEqualTo(1);

        assertThat(adminSeeder.seed()).isFalse();
        assertThat(adminSeeder.seed()).isFalse();

        assertThat(users.countByEmail(adminEmail)).isEqualTo(1);
        assertThat(users.findByEmail(adminEmail).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode register(String name, String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String registerBody(String name, String email, String password) throws Exception {
        return json.writeValueAsString(json.createObjectNode()
                .put("name", name)
                .put("email", email)
                .put("password", password));
    }

    private String loginBody(String email, String password) throws Exception {
        return json.writeValueAsString(json.createObjectNode()
                .put("email", email)
                .put("password", password));
    }

    /**
     * Rewrites the payload (a privilege escalation attempt) while keeping the original signature,
     * so the RS256 check must fail. Editing base64url characters directly would not be reliable:
     * the last character of a 256-byte signature carries unused trailing bits, so some flips decode
     * back to the very same bytes.
     *
     * @param token a validly signed token
     * @return the same token with USER swapped for ADMIN in the claims
     */
    private static String tamper(String token) {
        String[] parts = token.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String escalated = claims.replace("\"USER\"", "\"ADMIN\"");
        assertThat(escalated).isNotEqualTo(claims);
        String reEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(escalated.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + reEncoded + "." + parts[2];
    }
}
