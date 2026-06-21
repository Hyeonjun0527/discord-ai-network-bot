package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.FragmentType
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P04-T011 acceptance: 언어 규칙이 final 결정을 단독 강제하지 않고, corpus 에서 feature **값만** 검증된다.
 * 여기서는 feature 값(boolean/Int)만 검증한다 — 어떤 메서드도 버스트 경계를 반환하지 않는다.
 */
class KoreanFragmentFeaturesTest {
    private fun text(value: String): MessageFragment =
        MessageFragment(
            messageId = MessageId(1),
            authorId = AuthorId(1),
            channelId = ChannelId(100),
            sourceSequence = 1,
            occurredAt = BurstTestFragments.T0,
            content = MessageContent.Available(value),
            replyTo = null,
            threadId = null,
            type = FragmentType.NORMAL,
        )

    @Test
    fun `종결어미 부재를 값으로 추출한다`() {
        assertTrue(KoreanFragmentFeatures.lacksSentenceEnding(text("코알라 닉네임 추천")), "명사 종결 = 미완결")
        assertFalse(KoreanFragmentFeatures.lacksSentenceEnding(text("코알라 어때요")), "요 종결")
        assertFalse(KoreanFragmentFeatures.lacksSentenceEnding(text("좋아!")), "문장부호 종결")
    }

    @Test
    fun `자음 축약을 값으로 추출한다`() {
        assertTrue(KoreanFragmentFeatures.hasConsonantCluster(text("ㅇㅇ")))
        assertTrue(KoreanFragmentFeatures.hasConsonantCluster(text("ㄴㄴ 별로")))
        assertFalse(KoreanFragmentFeatures.hasConsonantCluster(text("좋아요")))
    }

    @Test
    fun `ㅃㄹ 재촉 신호를 값으로 추출한다`() {
        assertTrue(KoreanFragmentFeatures.urgesHurry(text("ㅃㄹ 정해")))
        assertTrue(KoreanFragmentFeatures.urgesHurry(text("빨리빨리")))
        assertFalse(KoreanFragmentFeatures.urgesHurry(text("천천히 해도 돼")))
    }

    @Test
    fun `ㅋㅋ 웃음 run 길이를 값으로 추출한다`() {
        assertEquals(4, KoreanFragmentFeatures.laughterRunLength(text("ㅋㅋㅋㅋ")))
        assertEquals(2, KoreanFragmentFeatures.laughterRunLength(text("아 ㅋㅋ 그래 ㅋ")), "최대 run")
        assertEquals(0, KoreanFragmentFeatures.laughterRunLength(text("하하")))
    }

    @Test
    fun `문장부호 종결을 값으로 추출한다`() {
        assertTrue(KoreanFragmentFeatures.endsWithPunctuation(text("진짜?")))
        assertFalse(KoreanFragmentFeatures.endsWithPunctuation(text("코알라")))
    }

    @Test
    fun `원문 미관찰이면 모든 텍스트 신호가 부재로 취급된다`() {
        val intentMissing =
            MessageFragment(
                messageId = MessageId(2),
                authorId = AuthorId(1),
                channelId = ChannelId(100),
                sourceSequence = 2,
                occurredAt = BurstTestFragments.T0,
                content = MessageContent.Unavailable.IntentMissing,
                replyTo = null,
                threadId = null,
                type = FragmentType.NORMAL,
            )
        assertFalse(KoreanFragmentFeatures.lacksSentenceEnding(intentMissing), "권한 부재 != 종결어미 부재")
        assertFalse(KoreanFragmentFeatures.hasConsonantCluster(intentMissing))
        assertEquals(0, KoreanFragmentFeatures.laughterRunLength(intentMissing))
    }
}
