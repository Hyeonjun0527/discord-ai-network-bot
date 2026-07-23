package com.discordassistant.central.speech.domain

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P14-T004: SpeechScenePacket 계약 — 전체 채널 로그 무제한 포함 금지. */
class SpeechScenePacketTest {
    private val identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요.")
    private val shape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 280, reactionOnly = false)

    @Test
    fun `of caps recent turns at MAX_TURNS keeping the most recent`() {
        val turns = (1..50).map { ConversationTurn("user_$it", "메시지 $it") }
        val packet =
            SpeechScenePacket.of(
                focusThreadKey = "thread_A",
                target = SpeechTarget.NONE,
                recentTurns = turns,
                socialAct = SpeechSocialAct.ACKNOWLEDGE,
                burstShape = shape,
                identity = identity,
            )
        assertThat(packet.recentTurns).hasSize(SpeechScenePacket.MAX_TURNS)
        // 가장 최근(메시지 50)이 남고 오래된 것은 잘린다.
        assertThat(packet.recentTurns.last().text).isEqualTo("메시지 50")
        assertThat(packet.recentTurns.first().text).isEqualTo("메시지 31")
    }

    @Test
    fun `constructor rejects turns over the cap (no unlimited log)`() {
        val turns = (1..SpeechScenePacket.MAX_TURNS + 1).map { ConversationTurn("u", "x") }
        assertThatThrownBy {
            SpeechScenePacket(
                focusThreadKey = "t",
                target = SpeechTarget.NONE,
                recentTurns = turns,
                socialAct = SpeechSocialAct.ASK,
                burstShape = shape,
                identity = identity,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `of caps memory refs`() {
        val refs = (1..20).map { MemoryRef("주장 $it", "observed", 0.9) }
        val packet =
            SpeechScenePacket.of(
                focusThreadKey = "t",
                target = SpeechTarget.member("user_1"),
                recentTurns = emptyList(),
                socialAct = SpeechSocialAct.AGREE,
                burstShape = shape,
                identity = identity,
                memoryRefs = refs,
            )
        assertThat(packet.memoryRefs).hasSize(SpeechScenePacket.MAX_MEMORY_REFS)
    }

    @Test
    fun `of caps raw scene keeping the latest response target`() {
        val rawScene = "oldest-marker" + "x".repeat(SpeechScenePacket.MAX_RAW_CONTEXT_SCENE_CHARS) + "latest-marker"

        val packet =
            SpeechScenePacket.of(
                focusThreadKey = "t",
                target = SpeechTarget.NONE,
                recentTurns = emptyList(),
                socialAct = SpeechSocialAct.ANSWER,
                burstShape = shape,
                identity = identity,
                rawContextSceneData = rawScene,
            )

        assertThat(packet.rawContextSceneData)
            .hasSize(SpeechScenePacket.MAX_RAW_CONTEXT_SCENE_CHARS)
            .endsWith("latest-marker")
            .doesNotContain("oldest-marker")
    }

    @Test
    fun `target none cannot carry a pseudonym`() {
        assertThatThrownBy { SpeechTarget(SpeechTarget.Kind.NONE, "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `socialAct fromWireName normalizes unknown to UNKNOWN`() {
        assertThat(SpeechSocialAct.fromWireName("tease")).isEqualTo(SpeechSocialAct.TEASE)
        assertThat(SpeechSocialAct.fromWireName("nonsense")).isEqualTo(SpeechSocialAct.UNKNOWN)
        assertThat(SpeechSocialAct.UNKNOWN.isUnknown).isTrue()
    }
}
