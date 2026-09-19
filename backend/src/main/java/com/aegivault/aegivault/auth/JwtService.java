package com.aegivault.aegivault.auth;

import com.aegivault.aegivault.identity.Role;
import com.aegivault.aegivault.identity.User;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues HMAC SHA-256 bearer tokens via Spring Security's {@link JwtEncoder}
 * (Nimbus under the hood — no hand-rolled signing). Claims: {@code sub} is
 * the user UUID, plus {@code email}, {@code roles} (plain names such as
 * {@code USER}), {@code iat}, and {@code exp} 60 minutes after issuance.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final Duration TTL = Duration.ofMinutes(60);

    private final JwtEncoder jwtEncoder;

    public String issueToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().map(Role::getName).sorted().toList())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return TTL.toSeconds();
    }
}
