package until.the.eternity.dgs.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import until.the.eternity.dgs.filter.RouteLoggingFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GatewayRouteTest {
    @Autowired
    private ApplicationContext context;

    private WebTestClient webTestClient;

    /* Mock Servers */
    private static MockWebServer mockAuthService;
    private static MockWebServer mockCommunityService;
    private static MockWebServer mockOpenApiBatchService;

    @BeforeEach
    void setUpClient() {
        // responseTimeout을 10초로 설정 (기본값 5초, Retry 최대 시간이 5초를 초과함)
        this.webTestClient = WebTestClient
                .bindToApplicationContext(this.context)
                .configureClient()
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // Set up MockWebServers
    @BeforeAll
    static void setUp() throws IOException {
        mockAuthService = new MockWebServer();
        mockAuthService.start(8081);

        mockCommunityService = new MockWebServer();
        mockCommunityService.start(8082);

        mockOpenApiBatchService = new MockWebServer();
        mockOpenApiBatchService.start(8083);
    }

    // 테스트 클래스 종료 후, 모든 MockWebServer를 종료합니다.
    @AfterAll
    static void tearDown() throws IOException {
        mockAuthService.shutdown();
        mockCommunityService.shutdown();
        mockOpenApiBatchService.shutdown();
    }

    @Test
    @DisplayName("Auth 서버 라우팅 시 StripPrefix 필터가 정상 동작해야 한다")
    void authServerRoutingAndStripPrefixFilterTest() throws InterruptedException {
        // Given: mock AuthService가 반환할 응답 세팅
        String expectedResponseBody = "{\"token\": \"mock-jwt-token\"}";
        mockAuthService.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(expectedResponseBody));

        // When: 게이트웨이로 요청 전송
        webTestClient.post().uri("/api/auth/login")
                .bodyValue("{\"username\": \"test\"}")
                .exchange()
                // Then: 응답 검증
                .expectStatus().isOk()
                .expectBody().json(expectedResponseBody);

        // Then: mock AuthService가 받은 요청 검증
        RecordedRequest recordedRequest = mockAuthService.takeRequest(1, TimeUnit.SECONDS);

        assertThat(recordedRequest).isNotNull();
        // 1. 요청 메소드가 POST인지 확인
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        // 2. StripPrefix=2 필터에 의해 경로가 "/api/auth"가 제거된 "/login" 인지 확인
        assertThat(recordedRequest.getPath()).isEqualTo("/login");
        // 3. 요청 본문이 그대로 전달되었는지 확인
        assertThat(recordedRequest.getBody().readUtf8()).contains("test");
    }

    @Test
    @DisplayName("Default Filter (Retry)가 BAD_GATEWAY 상태코드에서 3번 재시도해야 한다")
    void defaultRetryFilterTest() throws InterruptedException {
        // Given: mock CommunityService가 처음 3번은 502(BAD_GATEWAY)를, 마지막에 200(OK)을 응답하도록 설정
        // Retry(retries: 3)는 최초 1회 + 재시도 3회 = 총 4회 시도
        mockCommunityService.enqueue(new MockResponse().setResponseCode(502).setBody("Retryable Error 1"));
        mockCommunityService.enqueue(new MockResponse().setResponseCode(502).setBody("Retryable Error 2"));
        mockCommunityService.enqueue(new MockResponse().setResponseCode(502).setBody("Retryable Error 3"));
        mockCommunityService.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("Finally successful response"));

        // When: 게이트웨이로 요청 전송
        webTestClient.get().uri("/api/community/posts/1")
                .exchange()
                // Then: 응답 검증
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Finally successful response");

        // Then: mock CommunityService가 총 4번의 요청을 받았는지 확인
        assertThat(mockCommunityService.getRequestCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("Auth JSON 요청과 오류 응답의 비밀값은 로그에서 숨기고 그대로 전달한다")
    void authSecretsAreNotLoggedButForwarded() throws InterruptedException {
        String querySecret = "query-code-SENTINEL-314159";
        String cookieSecret = "oauth-binding-SENTINEL-271828";
        String jwtSecret = "jwt-SENTINEL-161803";
        String passwordSecret = "password-SENTINEL-141421";
        String proofSecret = "signup-proof-SENTINEL-173205";
        String emailSecret = "email-SENTINEL-223606@example.test";
        String locationSecret = "location-SENTINEL-244948";
        String responseCookieSecret = "set-cookie-SENTINEL-264575";
        String requestBody = "{\"password\":\"" + passwordSecret + "\",\"padding\":\""
                + "p".repeat(510) + "\",\"signupProof\":\"" + proofSecret + "\"}";
        String responseBody = "{\"email\":\"" + emailSecret + "\",\"padding\":\""
                + "r".repeat(1010) + "\",\"signupProof\":\"" + proofSecret + "\"}";

        mockAuthService.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "oauth_flow_binding=" + responseCookieSecret + "; HttpOnly")
                .setHeader("Location", "https://example.test/callback?code=" + locationSecret)
                .setBody(responseBody));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient.post().uri("/api/auth/login?code=" + querySecret)
                    .header("Authorization", "Bearer " + jwtSecret)
                    .header("Cookie", "oauth_flow_binding=" + cookieSecret)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().valueEquals("Set-Cookie", "oauth_flow_binding=" + responseCookieSecret + "; HttpOnly")
                    .expectHeader().valueEquals("Location", "https://example.test/callback?code=" + locationSecret)
                    .expectBody(String.class).isEqualTo(responseBody);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getMethod()).isEqualTo("POST");
            assertThat(forwarded.getPath()).isEqualTo("/login?code=" + querySecret);
            assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer " + jwtSecret);
            assertThat(forwarded.getHeader("Cookie")).contains(cookieSecret);
            assertThat(forwarded.getBody().readUtf8()).isEqualTo(requestBody);

            String output = logs.output();
            assertThat(output).contains("/api/auth/login", "devnogi-auth-server", "400");
            assertThat(output).doesNotContain(querySecret, cookieSecret, jwtSecret, passwordSecret,
                    proofSecret, emailSecret, locationSecret, responseCookieSecret);
            assertThat(output).doesNotContain("/api/auth/login?", "Full URI:", "Body:", "Authorization:", "Set-Cookie:");
        }
    }

    @Test
    @DisplayName("기존 OAuth 콜백의 query와 응답 Location은 숨기고 그대로 전달한다")
    void legacyOAuthCallbackDoesNotLogSecrets() throws InterruptedException {
        String code = "legacy-code-SENTINEL-8675309";
        String location = "legacy-location-SENTINEL-13579";
        mockAuthService.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "https://example.test/finish?data=" + location));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient.get().uri("/login/oauth2/code/kakao?code=" + code)
                    .exchange().expectStatus().is3xxRedirection()
                    .expectHeader().valueEquals("Location", "https://example.test/finish?data=" + location);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getPath()).isEqualTo("/login/oauth2/code/kakao?code=" + code);
            String output = logs.output();
            assertThat(output).contains("/login/oauth2/code/kakao");
            assertThat(output).doesNotContain(code, location);
        }
    }

    @Test
    @DisplayName("Auth multipart 가입 증명은 로그에서 숨기고 요청 본문은 그대로 전달한다")
    void socialSignupMultipartDoesNotLogProof() throws InterruptedException {
        String proof = "multipart-proof-SENTINEL-112358";
        String email = "multipart-email-SENTINEL-132134@example.test";
        String response = "{\"nickname\":\"generated-name\"}";
        mockAuthService.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(response));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient.post().uri("/api/auth/signup/social")
                    .body(BodyInserters.fromMultipartData("signupProof", proof).with("email", email))
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(response);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getMethod()).isEqualTo("POST");
            assertThat(forwarded.getPath()).isEqualTo("/signup/social");
            assertThat(forwarded.getHeader("Content-Type")).contains("multipart/form-data");
            assertThat(forwarded.getBody().readUtf8()).contains(proof, email);
            assertThat(logs.output()).contains("/api/auth/signup/social")
                    .doesNotContain(proof, email);
        }
    }

    @Test
    @DisplayName("비인증 경로의 기존 상세 라우팅 로그를 유지한다")
    void nonAuthRouteRetainsDetailedLog() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/oab/items?name=ordinary-item"));
        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            new RouteLoggingFilter().filter(exchange, ignored -> Mono.empty()).block();
            assertThat(logs.output()).contains("Full URI: /oab/items?name=ordinary-item");
        }
    }

    private static final class CapturedGatewayLogs implements AutoCloseable {
        private final List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger("gateway.request"),
                (Logger) LoggerFactory.getLogger("gateway.response"),
                (Logger) LoggerFactory.getLogger("gateway.route"));
        private final List<Level> previousLevels = loggers.stream().map(Logger::getLevel).toList();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedGatewayLogs() {
            appender.start();
            loggers.forEach(logger -> {
                logger.setLevel(Level.DEBUG);
                logger.addAppender(appender);
            });
        }

        private String output() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        @Override
        public void close() {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(appender);
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            appender.stop();
        }
    }
}
