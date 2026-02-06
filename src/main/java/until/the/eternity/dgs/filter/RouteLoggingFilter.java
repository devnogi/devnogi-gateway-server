package until.the.eternity.dgs.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 라우팅 결정 시점에 라우팅 정보를 로깅하는 필터
 * 요청이 어떤 백엔드 서비스로 라우팅되는지 명확하게 보여줍니다
 */
@Component
public class RouteLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger ROUTE_LOG = LoggerFactory.getLogger("gateway.route");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 라우팅 전 로깅
        logPreRoute(request);

        return chain.filter(exchange)
                .doOnSubscribe(subscription -> {
                    // 라우팅 결정 후 로깅
                    logPostRoute(exchange, request);
                });
    }

    private void logPreRoute(ServerHttpRequest request) {
        ROUTE_LOG.info("╔══════════════════════════════════════════════════════════════╗");
        ROUTE_LOG.info("║ INCOMING REQUEST                                             ║");
        ROUTE_LOG.info("╠══════════════════════════════════════════════════════════════╣");
        ROUTE_LOG.info("║ Method: {} | Path: {}",
                padRight(request.getMethod().name(), 6),
                request.getPath().value());
        ROUTE_LOG.info("║ Full URI: {}", request.getURI());
    }

    private void logPostRoute(ServerWebExchange exchange, ServerHttpRequest request) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        URI routeUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

        ROUTE_LOG.info("╠══════════════════════════════════════════════════════════════╣");
        ROUTE_LOG.info("║ ROUTING DECISION                                             ║");
        ROUTE_LOG.info("╠══════════════════════════════════════════════════════════════╣");

        if (route != null) {
            String routeId = route.getId();
            String targetService = getServiceName(routeId);

            ROUTE_LOG.info("║ Route ID: {}", routeId);
            ROUTE_LOG.info("║ Target Service: {}", targetService);
            ROUTE_LOG.info("║ Target Base URI: {}", route.getUri());

            if (routeUri != null) {
                ROUTE_LOG.info("║ Full Target URL: {}", routeUri);
            }

            // 경로 변환 정보 (StripPrefix 등)
            String originalPath = request.getPath().value();
            String prefix = getRoutePrefix(routeId);
            if (prefix != null && originalPath.startsWith(prefix)) {
                String strippedPath = originalPath.substring(prefix.length());
                if (strippedPath.isEmpty()) strippedPath = "/";
                ROUTE_LOG.info("║ Path Transform: {} → {}", originalPath, strippedPath);
            }

            ROUTE_LOG.info("╠══════════════════════════════════════════════════════════════╣");
            ROUTE_LOG.info("║ {} {} → {} {}",
                    request.getMethod().name(),
                    padRight(originalPath, 20),
                    targetService,
                    routeUri != null ? routeUri.getPath() : "");
        } else {
            ROUTE_LOG.warn("║ ⚠️ NO ROUTE MATCHED for path: {}", request.getPath().value());
        }

        ROUTE_LOG.info("╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * Route ID에서 서비스 이름 추출
     */
    private String getServiceName(String routeId) {
        return switch (routeId) {
            case "devnogi-auth-server" -> "Auth Server (인증/인가)";
            case "open-api-batch-server" -> "Open API Batch Server (경매 데이터)";
            case "devnogi-community-server" -> "Community Server (커뮤니티)";
            default -> routeId;
        };
    }

    /**
     * Route ID에서 prefix 추출
     */
    private String getRoutePrefix(String routeId) {
        return switch (routeId) {
            case "devnogi-auth-server" -> "/das";
            case "open-api-batch-server" -> "/oab";
            case "devnogi-community-server" -> "/dcs";
            default -> null;
        };
    }

    private String padRight(String s, int length) {
        if (s == null) s = "";
        return String.format("%-" + length + "s", s);
    }

    @Override
    public int getOrder() {
        // RequestLoggingFilter 이후, 실제 라우팅 전에 실행
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
