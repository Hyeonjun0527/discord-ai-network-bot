package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.FragmentType
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P04-T012 acceptance: 텍스트 없는 반응성 메시지를 버스트 일부/독립으로 분류하되 attachment metadata 만으로
 * 원문 파일을 다운로드하지 않는다 — 분류는 type·content 가용 상태만 본다(첨부 바이트·URL 비참조).
 */
class ReactiveFragmentClassifierTest {
    private val empty = MessageContent.Unavailable.Empty

    @Test
    fun `텍스트 있는 조각은 TEXTUAL`() {
        val frag = BurstTestFragments.fragment(1, content = MessageContent.Available("안녕"))
        assertEquals(ReactiveFragmentKind.TEXTUAL, ReactiveFragmentClassifier.classify(frag))
    }

    @Test
    fun `텍스트 없는 이모지·시스템은 REACTIVE(가벼운 반응)`() {
        val emoji = BurstTestFragments.fragment(2, type = FragmentType.EMOJI, content = empty)
        val system = BurstTestFragments.fragment(3, type = FragmentType.SYSTEM, content = empty)
        assertEquals(ReactiveFragmentKind.REACTIVE, ReactiveFragmentClassifier.classify(emoji))
        assertEquals(ReactiveFragmentKind.REACTIVE, ReactiveFragmentClassifier.classify(system))
        assertTrue(ReactiveFragmentClassifier.isLightTouch(emoji), "이모지는 가벼운 개입(T007 정합)")
    }

    @Test
    fun `텍스트 없는 일반 첨부 메시지는 STANDALONE(독립 행동)`() {
        val attachmentOnly = BurstTestFragments.fragment(4, type = FragmentType.NORMAL, content = empty)
        assertEquals(ReactiveFragmentKind.STANDALONE, ReactiveFragmentClassifier.classify(attachmentOnly))
        assertFalse(ReactiveFragmentClassifier.isLightTouch(attachmentOnly), "독립 첨부는 가벼운 반응 아님")
    }
}
