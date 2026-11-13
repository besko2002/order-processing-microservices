package com.example.orders.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The user id always comes from the token's {@code sub}, never from a request header. */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsTheUserIdAndRolesFromTheToken() {
        UUID userId = UUID.randomUUID();
        authenticate(userId.toString(), "ROLE_ADMIN", "ROLE_USER");

        assertThat(CurrentUser.id()).contains(userId);
        assertThat(CurrentUser.requireId()).isEqualTo(userId);
        assertThat(CurrentUser.roles()).containsExactlyInAnyOrder("ADMIN", "USER");
        assertThat(CurrentUser.hasRole("ADMIN")).isTrue();
        assertThat(CurrentUser.hasRole("OPERATOR")).isFalse();
    }

    @Test
    void ignoresAuthoritiesThatAreNotRoles() {
        authenticate(UUID.randomUUID().toString(), "SCOPE_read", "ROLE_USER");

        assertThat(CurrentUser.roles()).containsExactly("USER");
    }

    @Test
    void reportsNoUserWhenTheRequestIsAnonymous() {
        assertThat(CurrentUser.id()).isEmpty();
        assertThat(CurrentUser.roles()).isEmpty();
        assertThat(CurrentUser.hasRole("ADMIN")).isFalse();
        assertThatThrownBy(CurrentUser::requireId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsNoUserForAnAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    void reportsNoUserWhenTheSubjectIsNotAUuid() {
        authenticate("not-a-uuid", "ROLE_USER");

        assertThat(CurrentUser.id()).isEmpty();
        assertThat(CurrentUser.roles()).containsExactly("USER");
    }

    private static void authenticate(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuer("order-processing-auth")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("USER"))
                .build();
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, granted));
    }
}
