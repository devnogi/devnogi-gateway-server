package until.the.eternity.dgs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "JWT_SECRET_KEY=c3ludGhldGljLXRlc3Qta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXM=",
            "JWT_ISSUER=synthetic-issuer"
        })
class DgsApplicationTests {

    @Test
    void contextLoads() {}
}
