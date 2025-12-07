package until.the.eternity.dgs.filter;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import until.the.eternity.dgs.util.JwtTokenProvider;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 인증이 필요 없는 공개 경로 목록
     */
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/das/api/auth/login",
            "/das/api/auth/signup",
            "/das/api/auth/admin/signup",
            "/das/api/auth/check-email",
            "/das/api/auth/check-nickname",
            "/das/api/auth/signup/social",
            "/das/api/auth/logout",
            "/das/oauth2/",                    // 소셜 로그인 시작
            "/das/login/oauth2/",              // 소셜 로그인 콜백
            "/actuator",
            "/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        log.info("Processing request: {} {}, Path: '{}'", request.getMethod(), request.getURI(), path);

        // Step 1: 클라이언트가 보낸 X-Auth-* 헤더 제거 (보안상 중요!)
        request = stripInternalHeaders(request);

        // Step 2: 공개 경로는 인증 불필요
        if (isPublicPath(path)) {
            log.info("Public path detected, skipping authentication: {}", path);
            return chain.filter(exchange.mutate().request(request).build());
        }

        log.info("Not a public path, checking authentication for: {}", path);

        // Step 3: Authorization 헤더에서 JWT 토큰 추출
        String token = extractToken(request);
        if (token == null) {
            log.warn("No JWT token found in Authorization header for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Step 4: JWT 토큰 검증
        if (!jwtTokenProvider.validateToken(token)) {
            log.warn("Invalid JWT token for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Step 5: ACCESS 토큰만 허용 (REFRESH 토큰은 /auth/refresh에서만 사용)
        String tokenType = jwtTokenProvider.getTokenType(token);
        if (!"ACCESS".equals(tokenType)) {
            log.warn("Token type is not ACCESS: {}", tokenType);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Step 6: 사용자 정보 추출 및 ServerWebExchange에 저장 (다음 필터에서 사용)
        try {
            Long userId = jwtTokenProvider.getUserId(token);
            String username = jwtTokenProvider.getUsername(token);
            String role = jwtTokenProvider.getRole(token);

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
     * 공개 경로 확인
     */
    private boolean isPublicPath(String path) {
        boolean isPublic = PUBLIC_PATHS.stream().anyMatch(path::startsWith);
        log.debug("Checking if path '{}' is public: {}", path, isPublic);
        if (isPublic) {
            String matchedPath = PUBLIC_PATHS.stream()
                    .filter(path::startsWith)
                    .findFirst()
                    .orElse("unknown");
            log.debug("Path '{}' matched with public path: '{}'", path, matchedPath);
        }
        return isPublic;
    }

    /**
     * 필터 우선순위 (낮을수록 먼저 실행)
     * JwtAuthenticationFilter가 먼저 실행되고, 그 다음 UserContextFilter가 실행됨
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
