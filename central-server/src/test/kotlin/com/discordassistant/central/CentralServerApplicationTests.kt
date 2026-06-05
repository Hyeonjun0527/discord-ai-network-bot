package com.discordassistant.central

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestWeight
import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 컨텍스트 로드 없이 도메인 enum 의 순수 로직만 검증한다(빠르고 외부 의존 없음).
 * 전체 Spring 컨텍스트 로드 테스트는 Discord/DB 빈이 들어온 뒤 별도로 추가한다.
 */
class CentralServerApplicationTests {
    @Test
    fun `요청 무게가 필요 부담 수준으로 매핑된다`() {
        assertEquals(ModelBurden.LIGHT, RequestWeight.LIGHT.requiredBurden())
        assertEquals(ModelBurden.STANDARD, RequestWeight.MEDIUM.requiredBurden())
        assertEquals(ModelBurden.HEAVY, RequestWeight.HEAVY.requiredBurden())
    }

    @Test
    fun `ProviderState 라우팅 가능 여부`() {
        assertTrue(com.discordassistant.central.provider.domain.model.ProviderState.ONLINE_IDLE.isRoutable)
        assertFalse(com.discordassistant.central.provider.domain.model.ProviderState.ONLINE_BUSY.isRoutable)
        assertFalse(com.discordassistant.central.provider.domain.model.ProviderState.OFFLINE.isRoutable)
        assertTrue(com.discordassistant.central.provider.domain.model.ProviderState.REMOVED.isTerminal)
    }

    @Test
    fun `RequestState 종단 상태`() {
        assertTrue(com.discordassistant.central.domain.RequestState.COMPLETED.isTerminal)
        assertTrue(com.discordassistant.central.domain.RequestState.REJECTED.isTerminal)
        assertFalse(com.discordassistant.central.domain.RequestState.ROUTING.isTerminal)
    }
}
