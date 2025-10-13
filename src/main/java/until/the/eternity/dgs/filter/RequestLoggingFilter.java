package until.the.eternity.dgs.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger REQ_LOG = LoggerFactory.getLogger("gateway.request");
    private final ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilterFactory;

    private static void logRequest(final ServerHttpRequest request, final String body) {

        REQ_LOG.info("Request Id: {}, URI: {}, Headers: {}, QueryParams: {}, Body: {}",
                request.getId(),
                request.getURI(),
                request.getHeaders(),
                request.getQueryParams(),
                body);
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
                            logRequest(exchange.getRequest(), body);
                            return Mono.justOrEmpty(body);
                        }
                );
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE;
    }
}
