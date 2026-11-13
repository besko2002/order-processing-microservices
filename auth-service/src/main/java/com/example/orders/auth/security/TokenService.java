package com.example.orders.auth.security;

import com.example.orders.auth.config.AuthProperties;
import com.example.orders.auth.domain.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the RS256 access tokens returned by register and login. */
@Service
public class TokenService {

    /** Claim holding the role names, always an array (e.g. {@code ["USER"]}). */
    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final AuthJwtKeys keys;
    private final AuthProperties properties;

    TokenService(JwtEncoder encoder, AuthJwtKeys keys, AuthProperties properties) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
    }

    /**
     * @param user account the token is issued for
     * @return the signed compact token plus its lifetime in seconds
     */
    public IssuedToken issue(UserAccount user) {
        Duration ttl = properties.jwt().expiration();
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keys.keyId())
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, ttl.toSeconds());
    }

    /**
     * @param value           compact serialised JWS
     * @param expiresInSeconds seconds until {@code exp}
     */
    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
