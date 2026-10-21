package until.the.eternity.dgs;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import until.the.eternity.dgs.config.JwtProperties;
import until.the.eternity.dgs.util.JwtTokenProvider;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LocalJwtSampleTest {
    @ParameterizedTest
    @ValueSource(strings = {".env.example", ".env.local.sample"})
    void localSampleCanVerifyAnAuthStyleToken(String sample) throws Exception {
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(sample))) {
            env.load(reader);
        }
        byte[] key = Base64.getDecoder().decode(env.getProperty("JWT_SECRET_KEY"));
        assertThat(key.length).isGreaterThanOrEqualTo(32);

        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(env.getProperty("JWT_SECRET_KEY"));
        properties.setIssuer(env.getProperty("JWT_ISSUER"));
        JwtTokenProvider provider = new JwtTokenProvider(properties);
        String token = Jwts.builder()
                .issuer("devnogi")
                .subject("local@example.invalid")
                .claim("userId", 1L)
                .claim("role", "USER")
                .claim("type", "ACCESS")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(key))
                .compact();

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserId(token)).isEqualTo(1L);
        assertThat(provider.getTokenType(token)).isEqualTo("ACCESS");
    }
}
