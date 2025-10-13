package until.the.eternity.dgs.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger RES_LOG = LoggerFactory.getLogger("gateway.response");
    private final ModifyResponseBodyGatewayFilterFactory modifyResponseBodyGatewayFilterFactory;

    private static void logResponse(
            final ServerHttpRequest request,
            final ServerHttpResponse response,
            final String body
    ) {

        RES_LOG.info("Response Id: {}, URI: {}, StatusCode: {}, Headers: {}, body: {}",
                request.getId(),
                request.getURI(),
                response.getStatusCode(),
                response.getHeaders(),
                body);
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {

        return modifyResponseBodyGatewayFilterFactory
                .apply(modifyResponseGatewayFilterConfig())
                .filter(exchange, chain);
    }

    private ModifyResponseBodyGatewayFilterFactory.Config modifyResponseGatewayFilterConfig() {

        return new ModifyResponseBodyGatewayFilterFactory.Config()
                .setRewriteFunction(String.class, String.class, (exchange, body) -> {
                            logResponse(exchange.getRequest(), exchange.getResponse(), body);
                            return Mono.justOrEmpty(body);
                        }
                );
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE;
    }
}
