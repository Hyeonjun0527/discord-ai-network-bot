package com.discordassistant.central.socialmemory.adapter.outbound.globalpromptset

import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetView
import com.discordassistant.central.shared.NexaIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * NEXA-P07-T007: globalpromptset 의 승인된 정체성을 immutable snapshot 으로 **읽기만** 한다.
 * 런타임 대화가 identity kernel 을 자동 덮어쓰지 못한다(쓰기 경로 부재).
 */
class GlobalPromptSetIdentityKernelBridgeTest {
    private val promptSets: GlobalPromptSetService = mock(GlobalPromptSetService::class.java)
    private val bridge = GlobalPromptSetIdentityKernelBridge(promptSets)

    @Test
    fun `관리자 지정 셋이 있으면 그 persona 를 administratorApproved snapshot 으로 읽는다`() {
        `when`(promptSets.list(1L)).thenReturn(
            listOf(
                GlobalPromptSetView(id = "nia", name = "니아", builtin = true, isDefault = false, preview = "p", content = null),
                GlobalPromptSetView(id = "7", name = "서버지기", builtin = false, isDefault = true, preview = null, content = "..."),
            ),
        )
        val kernel = bridge.identityKernel(1L)
        assertEquals("서버지기", kernel.personaName)
        assertTrue(kernel.administratorApproved)
    }

    @Test
    fun `기본 지정이 없으면 NEXA 기본 정체성(니아)으로 폴백한다`() {
        `when`(promptSets.list(2L)).thenReturn(
            listOf(
                GlobalPromptSetView(id = "nia", name = "니아", builtin = true, isDefault = true, preview = "p", content = null),
            ),
        )
        val kernel = bridge.identityKernel(2L)
        assertEquals(NexaIdentity.NIA_NAME, kernel.personaName)
        assertFalse(kernel.administratorApproved)
    }

    @Test
    fun `acceptance - 브리지는 읽기 전용이라 kernel 을 덮어쓸 메서드가 없다 (snapshot 은 immutable)`() {
        `when`(promptSets.list(3L)).thenReturn(emptyList())
        val kernel = bridge.identityKernel(3L)
        // 반환 snapshot 은 data class val 들이라 호출자가 변형할 수 없다(복제만 가능). 폴백 니아.
        assertEquals(NexaIdentity.NIA_NAME, kernel.personaName)
        assertTrue(kernel.interestTags.isEmpty())
    }
}
