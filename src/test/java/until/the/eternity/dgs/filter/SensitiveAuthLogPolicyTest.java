package until.the.eternity.dgs.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveAuthLogPolicyTest {
    @Test
    void detectsAuthEntryPathsBeforeRouteResolutionIncludingUnmatchedPaths() {
        for (String path : new String[]{
                "/das/api/auth/login", "/das/missing", "/api/auth/login",
                "/login/oauth2/code/kakao", "/oauth2/authorization/kakao"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path + "?code=synthetic-secret"));
            assertThat(SensitiveAuthLogPolicy.isSensitive(exchange)).as(path).isTrue();
            assertThat(SensitiveAuthLogPolicy.routeId(exchange)).isEqualTo("UNKNOWN");
            assertThat(SensitiveAuthLogPolicy.safePath(exchange)).as(path).doesNotContain("synthetic-secret");
        }
        MockServerWebExchange matrix = MockServerWebExchange.from(
                MockServerHttpRequest.get("/das;code=matrix-secret/api/auth/login;proof=other-secret"));
        assertThat(SensitiveAuthLogPolicy.isSensitive(matrix)).isTrue();
        assertThat(SensitiveAuthLogPolicy.safePath(matrix)).isEqualTo("/das/**");
    }

    @Test
    void detectsAuthRouteAfterResolutionAndPreservesOtherRoutes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/unclassified-path?code=synthetic-secret"));
        assertThat(SensitiveAuthLogPolicy.isSensitive(exchange)).isFalse();

        Route authRoute = Route.async().id("devnogi-auth-server-public")
                .uri(URI.create("http://localhost:8091"))
                .predicate(serverWebExchange -> true).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, authRoute);
        assertThat(SensitiveAuthLogPolicy.isSensitive(exchange)).isTrue();
        assertThat(SensitiveAuthLogPolicy.safePath(exchange)).isEqualTo("auth-route");

        Route communityRoute = Route.async().id("devnogi-community-server")
                .uri(URI.create("http://localhost:8093"))
                .predicate(serverWebExchange -> true).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, communityRoute);
        assertThat(SensitiveAuthLogPolicy.isSensitive(exchange)).isFalse();
    }
}
