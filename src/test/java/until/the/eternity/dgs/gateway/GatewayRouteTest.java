package until.the.eternity.dgs.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import until.the.eternity.dgs.filter.JwtAuthenticationGatewayFilterFactory;
import until.the.eternity.dgs.filter.RouteLoggingFilter;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GatewayRouteTest {
    private static final String TEST_ISSUER = "synthetic-issuer";
    private static final String TEST_SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            "synthetic-test-key-with-at-least-32-bytes"
                                    .getBytes(StandardCharsets.UTF_8));

    @Autowired private ApplicationContext context;

    private WebTestClient webTestClient;

    /* Mock Servers */
    private static MockWebServer mockAuthService;
    private static MockWebServer mockCommunityService;
    private static MockWebServer mockOpenApiBatchService;

    @BeforeEach
    void setUpClient() throws InterruptedException {
        while (mockCommunityService.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // 각 테스트는 자신이 전달한 Community 요청만 검증한다.
        }
        // responseTimeout을 10초로 설정 (기본값 5초, Retry 최대 시간이 5초를 초과함)
        this.webTestClient =
                WebTestClient.bindToApplicationContext(this.context)
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
        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(expectedResponseBody));

        // When: 게이트웨이로 요청 전송
        webTestClient
                .post()
                .uri("/api/auth/login")
                .bodyValue("{\"username\": \"test\"}")
                .exchange()
                // Then: 응답 검증
                .expectStatus()
                .isOk()
                .expectBody()
                .json(expectedResponseBody);

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
        int requestCountBefore = mockCommunityService.getRequestCount();
        // Given: mock CommunityService가 처음 3번은 502(BAD_GATEWAY)를, 마지막에 200(OK)을 응답하도록 설정
        // Retry(retries: 3)는 최초 1회 + 재시도 3회 = 총 4회 시도
        mockCommunityService.enqueue(
                new MockResponse().setResponseCode(502).setBody("Retryable Error 1"));
        mockCommunityService.enqueue(
                new MockResponse().setResponseCode(502).setBody("Retryable Error 2"));
        mockCommunityService.enqueue(
                new MockResponse().setResponseCode(502).setBody("Retryable Error 3"));
        mockCommunityService.enqueue(
                new MockResponse().setResponseCode(200).setBody("Finally successful response"));

        // When: 게이트웨이로 요청 전송
        webTestClient
                .get()
                .uri("/api/community/posts/1")
                .exchange()
                // Then: 응답 검증
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("Finally successful response");

        // Then: mock CommunityService가 총 4번의 요청을 받았는지 확인
        assertThat(mockCommunityService.getRequestCount() - requestCountBefore).isEqualTo(4);
    }

    @Test
    @DisplayName("운영 Community 공개 GET은 토큰 없이 전달하고 위조 내부 헤더를 제거한다")
    void publicCommunityGetPassesWithoutTokenAndStripsSpoofedHeaders() throws InterruptedException {
        mockCommunityService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"ok\":true}"));

        webTestClient
                .get()
                .uri("/dcs/api/posts")
                .header("X-Auth-User-Id", "999")
                .header("X-Auth-Username", "spoofed@example.test")
                .header("X-Auth-Roles", "ADMIN")
                .exchange()
                .expectStatus()
                .isOk();

        RecordedRequest forwarded = mockCommunityService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/posts");
        assertThat(forwarded.getHeader("X-Auth-User-Id")).isNull();
        assertThat(forwarded.getHeader("X-Auth-Username")).isNull();
        assertThat(forwarded.getHeader("X-Auth-Roles")).isNull();
    }

    @Test
    @DisplayName("운영 Community POST는 유효한 ACCESS 토큰을 내부 인증 헤더로 변환한다")
    void authenticatedCommunityPostInjectsTrustedContext() throws InterruptedException {
        String username = "community-user@example.test";
        String token = createToken("ACCESS", username);
        mockCommunityService.enqueue(
                new MockResponse()
                        .setResponseCode(201)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"id\":1}"));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .post()
                    .uri("/dcs/api/posts")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Auth-User-Id", "999")
                    .header("X-Auth-Username", "spoofed@example.test")
                    .header("X-Auth-Roles", "ADMIN")
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isCreated();

            RecordedRequest forwarded = mockCommunityService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getPath()).isEqualTo("/api/posts");
            assertThat(forwarded.getHeader("X-Auth-User-Id")).isEqualTo("42");
            assertThat(forwarded.getHeader("X-Auth-Username")).isEqualTo(username);
            assertThat(forwarded.getHeader("X-Auth-Roles")).isEqualTo("USER");
            assertThat(logs.output())
                    .contains("/dcs/**", "result=authenticated", "result=injected")
                    .doesNotContain(token, username, "spoofed@example.test");
        }
    }

    @Test
    @DisplayName("운영 Open API Batch 공개 GET은 토큰 없이 전달하고 위조 내부 헤더를 제거한다")
    void publicOpenApiBatchGetPassesWithoutTokenAndStripsSpoofedHeaders()
            throws InterruptedException {
        mockOpenApiBatchService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"ok\":true}"));

        webTestClient
                .get()
                .uri("/oab/api/item-infos")
                .header("X-Auth-User-Id", "999")
                .header("X-Auth-Username", "spoofed@example.test")
                .header("X-Auth-Roles", "ADMIN")
                .exchange()
                .expectStatus()
                .isOk();

        RecordedRequest forwarded = mockOpenApiBatchService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/item-infos");
        assertThat(forwarded.getHeader("X-Auth-User-Id")).isNull();
        assertThat(forwarded.getHeader("X-Auth-Username")).isNull();
        assertThat(forwarded.getHeader("X-Auth-Roles")).isNull();
    }

    @Test
    @DisplayName("운영 Open API Batch POST는 관리자 ACCESS 토큰을 내부 인증 헤더로 변환한다")
    void authenticatedOpenApiBatchPostInjectsAdminContext() throws InterruptedException {
        String username = "batch-admin@example.test";
        String token = createToken("ACCESS", username, "ADMIN");
        mockOpenApiBatchService.enqueue(new MockResponse().setResponseCode(200));

        webTestClient
                .post()
                .uri("/oab/api/item-infos/sync")
                .header("Authorization", "Bearer " + token)
                .header("X-Auth-User-Id", "999")
                .header("X-Auth-Username", "spoofed@example.test")
                .header("X-Auth-Roles", "USER")
                .exchange()
                .expectStatus()
                .isOk();

        RecordedRequest forwarded = mockOpenApiBatchService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/item-infos/sync");
        assertThat(forwarded.getHeader("X-Auth-User-Id")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Auth-Username")).isEqualTo(username);
        assertThat(forwarded.getHeader("X-Auth-Roles")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("운영 Community 라우트는 잘못된 토큰과 REFRESH 토큰을 거부한다")
    void communityRouteRejectsInvalidAndRefreshTokens() throws InterruptedException {
        webTestClient
                .get()
                .uri("/dcs/api/posts")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        webTestClient
                .get()
                .uri("/dcs/api/posts")
                .header("Authorization", "Bearer " + createToken("REFRESH", "refresh@example.test"))
                .exchange()
                .expectStatus()
                .isUnauthorized();

        webTestClient
                .get()
                .uri("/dcs/api/posts")
                .header("Authorization", "Basic malformed-credentials")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        assertThat(mockCommunityService.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
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
        String requestBody =
                "{\"password\":\""
                        + passwordSecret
                        + "\",\"padding\":\""
                        + "p".repeat(510)
                        + "\",\"signupProof\":\""
                        + proofSecret
                        + "\"}";
        String responseBody =
                "{\"email\":\""
                        + emailSecret
                        + "\",\"padding\":\""
                        + "r".repeat(1010)
                        + "\",\"signupProof\":\""
                        + proofSecret
                        + "\"}";

        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setHeader(
                                "Set-Cookie",
                                "oauth_flow_binding=" + responseCookieSecret + "; HttpOnly")
                        .setHeader(
                                "Location", "https://example.test/callback?code=" + locationSecret)
                        .setBody(responseBody));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .post()
                    .uri("/das/api/auth/login?code=" + querySecret)
                    .header("Authorization", "Bearer " + jwtSecret)
                    .header("Cookie", "oauth_flow_binding=" + cookieSecret)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .valueEquals(
                            "Set-Cookie",
                            "oauth_flow_binding=" + responseCookieSecret + "; HttpOnly")
                    .expectHeader()
                    .valueEquals("Location", "https://example.test/callback?code=" + locationSecret)
                    .expectBody(String.class)
                    .isEqualTo(responseBody);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getMethod()).isEqualTo("POST");
            assertThat(forwarded.getPath()).isEqualTo("/api/auth/login?code=" + querySecret);
            assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer " + jwtSecret);
            assertThat(forwarded.getHeader("Cookie")).contains(cookieSecret);
            assertThat(forwarded.getBody().readUtf8()).isEqualTo(requestBody);

            String output = logs.output();
            assertThat(output).contains("/das/**", "devnogi-auth-server", "400");
            assertThat(output)
                    .doesNotContain(
                            querySecret,
                            cookieSecret,
                            jwtSecret,
                            passwordSecret,
                            proofSecret,
                            emailSecret,
                            locationSecret,
                            responseCookieSecret);
            assertThat(output)
                    .doesNotContain(
                            "/das/api/auth/login?",
                            "Full URI:",
                            "Body:",
                            "Authorization:",
                            "Set-Cookie:");
            assertThat(logs.outputFor("gateway.request")).contains("Auth request", "/das/**");
            assertThat(logs.outputFor("gateway.response"))
                    .contains("Auth response", "/das/**", "400");
        }
    }

    @Test
    @DisplayName("기존 OAuth 콜백의 query와 응답 Location은 숨기고 그대로 전달한다")
    void legacyOAuthCallbackDoesNotLogSecrets() throws InterruptedException {
        String code = "legacy-code-SENTINEL-8675309";
        String location = "legacy-location-SENTINEL-13579";
        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "https://example.test/finish?data=" + location));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .get()
                    .uri("/login/oauth2/code/kakao?code=" + code)
                    .exchange()
                    .expectStatus()
                    .is3xxRedirection()
                    .expectHeader()
                    .valueEquals("Location", "https://example.test/finish?data=" + location);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getPath()).isEqualTo("/login/oauth2/code/kakao?code=" + code);
            String output = logs.output();
            assertThat(output).contains("/login/oauth2/code/**");
            assertThat(output).doesNotContain(code, location);
        }
    }

    @Test
    @DisplayName("운영 Auth 교환 경로는 code와 결합 쿠키를 전달하되 로그에서 숨긴다")
    void oauthExchangeUsesProtectedAuthRoute() throws InterruptedException {
        String code = "exchange-code-SENTINEL-102938";
        String binding = "exchange-binding-SENTINEL-475869";
        String proof = "exchange-proof-SENTINEL-564738";
        String requestBody = "{\"code\":\"" + code + "\"}";
        String responseBody = "{\"signupProof\":\"" + proof + "\"}";
        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(responseBody));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .post()
                    .uri("/das/api/auth/oauth/exchange")
                    .header("Cookie", "oauth_flow_binding=" + binding)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .isEqualTo(responseBody);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getMethod()).isEqualTo("POST");
            assertThat(forwarded.getPath()).isEqualTo("/api/auth/oauth/exchange");
            assertThat(forwarded.getHeader("Cookie")).contains(binding);
            assertThat(forwarded.getBody().readUtf8()).isEqualTo(requestBody);
            assertThat(logs.output())
                    .contains("/das/**", "devnogi-auth-server")
                    .doesNotContain(code, binding, proof);
            assertThat(logs.outputFor("gateway.request")).contains("Auth request", "/das/**");
            assertThat(logs.outputFor("gateway.response")).contains("Auth response", "/das/**");
        }
    }

    @Test
    @DisplayName("Auth multipart 가입 증명은 로그에서 숨기고 요청 본문은 그대로 전달한다")
    void socialSignupMultipartDoesNotLogProof() throws InterruptedException {
        String proof = "multipart-proof-SENTINEL-112358";
        String email = "multipart-email-SENTINEL-132134@example.test";
        String response = "{\"nickname\":\"generated-name\"}";
        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(response));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .post()
                    .uri("/das/api/auth/signup/social")
                    .body(
                            BodyInserters.fromMultipartData("signupProof", proof)
                                    .with("email", email))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .isEqualTo(response);

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getMethod()).isEqualTo("POST");
            assertThat(forwarded.getPath()).isEqualTo("/api/auth/signup/social");
            assertThat(forwarded.getHeader("Content-Type")).contains("multipart/form-data");
            assertThat(forwarded.getBody().readUtf8()).contains(proof, email);
            assertThat(logs.output())
                    .contains("/das/**", "devnogi-auth-server")
                    .doesNotContain(proof, email);
        }
    }

    @Test
    @DisplayName("256KB를 초과하는 Auth multipart 요청도 본문 손실 없이 전달한다")
    void largeSocialSignupMultipartIsForwardedWithoutBuffering() throws InterruptedException {
        byte[] profileImage = new byte[300 * 1024];
        ByteArrayResource imageResource =
                new ByteArrayResource(profileImage) {
                    @Override
                    public String getFilename() {
                        return "profile.png";
                    }
                };
        String response = "{\"id\":1}";
        mockAuthService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(response));

        webTestClient
                .post()
                .uri("/das/api/auth/signup/social")
                .body(
                        BodyInserters.fromMultipartData("signupProof", "synthetic-proof")
                                .with("file", imageResource))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo(response);

        RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/auth/signup/social");
        assertThat(forwarded.getHeader("Content-Type")).contains("multipart/form-data");
        assertThat(forwarded.getBodySize()).isGreaterThan(profileImage.length);
    }

    @Test
    @DisplayName("256KB를 초과하는 일반 multipart 요청도 본문 손실 없이 전달한다")
    void largeNonAuthMultipartIsForwardedWithoutBuffering() throws InterruptedException {
        byte[] attachment = new byte[300 * 1024];
        ByteArrayResource attachmentResource =
                new ByteArrayResource(attachment) {
                    @Override
                    public String getFilename() {
                        return "attachment.png";
                    }
                };
        String response = "{\"id\":1}";
        mockOpenApiBatchService.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(response));

        webTestClient
                .post()
                .uri("/api/statics/upload")
                .body(BodyInserters.fromMultipartData("file", attachmentResource))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo(response);

        RecordedRequest forwarded = mockOpenApiBatchService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/upload");
        assertThat(forwarded.getHeader("Content-Type")).contains("multipart/form-data");
        assertThat(forwarded.getBodySize()).isGreaterThan(attachment.length);
    }

    @Test
    @DisplayName("실제 /das 라우트의 경로 세그먼트와 matrix 비밀값은 로그에서 숨기고 전달한다")
    void authPathSecretsAreNotLoggedButForwarded() throws InterruptedException {
        String pathSecret = "path-code-SENTINEL-987654";
        String matrixSecret = "matrix-proof-SENTINEL-456789";
        String querySecret = "query-code-SENTINEL-654321";
        String requestPath = "/das/api/auth/login/" + pathSecret + ";signupProof=" + matrixSecret;
        String downstreamPath =
                "/api/auth/login/"
                        + pathSecret
                        + ";signupProof="
                        + matrixSecret
                        + "?code="
                        + querySecret;
        mockAuthService.enqueue(new MockResponse().setResponseCode(200).setBody("forwarded"));

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            webTestClient
                    .get()
                    .uri(requestPath + "?code=" + querySecret)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .isEqualTo("forwarded");

            RecordedRequest forwarded = mockAuthService.takeRequest(1, TimeUnit.SECONDS);
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getPath()).isEqualTo(downstreamPath);
            assertThat(logs.output())
                    .contains("/das/**", "devnogi-auth-server")
                    .doesNotContain(
                            pathSecret,
                            matrixSecret,
                            querySecret,
                            pathSecret.substring(0, 16),
                            matrixSecret.substring(0, 16));
        }
    }

    @Test
    @DisplayName("Auth JWT 검증 오류는 토큰과 matrix 경로를 로그에 남기지 않는다")
    void invalidAuthJwtDoesNotLeakThroughErrorLog() {
        String pathSecret = "jwt-path-SENTINEL-765432";
        String tokenSecret = "jwt-header-SENTINEL-234567";
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/das/api/user/info;code=" + pathSecret)
                                .header("Authorization", "Bearer " + tokenSecret));
        JwtAuthenticationGatewayFilterFactory.Config config =
                new JwtAuthenticationGatewayFilterFactory.Config();
        config.setSecretKey(TEST_SECRET);
        config.setIssuer(TEST_ISSUER);

        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            new JwtAuthenticationGatewayFilterFactory()
                    .apply(config)
                    .filter(
                            exchange,
                            ignored -> Mono.error(new AssertionError("invalid token passed")))
                    .block();
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
            assertThat(logs.output())
                    .contains("/das/**")
                    .doesNotContain(
                            pathSecret,
                            tokenSecret,
                            pathSecret.substring(0, 12),
                            tokenSecret.substring(0, 12));
        }
    }

    @Test
    @DisplayName("JWT secret 설정 오류는 route 초기화 시 명시적으로 실패한다")
    void invalidJwtSecretFailsRouteInitialization() {
        JwtAuthenticationGatewayFilterFactory.Config config =
                new JwtAuthenticationGatewayFilterFactory.Config();
        config.setSecretKey("not-base64");
        config.setIssuer(TEST_ISSUER);

        assertThatThrownBy(() -> new JwtAuthenticationGatewayFilterFactory().apply(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT secret key configuration is invalid");
    }

    @Test
    @DisplayName("JWT issuer 누락은 route 초기화 시 명시적으로 실패한다")
    void missingJwtIssuerFailsRouteInitialization() {
        JwtAuthenticationGatewayFilterFactory.Config config =
                new JwtAuthenticationGatewayFilterFactory.Config();
        config.setSecretKey(TEST_SECRET);
        config.setIssuer(" ");

        assertThatThrownBy(() -> new JwtAuthenticationGatewayFilterFactory().apply(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT issuer is required");
    }

    @Test
    @DisplayName("비인증 경로의 기존 상세 라우팅 로그를 유지한다")
    void nonAuthRouteRetainsDetailedLog() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/oab/items?name=ordinary-item"));
        try (CapturedGatewayLogs logs = new CapturedGatewayLogs()) {
            new RouteLoggingFilter().filter(exchange, ignored -> Mono.empty()).block();
            assertThat(logs.output()).contains("Full URI: /oab/items?name=ordinary-item");
        }
    }

    private static String createToken(String type, String subject) {
        return createToken(type, subject, "USER");
    }

    private static String createToken(String type, String subject, String role) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(TEST_ISSUER)
                .subject(subject)
                .claim("userId", 42L)
                .claim("role", role)
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + Duration.ofMinutes(5).toMillis()))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET)))
                .compact();
    }

    private static final class CapturedGatewayLogs implements AutoCloseable {
        private final List<Logger> loggers =
                List.of(
                        (Logger) LoggerFactory.getLogger("gateway.request"),
                        (Logger) LoggerFactory.getLogger("gateway.response"),
                        (Logger) LoggerFactory.getLogger("gateway.route"),
                        (Logger)
                                LoggerFactory.getLogger(
                                        "until.the.eternity.dgs.filter.UserContextFilter"),
                        (Logger)
                                LoggerFactory.getLogger(
                                        "until.the.eternity.dgs.filter.CorsDebugFilter"),
                        (Logger)
                                LoggerFactory.getLogger(
                                        "until.the.eternity.dgs.filter.JwtAuthenticationGatewayFilterFactory"));
        private final List<Level> previousLevels = loggers.stream().map(Logger::getLevel).toList();
        private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedGatewayLogs() {
            appender.start();
            loggers.forEach(
                    logger -> {
                        logger.setLevel(Level.DEBUG);
                    });
            rootLogger.addAppender(appender);
            loggers.get(0).addAppender(appender);
            loggers.get(1).addAppender(appender);
        }

        private String output() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        private String outputFor(String loggerName) {
            return appender.list.stream()
                    .filter(event -> event.getLoggerName().equals(loggerName))
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        @Override
        public void close() {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            rootLogger.detachAppender(appender);
            loggers.get(0).detachAppender(appender);
            loggers.get(1).detachAppender(appender);
            appender.stop();
        }
    }
}
