package com.discordassistant.central.bdd

import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * Cucumber ↔ Spring 브리지(차수 18). 단일 @CucumberContextConfiguration 이 전 시나리오에 컨텍스트를 제공한다.
 * Testcontainers JDBC URL(jdbc:tc:...) 로 실제 Postgres 를 지연 기동 — 데이터소스가 처음 연결될 때(=컨텍스트 생성 시)에만
 * 컨테이너가 뜬다. 클래스 로딩 자체에는 부작용이 없어, Docker 없는 환경의 디스커버리 단계에서 컨테이너가 뜨지 않는다.
 */
@CucumberContextConfiguration
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///central",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.enabled=true",
    ],
)
class CucumberSpringConfiguration
