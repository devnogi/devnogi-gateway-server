package until.the.eternity.dgs.filter;

import java.util.stream.Collectors;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;

/** Identifies authentication traffic before and after Gateway resolves its route. */
final class SensitiveAuthLogPolicy {
    private SensitiveAuthLogPolicy() {}

    static boolean isSensitive(ServerWebExchange exchange) {
        return safePath(exchange) != null;
    }

    static String safeRequestPath(ServerWebExchange exchange) {
        String protectedPath = safePath(exchange);
        return protectedPath != null ? protectedPath : normalizedPath(exchange);
    }

    static String safePath(ServerWebExchange exchange) {
        String path = normalizedPath(exchange);
        if (hasPathPrefix(path, "/das")) return "/das/**";
        if (hasPathPrefix(path, "/api/auth")) return "/api/auth/**";
        if (hasPathPrefix(path, "/dcs")) return "/dcs/**";
        if (hasPathPrefix(path, "/login/oauth2/code")) return "/login/oauth2/code/**";
        if (hasPathPrefix(path, "/oauth2/authorization")) return "/oauth2/authorization/**";

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null && route.getId().startsWith("devnogi-auth-server")
                ? "auth-route"
                : null;
    }

    private static String normalizedPath(ServerWebExchange exchange) {
        // PathSegment.valueToMatch excludes matrix parameters, which may contain credentials.
        return exchange.getRequest().getPath().elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .map(PathContainer.PathSegment::valueToMatch)
                .collect(Collectors.joining("/", "/", ""));
    }

    static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "UNKNOWN";
    }

    private static boolean hasPathPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
