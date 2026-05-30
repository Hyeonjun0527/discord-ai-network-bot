package com.discordassistant.central.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * API 계약 원장(차수 18): springdoc-openapi 가 OpenAPI 3 문서를 노출하는지 스모크 검증.
 * requirements.yaml = 제품 요구사항, Cucumber = 사용자 시나리오, OpenAPI = API 계약 원장.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest
    @Autowired
    constructor(
        val rest: TestRestTemplate,
    ) {
        @Test
        fun `OpenAPI 3 문서가 노출된다`() {
            val res = rest.getForEntity("/v3/api-docs", String::class.java)
            assertTrue(res.statusCode == HttpStatus.OK, "status=${res.statusCode}")
            val body = res.body ?: ""
            assertTrue(body.contains("\"openapi\""), "openapi 필드 없음: ${body.take(120)}")
            assertTrue(body.contains("\"paths\""), "paths 필드 없음")
        }
    }
