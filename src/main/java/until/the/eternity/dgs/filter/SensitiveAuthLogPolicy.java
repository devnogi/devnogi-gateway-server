package until.the.eternity.dgs.filter;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;

/** Identifies authentication traffic before and after Gateway resolves its route. */
final class SensitiveAuthLogPolicy {
    private SensitiveAuthLogPolicy() {
    }

    static boolean isSensitive(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (hasPathPrefix(path, "/das")
                || hasPathPrefix(path, "/api/auth")
                || hasPathPrefix(path, "/login/oauth2/code")
                || hasPathPrefix(path, "/oauth2/authorization")) {
            return true;
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null && route.getId().startsWith("devnogi-auth-server");
    }

    static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "UNKNOWN";
    }

    private static boolean hasPathPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
