package until.the.eternity.dgs.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import javax.crypto.SecretKey;

@Component
public class JwtAuthenticationGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtAuthenticationGatewayFilterFactory.Config> {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationGatewayFilterFactory.class);

    public JwtAuthenticationGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();
            logger.info("path: {}", path);

            String token = extractToken(exchange);

            if (token == null || !validateToken(token, config)) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
    }

    private String extractToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.replace("Bearer ", "");
        }
        return null;
    }

    private boolean validateToken(String token, Config config) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(config.getSecretKey().getBytes());
            Jws<Claims> claimsJws = Jwts
                    .parserBuilder()
                    .requireIssuer("hyeongtaek")
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);

            logger.info("claimsJws = {}", claimsJws.getBody().toString());

            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static class Config {

        public String getSecretKey() {
            return "secret key 1243kljasw;ldkrfjl;asdkdfj;saldkfj ";
        }
    }
}
