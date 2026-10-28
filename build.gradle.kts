plugins {
	java
	pmd
	id("com.diffplug.spotless") version "6.25.0"
	id("org.springframework.boot") version "3.2.5" // 안정 버전
	id("io.spring.dependency-management") version "1.1.7"
}

group = "until.the.eternity"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

repositories {
	mavenCentral()
}

extra["springCloudVersion"] = "2023.0.1"

dependencies {
	// 기본
	implementation("org.springframework.boot:spring-boot-starter")

	// ✅ Gateway
	implementation("org.springframework.cloud:spring-cloud-starter-gateway")

	// LoadBalancer
	implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")

	// ✅ WebFlux 기반
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// ✅ JWT 인증용
	implementation("io.jsonwebtoken:jjwt-api:0.12.5")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

	// ✅ Actuator (모니터링 및 헬스 체크)
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// ✅ 테스트
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// 라우팅 테스트용 Mock Web Server 라이브러리
	testImplementation("com.squareup.okhttp3:mockwebserver")

	// ✅ Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

spotless {
	java {
		googleJavaFormat().aosp()
		removeUnusedImports()
		importOrder()
		trimTrailingWhitespace()
		endWithNewline()
	}
}

pmd {
	toolVersion = "7.24.0"
	ruleSets = emptyList()
	ruleSetFiles = files("config/pmd/unused.xml")
}

tasks.named("check") { dependsOn("spotlessCheck") }
tasks.register("lint") {
	group = "verification"
	description = "Check formatting, imports, and unused code."
	dependsOn("spotlessCheck", "pmdMain", "pmdTest")
}
tasks.register<Exec>("installGitHooks") {
	group = "build setup"
	description = "Install the repository-local Git hooks."
	commandLine("git", "config", "--local", "extensions.worktreeConfig", "true")
	doLast {
		exec { commandLine("git", "config", "--worktree", "core.hooksPath", ".githooks") }
	}
}

// Verification must not format sources or regenerate checked-in documentation.
tasks.register("verify") {
	group = "verification"
	description = "Run tests, configured static checks, and build the application JAR."
	dependsOn("check", "bootJar")
}
