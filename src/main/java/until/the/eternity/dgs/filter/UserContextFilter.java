package until.the.eternity.dgs.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
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
        // JwtAuthenticationFilter가 저장한 사용자 정보 읽기
        Long userId = exchange.getAttribute("userId");
        String username = exchange.getAttribute("username");
        String role = exchange.getAttribute("role");

        // 사용자 정보가 없으면 (공개 경로 등) 그대로 통과
        if (userId == null || username == null || role == null) {
            log.debug("No user context found, skipping header injection");
            return chain.filter(exchange);
        }

        // 내부 헤더 추가
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-Auth-User-Id", String.valueOf(userId))
                .header("X-Auth-Username", username)
                .header("X-Auth-Roles", role)
                .build();

        log.debug("Added user context headers - User-Id: {}, Username: {}, Roles: {}",
                userId, username, role);

        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * JwtAuthenticationFilter(-100) 다음에 실행되도록 우선순위 설정
     */
    @Override
    public int getOrder() {
        return -99;
    }
}
