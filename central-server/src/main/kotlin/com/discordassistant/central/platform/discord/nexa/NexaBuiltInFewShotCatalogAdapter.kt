package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.application.fewshot.NiaBuiltInFewShotCatalog
import com.discordassistant.central.participation.application.fewshot.NiaBuiltInFewShotCatalogPort
import com.discordassistant.central.participation.application.fewshot.NiaFewShotSpeechPromptRenderer
import com.discordassistant.central.participation.application.judge.JudgeFewShotExamplePayload
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import org.springframework.stereotype.Component

@Component
class NexaBuiltInFewShotCatalogAdapter : NiaBuiltInFewShotCatalogPort {
    override fun catalog(): NiaBuiltInFewShotCatalog {
        val judge = NexaParticipationEmitBridge.builtInFewShotPayload()
        return NiaBuiltInFewShotCatalog(
            judgeSetId = requireNotNull(judge.setId),
            judgeVersion = requireNotNull(judge.version),
            judgeExamples = judge.examples.map(JudgeFewShotExamplePayload::toDomain),
            speechExamples = NiaFewShotSpeechPromptRenderer.builtInExamples(),
            editableExamples =
                judge.examples.map(JudgeFewShotExamplePayload::toDomain) + NiaFewShotSpeechPromptRenderer.builtInEditableExamples(),
        )
    }
}

private fun JudgeFewShotExamplePayload.toDomain(): NiaFewShotExample =
    NiaFewShotExample(
        title = title,
        rawMessages = rawMessages.map { NiaFewShotRawMessage(it.ref, it.authorRole, it.offsetMs, it.text) },
        expectedAction = expectedAction,
        expectedDeliveryMode = expectedDeliveryMode,
        expectedReplies = expectedReplies,
        currentState = currentState,
        expectedReactionCode = expectedReactionCode,
        expectedReevaluateAfterMs = expectedReevaluateAfterMs,
        reason = reason,
        evidenceRefs = evidenceRefs,
        badAlternative = NiaFewShotBadAlternative(badAlternative.action, badAlternative.whyBad, badAlternative.deliveryMode),
        tags = tags,
        priority = priority,
        privacyClass = privacyClass,
    )
