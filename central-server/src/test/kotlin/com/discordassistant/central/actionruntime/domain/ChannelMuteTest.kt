package com.discordassistant.central.actionruntime.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T014 도메인 acceptance: 발화만 끄기와 관찰·저장까지 끄기를 분리하고, 관찰 중단은
 * OBSERVE_AND_SPEECH 에서만(= 신규 append 차단) 일어난다.
 */
class ChannelMuteTest {
    private val mutes =
        mapOf(
            "c-speech" to ChannelMuteLevel.SPEECH_ONLY,
            "c-observe" to ChannelMuteLevel.OBSERVE_AND_SPEECH,
        )

    @Test
    fun `unmuted channel allows both speech and observation append`() {
        assertTrue(ChannelMute.allowsSpeech("c-none", mutes))
        assertTrue(ChannelMute.allowsObservationAppend("c-none", mutes))
        assertTrue(ChannelMute.levelOf("c-none", mutes) == ChannelMuteLevel.NONE)
    }

    @Test
    fun `speech-only mute blocks speech but keeps observing`() {
        // 발화만 끔: 발화는 막되 신규 관찰·append 는 계속(맥락 보존).
        assertFalse(ChannelMute.allowsSpeech("c-speech", mutes))
        assertTrue(ChannelMute.allowsObservationAppend("c-speech", mutes))
    }

    @Test
    fun `observe-and-speech mute blocks new event store append`() {
        // acceptance: 관찰 중단은 새 event store append 부터 차단한다.
        assertFalse(ChannelMute.allowsSpeech("c-observe", mutes))
        assertFalse(ChannelMute.allowsObservationAppend("c-observe", mutes))
    }

    @Test
    fun `stronger level subsumes weaker - observe-and-speech also blocks speech`() {
        // 더 강한 수준이 약한 수준을 포함한다(OBSERVE_AND_SPEECH 면 발화도 당연히 차단).
        assertFalse(ChannelMuteLevel.OBSERVE_AND_SPEECH.allowsSpeech)
        assertFalse(ChannelMuteLevel.OBSERVE_AND_SPEECH.allowsObservationAppend)
        assertFalse(ChannelMuteLevel.SPEECH_ONLY.allowsSpeech)
        assertTrue(ChannelMuteLevel.SPEECH_ONLY.allowsObservationAppend)
    }
}
