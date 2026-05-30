import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0" // @Entity all-open + no-arg 생성자
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.discordassistant"
version = "0.1.0-SNAPSHOT"
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
    implementation("org.springframework.boot:spring-boot-starter-actuator")  // 운영 헬스/메트릭
    implementation("io.micrometer:micrometer-registry-prometheus")           // /actuator/prometheus
    implementation("org.springframework.boot:spring-boot-starter-validation")
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
    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict") // Spring nullability 엄격 처리
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// bootJar 만 산출(plain jar 비활성) + 고정 파일명(app.jar) — Dockerfile COPY 모호성 제거.
tasks.named<Jar>("jar") {
    enabled = false
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
