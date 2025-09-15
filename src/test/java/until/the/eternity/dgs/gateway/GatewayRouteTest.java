package until.the.eternity.dgs.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
}
