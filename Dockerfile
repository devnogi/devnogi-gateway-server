# ========================================
# Stage 1: Build
# ========================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Gradle wrapper 및 설정 파일 먼저 복사 (의존성 캐싱)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 실행 권한 부여
RUN chmod +x ./gradlew

# 의존성 다운로드 (캐싱 레이어)
RUN ./gradlew dependencies --no-daemon || true

# 소스 코드 복사
COPY src src

# 애플리케이션 빌드 (테스트 제외)
RUN ./gradlew bootJar --no-daemon -x test

# ========================================
# Stage 2: Runtime
# ========================================
FROM eclipse-temurin:21-jre-alpine

# curl 설치 (healthcheck용, wget보다 가벼움)
RUN apk add --no-cache curl

WORKDIR /app

# 빌드된 JAR 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 로그 디렉토리 생성
RUN mkdir -p /app/logs

# 비root 사용자로 실행 (보안)
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /app

USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JAVA_OPTS 환경변수를 통해 JVM 옵션 주입 가능
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
