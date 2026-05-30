import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0" // @Entity all-open + no-arg 생성자
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" // 정적 분석/포맷(차수 7 #76)
    id("org.jetbrains.kotlinx.kover") version "0.9.1" // Kotlin 커버리지(차수 18, JaCoCo 대체)
}

ktlint {
    version.set("1.4.1") // Kotlin 2.1 호환 ktlint
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("build/") }
    }
}

group = "com.discordassistant"
version = "0.1.0"
description = "커뮤니티 로컬 AI Provider Pool 중앙 서버 (ADR 0003/0004)"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot 코어
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket") // 에이전트 WS 릴레이
    // 대시보드 관리자 인증(차수 14 #196/#197). 기본 비활성(permitAll); central.oauth.enabled 로 OAuth2 활성.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // 운영 헬스/메트릭
    implementation("io.micrometer:micrometer-registry-prometheus") // /actuator/prometheus
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // API 계약 원장(차수 18): OpenAPI 3 자동 생성 + Swagger UI. springdoc 2.7 = Spring Boot 3.4 호환.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    // 분산 rate limit(차수 16 #242). 기본 인메모리; central.ratelimit.redis-enabled 시 Redis 백엔드.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 영속화 (JPA + Flyway). H2(dev/test), Postgres(prod)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Discord (ADR 0004: Kotlin 이 Discord 직접 처리)
    implementation("net.dv8tion:JDA:5.2.1")
    // 에러 트래킹(차수 15 #223). DSN 미설정 시 no-op. 운영에서 SENTRY_DSN 로 활성.
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.14.0")
    // 분산 추적(차수 15 #219). 기본 샘플링 0(미수집). OTLP 엔드포인트 설정 시 export.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Testcontainers(차수 17 #261, 차수 18 BDD) — Docker 필요. integration-docker 태그로 게이트.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // BDD(차수 18): Cucumber + Spring + JUnit Platform 엔진. 핵심 흐름 시나리오를 실제 컨텍스트로 실행.
    testImplementation(platform("io.cucumber:cucumber-bom:7.20.1"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-spring")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation("org.junit.platform:junit-platform-suite")
    // 아키텍처 규칙 보호(차수 18): 레이어 의존 방향/순환 의존 검증.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict") // Spring nullability 엄격 처리
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        // Docker 의존 통합 테스트(#261, @Tag)는 기본 제외. 실행: -PdockerTests
        if (!project.hasProperty("dockerTests")) {
            excludeTags("integration-docker")
        }
    }
    if (!project.hasProperty("dockerTests")) {
        // Cucumber BDD 스위트는 Testcontainers Postgres(실 DB) 필요 → 기본 빌드에서 클래스 단위 제외(실행: -PdockerTests).
        // (스위트는 태그 게이트가 자식 시나리오에 전파되지 않아 클래스 제외로 게이트한다.)
        exclude("**/RunCucumberBddTest.class")
    }
    // Testcontainers 가 Docker 소켓을 찾도록 호스트 환경의 DOCKER_HOST 를 테스트 JVM 에 전달(있을 때만).
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
}

// 커버리지(차수 18, JaCoCo→Kover). 라인 커버리지 기준. 부트스트랩/설정 클래스는 집계 제외.
// 게이트는 회귀 방지용 보수적 하한 — 핵심 흐름 테스트가 보강되면 90% 까지 함께 올린다(가짜 90% 금지).
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.discordassistant.central.CentralServerApplication",
                    "com.discordassistant.central.CentralServerApplicationKt",
                    "*Config",
                    "*Configuration",
                    // JDA 이벤트 어댑터: 슬래시 이벤트→CommandService 디스패치 글루. 로직은 CommandService 가 보유하며
                    // 단위 테스트 + BDD(Cucumber)로 커버한다. 어댑터 자체는 JDA 런타임 의존이라 커버리지 집계 제외.
                    "*.DiscordBot",
                    "*.DiscordBot\$*",
                    // dev 전용 엔드포인트(운영 CENTRAL_DEV_ENABLED=false 로 차단). 핵심 흐름 아님.
                    "*.DevController",
                    "*.DevController\$*",
                    // Redis 백엔드(분산 rate limit 옵트인 인프라) — 미사용 시 비활성, 인프라 의존.
                    "*RedisRateLimitStore",
                )
            }
        }
        total {
            html { onCheck = false }
            xml { onCheck = true }
        }
        verify {
            rule {
                // 핵심 로직 라인 커버리지 ≥ 90%(실측 90.7%). JDA 어댑터/dev/Redis 인프라 글루는 위 filters 로 제외.
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

// bootJar 만 산출(plain jar 비활성) + 고정 파일명(app.jar) — Dockerfile COPY 모호성 제거.
tasks.named<Jar>("jar") {
    enabled = false
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
