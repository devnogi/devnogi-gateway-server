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
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
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
            String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";

            log.info("[JWT-DEBUG] ========== JWT Filter Start ==========");
            log.info("[JWT-DEBUG] Request: {} {}", method, path);
            log.info("[JWT-DEBUG] Origin: {}", request.getHeaders().getOrigin());
            log.info("[JWT-DEBUG] All Headers: {}", request.getHeaders().toSingleValueMap());

            // 쿠키 정보 로깅
            MultiValueMap<String, HttpCookie> cookies = request.getCookies();
            log.info("[JWT-DEBUG] Cookies: {}", cookies.keySet());
            cookies.forEach((name, cookieList) ->
                cookieList.forEach(cookie ->
                    log.info("[JWT-DEBUG] Cookie '{}': {} (length: {})",
                        name,
                        cookie.getValue().length() > 20 ? cookie.getValue().substring(0, 20) + "..." : cookie.getValue(),
                        cookie.getValue().length())
                )
            );

            // Step 1: 클라이언트가 보낸 X-Auth-* 헤더 제거 (보안상 중요!)
            request = stripInternalHeaders(request);

            // Step 2: Authorization 헤더 또는 쿠키에서 JWT 토큰 추출
            String token = extractToken(request);
            String tokenSource = "unknown";

            if (token == null) {
                // Authorization 헤더에 없으면 쿠키에서 access_token 추출 시도
                token = extractTokenFromCookie(request);
                if (token != null) {
                    tokenSource = "cookie";
                    log.info("[JWT-DEBUG] Token extracted from cookie 'access_token'");
                }
            } else {
                tokenSource = "Authorization header";
                log.info("[JWT-DEBUG] Token extracted from Authorization header");
            }

            if (token == null) {
                log.warn("[JWT-DEBUG] No JWT token found in Authorization header or cookies for path: {}", path);
                log.info("[JWT-DEBUG] ========== JWT Filter End (UNAUTHORIZED - No Token) ==========");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            log.info("[JWT-DEBUG] Token source: {}, Token length: {}", tokenSource, token.length());
            log.info("[JWT-DEBUG] Token preview: {}...", token.length() > 50 ? token.substring(0, 50) : token);

            // Step 3: JWT 토큰 검증
            if (!validateToken(token, config)) {
                log.warn("[JWT-DEBUG] Invalid JWT token for path: {}", path);
                log.info("[JWT-DEBUG] ========== JWT Filter End (UNAUTHORIZED - Invalid Token) ==========");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            log.info("[JWT-DEBUG] Token validation: PASSED");

            // Step 4: ACCESS 토큰만 허용 (REFRESH 토큰은 /auth/refresh에서만 사용)
            String tokenType = getTokenType(token, config);
            log.info("[JWT-DEBUG] Token type: {}", tokenType);
            if (!"ACCESS".equals(tokenType)) {
                log.warn("[JWT-DEBUG] Token type is not ACCESS: {}", tokenType);
                log.info("[JWT-DEBUG] ========== JWT Filter End (UNAUTHORIZED - Wrong Token Type) ==========");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // Step 5: 사용자 정보 추출 및 ServerWebExchange에 저장
            try {
                Long userId = getUserId(token, config);
                String username = getUsername(token, config);
                String role = getRole(token, config);

                log.info("[JWT-DEBUG] Authenticated user - ID: {}, Username: {}, Role: {}", userId, username, role);

                // ServerWebExchange의 attributes에 사용자 정보 저장
                exchange.getAttributes().put("userId", userId);
                exchange.getAttributes().put("username", username);
                exchange.getAttributes().put("role", role);

                log.info("[JWT-DEBUG] ========== JWT Filter End (SUCCESS) ==========");
                return chain.filter(exchange.mutate().request(request).build());
            } catch (Exception e) {
                log.error("[JWT-DEBUG] Error extracting user info from token: {}", e.getMessage(), e);
                log.info("[JWT-DEBUG] ========== JWT Filter End (UNAUTHORIZED - Extraction Error) ==========");
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
        log.debug("[JWT-DEBUG] Authorization header: {}", authHeader != null ? "present" : "absent");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * 쿠키에서 access_token 추출
     */
    private String extractTokenFromCookie(ServerHttpRequest request) {
        MultiValueMap<String, HttpCookie> cookies = request.getCookies();
        if (cookies.containsKey("access_token")) {
            HttpCookie accessTokenCookie = cookies.getFirst("access_token");
            if (accessTokenCookie != null) {
                log.debug("[JWT-DEBUG] Found access_token cookie");
                return accessTokenCookie.getValue();
            }
        }
        log.debug("[JWT-DEBUG] No access_token cookie found");
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
        log.debug("[JWT-DEBUG] Validating token with issuer: {}", config.getIssuer());
        try {
            Claims claims = extractAllClaims(token, config);
            log.debug("[JWT-DEBUG] Token claims - subject: {}, issuer: {}, expiration: {}",
                claims.getSubject(), claims.getIssuer(), claims.getExpiration());
            return true;
        } catch (SignatureException e) {
            log.error("[JWT-DEBUG] Invalid JWT signature: {}", e.getMessage());
            return false;
        } catch (MalformedJwtException e) {
            log.error("[JWT-DEBUG] Invalid JWT token (malformed): {}", e.getMessage());
            return false;
        } catch (ExpiredJwtException e) {
            log.error("[JWT-DEBUG] JWT token is expired: {} (expired at: {})",
                e.getMessage(), e.getClaims() != null ? e.getClaims().getExpiration() : "unknown");
            return false;
        } catch (UnsupportedJwtException e) {
            log.error("[JWT-DEBUG] JWT token is unsupported: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.error("[JWT-DEBUG] JWT claims string is empty: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("[JWT-DEBUG] Unexpected error during token validation: {}", e.getMessage(), e);
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
