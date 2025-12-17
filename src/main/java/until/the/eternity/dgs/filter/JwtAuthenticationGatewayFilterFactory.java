package until.the.eternity.dgs.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.Base64;

@Slf4j
@Component
public class JwtAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthenticationGatewayFilterFactory.Config> {

    public JwtAuthenticationGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();

            log.info("JWT Filter processing request: {} {}", request.getMethod(), path);

            // Step 1: 클라이언트가 보낸 X-Auth-* 헤더 제거 (보안상 중요!)
            request = stripInternalHeaders(request);

            // Step 2: Authorization 헤더에서 JWT 토큰 추출
            String token = extractToken(request);
            if (token == null) {
                log.warn("No JWT token found in Authorization header for path: {}", path);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // Step 3: JWT 토큰 검증
            if (!validateToken(token, config)) {
                log.warn("Invalid JWT token for path: {}", path);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // Step 4: ACCESS 토큰만 허용 (REFRESH 토큰은 /auth/refresh에서만 사용)
            String tokenType = getTokenType(token, config);
            if (!"ACCESS".equals(tokenType)) {
                log.warn("Token type is not ACCESS: {}", tokenType);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // Step 5: 사용자 정보 추출 및 ServerWebExchange에 저장
            try {
                Long userId = getUserId(token, config);
                String username = getUsername(token, config);
                String role = getRole(token, config);

                log.debug("Authenticated user - ID: {}, Username: {}, Role: {}", userId, username, role);

                // ServerWebExchange의 attributes에 사용자 정보 저장
                exchange.getAttributes().put("userId", userId);
                exchange.getAttributes().put("username", username);
                exchange.getAttributes().put("role", role);

                return chain.filter(exchange.mutate().request(request).build());
            } catch (Exception e) {
                log.error("Error extracting user info from token: {}", e.getMessage(), e);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출
     */
    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * 클라이언트가 보낸 X-Auth-* 헤더 제거
     * (보안상 중요: 클라이언트가 위조한 인증 헤더를 제거)
     */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove("X-Auth-User-Id");
                    headers.remove("X-Auth-Username");
                    headers.remove("X-Auth-Roles");
                    headers.remove("X-Auth-Token-Id");
                })
                .build();
    }

    /**
     * Secret Key 생성
     */
    private SecretKey getSecretKey(Config config) {
        byte[] keyBytes = Base64.getDecoder().decode(config.getSecretKey());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * JWT 토큰 검증
     */
    private boolean validateToken(String token, Config config) {
        try {
            extractAllClaims(token, config);
            return true;
        } catch (SignatureException e) {
            log.error("Invalid JWT signature: {}", e.getMessage());
            return false;
        } catch (MalformedJwtException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
            return false;
        } catch (ExpiredJwtException e) {
            log.error("JWT token is expired: {}", e.getMessage());
            return false;
        } catch (UnsupportedJwtException e) {
            log.error("JWT token is unsupported: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 토큰에서 모든 Claims 추출
     */
    private Claims extractAllClaims(String token, Config config) {
        return Jwts.parser()
                .verifyWith(getSecretKey(config))
                .requireIssuer(config.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 토큰에서 사용자 ID 추출
     */
    private Long getUserId(String token, Config config) {
        Claims claims = extractAllClaims(token, config);
        return claims.get("userId", Long.class);
    }

    /**
     * 토큰에서 사용자 이메일(subject) 추출
     */
    private String getUsername(String token, Config config) {
        Claims claims = extractAllClaims(token, config);
        return claims.getSubject();
    }

    /**
     * 토큰에서 사용자 역할 추출
     */
    private String getRole(String token, Config config) {
        Claims claims = extractAllClaims(token, config);
        return claims.get("role", String.class);
    }

    /**
     * 토큰 타입 확인 (ACCESS or REFRESH)
     */
    private String getTokenType(String token, Config config) {
        Claims claims = extractAllClaims(token, config);
        return claims.get("type", String.class);
    }

    /**
     * Configuration class for JWT authentication filter
     */
    @Getter
    @Setter
    public static class Config {
        private String secretKey;
        private String issuer;
    }
}
