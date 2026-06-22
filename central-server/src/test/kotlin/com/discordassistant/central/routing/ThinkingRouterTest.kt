package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.routing.application.CloudThinkingOption
import com.discordassistant.central.routing.application.ThinkingRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** thinking 속도 라우터(규칙 기반·LLM 0) + 어드민 override 파서 순수 단위테스트. */
class ThinkingRouterTest {
    @Test
    fun `짧은 잡담은 기본 disabled(속도 우선)`() {
        assertEquals(CloudThinking.DISABLED, ThinkingRouter.route("안녕"))
        assertEquals(CloudThinking.DISABLED, ThinkingRouter.route("오늘 날씨 좋다"))
    }

    @Test
    fun `추론 키워드(왜·어떻게·계산·코드·비교 등)면 enabled`() {
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route("왜 하늘은 파랄까"))
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route("이거 어떻게 풀어"))
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route("123 곱하기 456 계산해줘"))
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route("이 코드 버그 찾아줘"))
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route("how do I solve this"))
    }

    @Test
    fun `길이 임계 초과면 enabled`() {
        val long = "ㄱ".repeat(200)
        assertEquals(CloudThinking.ENABLED, ThinkingRouter.route(long))
    }

    @Test
    fun `override 파서 — on off 만 인식, 그 외는 null(자동)`() {
        assertEquals(CloudThinking.ENABLED, CloudThinkingOption.parse("on"))
        assertEquals(CloudThinking.ENABLED, CloudThinkingOption.parse("enabled"))
        assertEquals(CloudThinking.DISABLED, CloudThinkingOption.parse("off"))
        assertEquals(CloudThinking.DISABLED, CloudThinkingOption.parse("disabled"))
        assertNull(CloudThinkingOption.parse(null))
        assertNull(CloudThinkingOption.parse(""))
        assertNull(CloudThinkingOption.parse("아무거나"))
    }
}
