package com.moveinsync.config.server.security;

import com.moveinsync.config.server.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Issues and verifies HS256 access tokens carrying role and tenant claims. */
@Service
public class TokenService {

    static final String ISSUER = "moveinsync-config";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public TokenService(AppProperties props) {
        String secret = props.security().jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.security.jwt-secret (JWT_SECRET) must be set to at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder = d;
        this.ttl = props.security().tokenTtl();
    }

    public record Issued(String token, long expiresInSeconds) {
    }

    public Issued issue(AuthUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.subject())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("role", user.role().name());
        if (user.tenant() != null) {
            claims.claim("tenant", user.tenant());
        }
        if (!user.serviceTenants().isEmpty()) {
            claims.claim("tenants", List.copyOf(user.serviceTenants()));
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new Issued(token, ttl.toSeconds());
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public static AuthUser toUser(Jwt jwt) {
        List<String> tenants = jwt.getClaimAsStringList("tenants");
        return new AuthUser(jwt.getSubject(), Role.valueOf(jwt.getClaimAsString("role")), jwt.getClaimAsString("tenant"),
                tenants == null ? Set.of() : Set.copyOf(tenants));
    }
}
