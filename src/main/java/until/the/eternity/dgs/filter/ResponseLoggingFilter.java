package until.the.eternity.dgs.filter;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ResponseLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger RES_LOG = LoggerFactory.getLogger("gateway.response");
    private final ModifyResponseBodyGatewayFilterFactory modifyResponseBodyGatewayFilterFactory;

    private static void logResponse(
            final ServerWebExchange exchange,
            final ServerHttpRequest request,
            final ServerHttpResponse response,
            final String body) {
        if (SensitiveAuthLogPolicy.isSensitive(exchange)) {
            RES_LOG.info(
                    "Auth response: method={}, path={}, route={}, status={}",
                    request.getMethod(),
                    SensitiveAuthLogPolicy.safeRequestPath(exchange),
                    SensitiveAuthLogPolicy.routeId(exchange),
                    response.getStatusCode());
            return;
        }

        // 라우팅 정보 추출
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "UNKNOWN";
        String routeUri = route != null ? route.getUri().toString() : "UNKNOWN";

        HttpStatusCode statusCode = response.getStatusCode();
        boolean isError =
                statusCode != null
                        && (statusCode.is4xxClientError() || statusCode.is5xxServerError());

        // 에러 응답은 ERROR 레벨로 로깅
        if (isError) {
            RES_LOG.error("========== GATEWAY RESPONSE (ERROR) ==========");
        } else {
            RES_LOG.info("========== GATEWAY RESPONSE ==========");
        }

        RES_LOG.info("Request ID: {}", request.getId());
        RES_LOG.info("Request URI: {}", request.getURI());
        RES_LOG.info("Request Path: {}", request.getPath().value());
        RES_LOG.info("--- Routing Information ---");
        RES_LOG.info("Route ID: {}", routeId);
        RES_LOG.info("Route Target URI: {}", routeUri);
        RES_LOG.info("--- Response Status ---");
        RES_LOG.info("Status Code: {}", statusCode);

        // 응답 헤더
        RES_LOG.info("--- Response Headers ---");
        RES_LOG.info("Content-Type: {}", response.getHeaders().getFirst("Content-Type"));
        RES_LOG.info("Content-Length: {}", response.getHeaders().getFirst("Content-Length"));

        // Set-Cookie 헤더 (쿠키 설정 확인)
        if (response.getHeaders().containsKey("Set-Cookie")) {
            RES_LOG.info(
                    "Set-Cookie headers: {}",
                    response.getHeaders().getOrEmpty("Set-Cookie").size());
        }

        // CORS 헤더
        String corsOrigin = response.getHeaders().getFirst("Access-Control-Allow-Origin");
        String corsCredentials = response.getHeaders().getFirst("Access-Control-Allow-Credentials");
        if (corsOrigin != null || corsCredentials != null) {
            RES_LOG.info("--- CORS Headers ---");
            RES_LOG.info("Access-Control-Allow-Origin: {}", corsOrigin);
            RES_LOG.info("Access-Control-Allow-Credentials: {}", corsCredentials);
        }

        // 응답 바디
        if (body != null && !body.isEmpty()) {
            RES_LOG.info("--- Response Body ---");
            String truncatedBody =
                    body.length() > 1000
                            ? body.substring(0, 1000)
                                    + "...(truncated, total: "
                                    + body.length()
                                    + " chars)"
                            : body;

            if (isError) {
                RES_LOG.error("Body: {}", truncatedBody);
            } else {
                RES_LOG.info("Body: {}", truncatedBody);
            }
        }

        RES_LOG.debug("Response header names: {}", response.getHeaders().keySet());

        if (isError) {
            RES_LOG.error("==========================================");
        } else {
            RES_LOG.info("======================================");
        }
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {

        return modifyResponseBodyGatewayFilterFactory
                .apply(modifyResponseGatewayFilterConfig())
                .filter(exchange, chain);
    }

    private ModifyResponseBodyGatewayFilterFactory.Config modifyResponseGatewayFilterConfig() {

        return new ModifyResponseBodyGatewayFilterFactory.Config()
                .setRewriteFunction(
                        String.class,
                        String.class,
                        (exchange, body) -> {
                            logResponse(
                                    exchange, exchange.getRequest(), exchange.getResponse(), body);
                            return Mono.justOrEmpty(body);
                        });
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE;
    }
}
