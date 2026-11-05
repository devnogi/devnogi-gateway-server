package until.the.eternity.dgs.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.util.Base64;
import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthenticationGatewayFilterFactory.Config> {

    private static final int JWT_FILTER_ORDER = -100;

    public JwtAuthenticationGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter =
                (exchange, chain) -> {
                    ServerHttpRequest request = exchange.getRequest();
                    String requestId = request.getId();
                    String path = SensitiveAuthLogPolicy.safeRequestPath(exchange);
                    String method =
                            request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";

                    // Step 1: 클라이언트가 보낸 X-Auth-* 헤더 제거 (보안상 중요!)
                    request = stripInternalHeaders(request);

                    // Step 2: Authorization 헤더 또는 쿠키에서 JWT 토큰 추출
                    String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    String token = null;
                    String tokenSource = "none";

                    if (authorization != null) {
                        tokenSource = "authorization";
                        if (!authorization.startsWith("Bearer ")
                                || authorization.substring(7).isBlank()) {
                            log.warn(
                                    "[JWT] requestId={} method={} path={} tokenSource={} result=unauthorized reason=malformed-authorization",
                                    requestId,
                                    method,
                                    path,
                                    tokenSource);
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }
                        token = authorization.substring(7);
                    } else {
                        // Authorization 헤더에 없으면 쿠키에서 access_token 추출 시도
                        token = extractTokenFromCookie(request);
                        if (token != null) {
                            tokenSource = "cookie";
                        }
                    }

                    if (token == null) {
                        if (!config.isRequired()) {
                            log.info(
                                    "[JWT] requestId={} method={} path={} tokenSource=none result=anonymous",
                                    requestId,
                                    method,
                                    path);
                            return chain.filter(exchange.mutate().request(request).build());
                        }
                        log.warn(
                                "[JWT] requestId={} method={} path={} tokenSource=none result=unauthorized reason=missing",
                                requestId,
                                method,
                                path);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Step 3: JWT 토큰 검증
                    Claims claims = validateToken(token, config);
                    if (claims == null) {
                        log.warn(
                                "[JWT] requestId={} method={} path={} tokenSource={} result=unauthorized reason=invalid",
                                requestId,
                                method,
                                path,
                                tokenSource);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Step 4: ACCESS 토큰만 허용 (REFRESH 토큰은 /auth/refresh에서만 사용)
                    String tokenType = claims.get("type", String.class);
                    if (!"ACCESS".equals(tokenType)) {
                        log.warn(
                                "[JWT] requestId={} method={} path={} tokenSource={} result=unauthorized reason=token-type",
                                requestId,
                                method,
                                path,
                                tokenSource);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Step 5: 사용자 정보 추출 및 ServerWebExchange에 저장
                    try {
                        Long userId = claims.get("userId", Long.class);
                        String username = claims.getSubject();
                        String role = claims.get("role", String.class);
                        if (userId == null || username == null || role == null) {
                            throw new IllegalArgumentException("required claims are missing");
                        }

                        // ServerWebExchange의 attributes에 사용자 정보 저장
                        exchange.getAttributes().put("userId", userId);
                        exchange.getAttributes().put("username", username);
                        exchange.getAttributes().put("role", role);

                        log.info(
                                "[JWT] requestId={} method={} path={} tokenSource={} result=authenticated",
                                requestId,
                                method,
                                path,
                                tokenSource);
                        return chain.filter(exchange.mutate().request(request).build());
                    } catch (Exception e) {
                        log.warn(
                                "[JWT] requestId={} method={} path={} tokenSource={} result=unauthorized reason=claims",
                                requestId,
                                method,
                                path,
                                tokenSource);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }
                };
        return new OrderedGatewayFilter(filter, JWT_FILTER_ORDER);
    }

    /** 쿠키에서 access_token 추출 */
    private String extractTokenFromCookie(ServerHttpRequest request) {
        HttpCookie accessTokenCookie = request.getCookies().getFirst("access_token");
        if (accessTokenCookie != null) {
            return accessTokenCookie.getValue();
        }
        return null;
    }

    /** 클라이언트가 보낸 X-Auth-* 헤더 제거 (보안상 중요: 클라이언트가 위조한 인증 헤더를 제거) */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(
                        headers -> {
                            headers.remove("X-Auth-User-Id");
                            headers.remove("X-Auth-Username");
                            headers.remove("X-Auth-Roles");
                            headers.remove("X-Auth-Token-Id");
                        })
                .build();
    }

    /** Secret Key 생성 */
    private SecretKey getSecretKey(Config config) {
        byte[] keyBytes = Base64.getDecoder().decode(config.getSecretKey());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** JWT 토큰 검증 */
    private Claims validateToken(String token, Config config) {
        try {
            return extractAllClaims(token, config);
        } catch (SignatureException e) {
            return null;
        } catch (MalformedJwtException e) {
            return null;
        } catch (ExpiredJwtException e) {
            return null;
        } catch (UnsupportedJwtException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 토큰에서 모든 Claims 추출 */
    private Claims extractAllClaims(String token, Config config) {
        return Jwts.parser()
                .verifyWith(getSecretKey(config))
                .requireIssuer(config.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Configuration class for JWT authentication filter */
    @Getter
    @Setter
    public static class Config {
        private String secretKey;
        private String issuer;
        private boolean required = true;
    }
}
