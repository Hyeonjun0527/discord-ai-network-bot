package com.discordassistant.central.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 웹 문구 i18n 생성본(`/i18n/web.json`)이 공개 서빙되고 ko/en/ja 를 담는지 검증. 설치 랜딩이 이걸
 * fetch 해 다국어 적용한다(SSOT 는 저장소 루트 i18n/messages.json, gen_i18n.py 가 생성).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebI18nServingTest
    @Autowired
    constructor(
        val mvc: MockMvc,
    ) {
        @Test
        fun `web i18n json 이 서빙되고 ko en ja 를 포함한다`() {
            val json =
                mvc
                    .perform(get("/i18n/web.json"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            // 헤드라인 키가 세 언어로 존재(일본어 포함)
            assertTrue(json.contains("heroHeadline"), json)
            assertTrue(json.contains("AIメンバー"), "일본어 헤드라인 누락") // ja
            assertTrue(json.contains("AI member"), "영어 헤드라인 누락") // en
            assertTrue(json.contains("AI 멤버"), "한국어 헤드라인 누락") // ko
        }
    }
