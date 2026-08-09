package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechGroundingNeed
import com.discordassistant.central.speech.domain.model.SpeechResponseObligation
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget

/** speech 생성 테스트 공용 패킷 빌더(중복 제거). */
internal object SpeechGenerationFixtures {
    fun packet(
        inputTraceId: String? = null,
        socialAct: SpeechSocialAct = SpeechSocialAct.ACKNOWLEDGE,
        styleResponseMode: HumanSpeechResponseMode? = null,
        burstShape: SpeechBurstShape = SpeechBurstShape(1, 280, false),
        turns: List<ConversationTurn> = listOf(ConversationTurn("user_1", "안녕")),
        memoryRefs: List<MemoryRef> = emptyList(),
        speechIntent: String? = null,
        rawContextSceneData: String? = null,
        responseTargetRef: String? = null,
        responseObligation: SpeechResponseObligation = SpeechResponseObligation.OPTIONAL,
        groundingNeed: SpeechGroundingNeed = SpeechGroundingNeed.NONE,
        identity: IdentityKernelSection = IdentityKernelSection.of("니아", "당신은 「니아」 예요.", listOf("비서 멘트 금지")),
    ): SpeechScenePacket =
        SpeechScenePacket.of(
            inputTraceId = inputTraceId,
            focusThreadKey = "thread_1",
            target = SpeechTarget.member("user_1"),
            recentTurns = turns,
            socialAct = socialAct,
            styleResponseMode = styleResponseMode,
            burstShape = burstShape,
            identity = identity,
            memoryRefs = memoryRefs,
            speechIntent = speechIntent,
            rawContextSceneData = rawContextSceneData,
            responseTargetRef = responseTargetRef,
            responseObligation = responseObligation,
            groundingNeed = groundingNeed,
        )
}
