package com.discordassistant.central.dashboard

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Spring REST Docs(차수 18): 테스트가 통과해야 REST 스니펫이 생성된다(build/generated-snippets/metrics-pool/).
 * springdoc(OpenAPI) = 계약 원장, REST Docs = 테스트 기반 검증된 예시 문서 — 상호 보완.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
class MetricsApiDocsTest
    @Autowired
    constructor(
        val mockMvc: MockMvc,
    ) {
        @Test
        fun `pool 메트릭 API 응답 계약 문서화`() {
            mockMvc
                .perform(get("/api/metrics/pool"))
                .andExpect(status().isOk())
                .andDo(
                    document(
                        "metrics-pool",
                        responseFields(
                            fieldWithPath("activeProviders").description("활성 프로바이더 수"),
                            fieldWithPath("inFlightTotal").description("처리중 요청 합계"),
                            subsectionWithPath("guildPoolSizes").description("길드별 풀 크기(길드ID→인원)"),
                        ),
                    ),
                )
        }
    }
