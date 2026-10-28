package until.the.eternity.dgs.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    // Spring binds these properties and Lombok generates their accessors.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private String secretKey;

    @SuppressWarnings("PMD.UnusedPrivateField")
    private String issuer;
}
