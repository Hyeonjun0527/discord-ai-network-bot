package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import com.discordassistant.central.speech.domain.service.critic.CandidateText

/** critic·selector 테스트 공용 빌더(중복 제거). */
internal object SpeechCriticFixtures {
    fun packet(
        focusThreadKey: String = "thread_1",
        target: SpeechTarget = SpeechTarget.member("user_1"),
        turns: List<ConversationTurn> = listOf(ConversationTurn("user_1", "안녕")),
        memoryRefs: List<MemoryRef> = emptyList(),
        socialAct: SpeechSocialAct = SpeechSocialAct.ACKNOWLEDGE,
        burstShape: SpeechBurstShape = SpeechBurstShape(1, 280, false),
        speechIntent: String? = null,
        rawContextSceneData: String? = null,
    ): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = focusThreadKey,
            target = target,
            recentTurns = turns,
            socialAct = socialAct,
            burstShape = burstShape,
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요.", listOf("비서 멘트 금지")),
            memoryRefs = memoryRefs,
            speechIntent = speechIntent,
            rawContextSceneData = rawContextSceneData,
        )

    fun candidate(
        id: String = "c1",
        vararg bubbles: String,
    ): CandidateText = CandidateText(id, bubbles.toList())

    fun memory(
        claim: String,
        provenance: String = "stated",
        confidence: Double = 0.9,
    ): MemoryRef = MemoryRef(claim = claim, provenance = provenance, confidence = confidence)
}
