package until.the.eternity.dgs.filter;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger REQ_LOG = LoggerFactory.getLogger("gateway.request");
    private final ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilterFactory;

    private static void logRequest(final ServerWebExchange exchange, final ServerHttpRequest request, final String body) {
        // 라우팅 정보 추출
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "UNKNOWN";
        String routeUri = route != null ? route.getUri().toString() : "UNKNOWN";

        REQ_LOG.info("========== GATEWAY REQUEST ==========");
        REQ_LOG.info("Request ID: {}", request.getId());
        REQ_LOG.info("Method: {}", request.getMethod());
        REQ_LOG.info("URI: {}", request.getURI());
        REQ_LOG.info("Path: {}", request.getPath().value());
        REQ_LOG.info("--- Routing Information ---");
        REQ_LOG.info("Route ID: {}", routeId);
        REQ_LOG.info("Route Target URI: {}", routeUri);

        // 쿼리 파라미터
        if (!request.getQueryParams().isEmpty()) {
            REQ_LOG.info("--- Query Parameters ---");
            request.getQueryParams().forEach((key, values) ->
                    REQ_LOG.info("  {} = {}", key, String.join(", ", values))
            );
        }

        // 인증 관련 헤더
        REQ_LOG.info("--- Authentication Headers ---");
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null) {
            // 토큰 마스킹 (앞 30자만 표시)
            String maskedAuth = authHeader.length() > 37
                    ? authHeader.substring(0, 37) + "...***"
                    : authHeader;
            REQ_LOG.info("Authorization: {}", maskedAuth);
        } else {
            REQ_LOG.info("Authorization: (empty)");
        }

        // 쿠키 정보
        REQ_LOG.info("--- Cookie Information ---");
        MultiValueMap<String, HttpCookie> cookies = request.getCookies();
        if (cookies.isEmpty()) {
            REQ_LOG.info("Cookies: (none)");
        } else {
            REQ_LOG.info("Cookie count: {}", cookies.size());
            cookies.forEach((name, cookieList) -> {
                for (HttpCookie cookie : cookieList) {
                    String value = cookie.getValue();
                    // 민감한 쿠키 마스킹
                    if (name.toLowerCase().contains("token") || name.toLowerCase().contains("session")) {
                        value = value.length() > 20
                                ? value.substring(0, 20) + "...***"
                                : value;
                    }
                    REQ_LOG.info("  {} = {}", name, value);
                }
            });
        }

        // 주요 헤더
        REQ_LOG.info("--- Key Headers ---");
        REQ_LOG.info("Content-Type: {}", request.getHeaders().getFirst("Content-Type"));
        REQ_LOG.info("Accept: {}", request.getHeaders().getFirst("Accept"));
        REQ_LOG.info("Origin: {}", request.getHeaders().getFirst("Origin"));
        REQ_LOG.info("Host: {}", request.getHeaders().getFirst("Host"));

        // 내부 인증 헤더 (UserContextFilter에서 설정)
        String xAuthUserId = request.getHeaders().getFirst("X-Auth-User-Id");
        String xAuthUsername = request.getHeaders().getFirst("X-Auth-Username");
        String xAuthRoles = request.getHeaders().getFirst("X-Auth-Roles");
        if (xAuthUserId != null || xAuthUsername != null || xAuthRoles != null) {
            REQ_LOG.info("--- Internal Auth Headers (from JWT) ---");
            REQ_LOG.info("X-Auth-User-Id: {}", xAuthUserId);
            REQ_LOG.info("X-Auth-Username: {}", xAuthUsername);
            REQ_LOG.info("X-Auth-Roles: {}", xAuthRoles);
        }

        // 요청 바디
        if (body != null && !body.isEmpty()) {
            REQ_LOG.info("--- Request Body ---");
            String truncatedBody = body.length() > 500
                    ? body.substring(0, 500) + "...(truncated)"
                    : body;
            REQ_LOG.info("Body: {}", truncatedBody);
        }

        // 전체 헤더 (DEBUG 레벨)
        REQ_LOG.debug("--- All Headers ---");
        request.getHeaders().forEach((key, values) ->
                REQ_LOG.debug("  {} = {}", key, String.join(", ", values))
        );

        REQ_LOG.info("======================================");
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {

        return modifyRequestBodyGatewayFilterFactory
                .apply(modifyRequestBodyGatewayFilterConfig())
                .filter(exchange, chain);
    }

    private ModifyRequestBodyGatewayFilterFactory.Config modifyRequestBodyGatewayFilterConfig() {

        return new ModifyRequestBodyGatewayFilterFactory.Config()
                .setRewriteFunction(String.class, String.class, (exchange, body) -> {
                            logRequest(exchange, exchange.getRequest(), body);
                            return Mono.justOrEmpty(body);
                        }
                );
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE;
    }
}
