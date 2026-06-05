package com.discordassistant.central.routing

import com.discordassistant.central.routing.domain.service.RequestMeta
import com.discordassistant.central.routing.domain.service.RequestWeigher
import com.discordassistant.central.routing.domain.service.WeighDecision
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestWeight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RequestWeigherTest {
    private val w = RequestWeigher()

    @Test
    fun `무게 휴리스틱`() {
        assertEquals(RequestWeight.LIGHT, w.weigh(RequestMeta(promptChars = 50)))
        assertEquals(RequestWeight.MEDIUM, w.weigh(RequestMeta(promptChars = 500)))
        assertEquals(RequestWeight.MEDIUM, w.weigh(RequestMeta(promptChars = 50, command = "summarize")))
        assertEquals(RequestWeight.MEDIUM, w.weigh(RequestMeta(promptChars = 50, responseMode = "deep")))
        assertEquals(RequestWeight.HEAVY, w.weigh(RequestMeta(promptChars = 3000)))
        assertEquals(RequestWeight.HEAVY, w.weigh(RequestMeta(promptChars = 50, attachments = 1)))
    }

    @Test
    fun `권한 충족 → ACCEPT`() {
        val r = w.resolve(RequestMeta(promptChars = 50), ModelBurden.STANDARD)
        assertEquals(WeighDecision.ACCEPT, r.decision)
        assertEquals(ModelBurden.LIGHT, r.effectiveBurden)
    }

    @Test
    fun `1단계 부족 → DOWNGRADE`() {
        // 필요 STANDARD(medium), member 상한 LIGHT → 1단계 부족
        val r = w.resolve(RequestMeta(promptChars = 500), ModelBurden.LIGHT)
        assertEquals(WeighDecision.DOWNGRADE, r.decision)
        assertEquals(ModelBurden.LIGHT, r.effectiveBurden)
    }

    @Test
    fun `2단계 이상 부족 → REJECT`() {
        // 필요 HEAVY, member 상한 LIGHT → 2단계 부족
        val r = w.resolve(RequestMeta(promptChars = 5000), ModelBurden.LIGHT)
        assertEquals(WeighDecision.REJECT, r.decision)
        assertEquals(null, r.effectiveBurden)
    }
}
