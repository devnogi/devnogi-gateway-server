# Devnogi Gateway Server

Spring Cloud Gateway 기반의 API Gateway 서버입니다.

## 기술 스택

- **Java 21**
- **Spring Boot 3.2.5**
- **Spring Cloud Gateway 2023.0.1**
- **JWT (jjwt 0.12.5)**

## 주요 기능

### 라우팅

| Path | Service | 설명 |
|------|---------|------|
| `/das/**` | Auth Server | 인증/인가 (JWT 발급) |
| `/login/oauth2/code/**` | Auth Server | OAuth2 콜백 |
| `/oab/**` | Open API Batch Server | 경매 데이터 수집/분석 |
| `/dcs/**` | Community Server | 게시판/댓글/사용자 관리 |

### JWT 인증

- Authorization 헤더 (`Bearer {token}`) 또는 쿠키 (`access_token`)에서 토큰 추출
- ACCESS 토큰만 허용 (REFRESH 토큰은 인증 서버에서만 사용)
- 클라이언트가 보낸 `X-Auth-*` 헤더 자동 제거 (보안)

### CORS

허용 Origins:
- `https://www.memonogi.com`
- `https://memonogi.com`
- `http://localhost:*`

## 로컬 실행

### 환경변수 설정

`.env.example`을 참고하여 `.env.local` 파일을 생성합니다. 기존 파일이 있으면 덮어쓰지 말고 필요한 설정만 비교합니다.

```bash
cp .env.example .env.local
# .env.local 파일 수정
```

필수 환경변수:
- `JWT_SECRET_KEY`: JWT 서명 키 (Base64 인코딩)
- `JWT_ISSUER`: JWT 발급자
- `AUTH_SERVER_URL`: 인증 서버 URL
- `OPEN_API_BATCH_SERVER_URL`: 배치 서버 URL
- `COMMUNITY_SERVER_URL`: 커뮤니티 서버 URL

샘플 JWT 키는 auth의 로컬 예제와 같은 공개 합성 Base64 값입니다. 실제 공유·운영 환경에서 사용하지 않습니다. 로컬 이메일 로그인 설정과 요청 흐름은 [개발 안내](docs/development.md#로컬-이메일-로그인)를 참고하세요.

### Gradle로 실행

```bash
./gradlew bootRun
```

### Docker Compose로 실행

```bash
docker-compose -f docker-compose-local.yml up -d
```

## 배포

### CI/CD

GitHub Actions의 배포 workflow는 수동 실행과 환경 승인을 사용합니다. push와 PR 생성만으로는 배포하지 않습니다.

- **dev 브랜치**에서 `push-cd-dev.yml` 수동 실행 → development 환경
- **main 브랜치**에서 `push-cd-prod.yml` 수동 실행 → production 환경

### Docker 빌드

```bash
docker build -t devnogi-gateway-server .
```

## API 문서

### Health Check

```
GET /actuator/health
```

### Actuator 엔드포인트

모든 Actuator 엔드포인트가 활성화되어 있습니다.

```
GET /actuator
```

## 프로젝트 구조

```
src/main/java/until/the/eternity/dgs/
├── DgsApplication.java              # 메인 애플리케이션
├── config/
│   └── JwtProperties.java           # JWT 설정
├── filter/
│   ├── JwtAuthenticationGatewayFilterFactory.java  # JWT 인증 필터
│   ├── UserContextFilter.java       # 사용자 컨텍스트 필터
│   ├── RequestLoggingFilter.java    # 요청 로깅 필터
│   ├── ResponseLoggingFilter.java   # 응답 로깅 필터
│   └── CorsDebugFilter.java         # CORS 디버그 필터
└── util/
    └── JwtTokenProvider.java        # JWT 유틸리티
```
