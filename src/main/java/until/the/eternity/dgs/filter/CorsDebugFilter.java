package until.the.eternity.dgs.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class CorsDebugFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String path = request.getURI().getPath();
        String origin = request.getHeaders().getOrigin();

        log.info("[CORS-DEBUG] ========== CORS Debug Start ==========");
        log.info("[CORS-DEBUG] Request: {} {}", method, path);
        log.info("[CORS-DEBUG] Origin: {}", origin);
        log.info("[CORS-DEBUG] Host: {}", request.getHeaders().getHost());

        // Preflight 요청인지 확인
        if (HttpMethod.OPTIONS.matches(method)) {
            String accessControlRequestMethod = request.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            String accessControlRequestHeaders = request.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            log.info("[CORS-DEBUG] PREFLIGHT request detected!");
            log.info("[CORS-DEBUG] Access-Control-Request-Method: {}", accessControlRequestMethod);
            log.info("[CORS-DEBUG] Access-Control-Request-Headers: {}", accessControlRequestHeaders);
        }

        // 쿠키 관련 헤더 확인
        String cookie = request.getHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookie != null) {
            log.info("[CORS-DEBUG] Cookie header present, length: {}", cookie.length());
            // 쿠키 이름만 로깅 (값은 민감정보)
            String[] cookies = cookie.split(";");
            for (String c : cookies) {
                String cookieName = c.trim().split("=")[0];
                log.info("[CORS-DEBUG] Cookie name: {}", cookieName);
            }
        } else {
            log.info("[CORS-DEBUG] No Cookie header");
        }

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            HttpHeaders responseHeaders = response.getHeaders();

            log.info("[CORS-DEBUG] Response Status: {}", response.getStatusCode());
            log.info("[CORS-DEBUG] Access-Control-Allow-Origin: {}",
                responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            log.info("[CORS-DEBUG] Access-Control-Allow-Credentials: {}",
                responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
            log.info("[CORS-DEBUG] Access-Control-Allow-Methods: {}",
                responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
            log.info("[CORS-DEBUG] Access-Control-Allow-Headers: {}",
                responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
            log.info("[CORS-DEBUG] ========== CORS Debug End ==========");
        }));
    }

    @Override
    public int getOrder() {
        // CORS 필터보다 먼저 실행되도록 최상위 우선순위
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
