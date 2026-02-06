package until.the.eternity.dgs.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway에서 검증한 사용자 정보를 내부 헤더(X-Auth-*)로 변환하여
 * 다운스트림 마이크로서비스로 전달하는 필터
 */
@Slf4j
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest originalRequest = exchange.getRequest();
        String method = originalRequest.getMethod() != null ? originalRequest.getMethod().name() : "UNKNOWN";
        String path = originalRequest.getURI().getPath();

        // 라우팅 정보
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "UNKNOWN";

        // 원본 요청 헤더 로깅
        HttpHeaders originalHeaders = originalRequest.getHeaders();
        log.info("[USER-CONTEXT] ========== Incoming Request Headers ==========");
        log.info("[USER-CONTEXT] Request: {} {} → Route: {}", method, path, routeId);
        log.info("[USER-CONTEXT] --- Original Headers ---");
        originalHeaders.forEach((key, values) ->
                log.info("[USER-CONTEXT]   {} : {}", key, maskHeaderValue(key, String.join(", ", values)))
        );

        // JwtAuthenticationGatewayFilterFactory가 저장한 사용자 정보 읽기
        Long userId = exchange.getAttribute("userId");
        String username = exchange.getAttribute("username");
        String role = exchange.getAttribute("role");

        // 사용자 정보가 없으면 (공개 경로 등) 그대로 통과
        if (userId == null || username == null || role == null) {
            log.info("[USER-CONTEXT] No user context found (userId={}, username={}, role={}), skipping header injection",
                    userId, username, role);
            log.info("[USER-CONTEXT] =================================================");
            return chain.filter(exchange);
        }

        // 내부 헤더 추가
        ServerHttpRequest mutatedRequest = originalRequest.mutate()
                .header("X-Auth-User-Id", String.valueOf(userId))
                .header("X-Auth-Username", username)
                .header("X-Auth-Roles", role)
                .build();

        // 마이크로서비스로 전달될 커스텀 헤더 로깅
        log.info("[USER-CONTEXT] --- Custom Headers for Microservice ---");
        log.info("[USER-CONTEXT]   X-Auth-User-Id  : {}", userId);
        log.info("[USER-CONTEXT]   X-Auth-Username  : {}", username);
        log.info("[USER-CONTEXT]   X-Auth-Roles     : {}", role);

        // 최종 전달될 전체 헤더 로깅
        HttpHeaders finalHeaders = mutatedRequest.getHeaders();
        log.info("[USER-CONTEXT] --- Final Headers to Microservice ---");
        finalHeaders.forEach((key, values) ->
                log.info("[USER-CONTEXT]   {} : {}", key, maskHeaderValue(key, String.join(", ", values)))
        );
        log.info("[USER-CONTEXT] =================================================");

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 민감한 헤더 값 마스킹
     */
    private String maskHeaderValue(String headerName, String value) {
        if (value == null) return "(null)";
        String lowerName = headerName.toLowerCase();
        if (lowerName.equals("authorization") || lowerName.contains("token") || lowerName.contains("cookie")) {
            return value.length() > 30 ? value.substring(0, 30) + "...***" : value;
        }
        return value;
    }

    /**
     * JWT 필터 다음에 실행되도록 우선순위 설정
     * (GlobalFilter는 GatewayFilter보다 나중에 실행됨)
     */
    @Override
    public int getOrder() {
        return -99;
    }
}
