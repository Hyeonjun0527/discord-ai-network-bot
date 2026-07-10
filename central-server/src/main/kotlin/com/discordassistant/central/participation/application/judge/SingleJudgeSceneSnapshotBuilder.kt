package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.feature.MemoryFeatures
import com.discordassistant.central.participation.application.feature.MemoryObservation
import com.discordassistant.central.participation.application.feature.RelationshipFeatures
import com.discordassistant.central.participation.application.feature.RelationshipObservation
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef

object SingleJudgeSceneSnapshotBuilder {
    private const val IDLE_GAP_THRESHOLD_MILLIS: Long = 5_000

    fun build(observation: SingleJudgeSceneObservation): SingleJudgeSceneBuildResult {
        val directAddressed = observation.derivedDirectAddressed()
        val textSignals = observation.toTextSignals(directAddressed)
        val agentState =
            JudgeAgentSceneState(
                recentSpeechCount = observation.recentAgentBurstCount,
                lastSpokeAgeSeconds = observation.lastNiaSpokeAgeSeconds,
            )
        val conversationState =
            JudgeConversationSceneState(
                humanLikelyAnswering = observation.humanLikelyAnswering,
                idleGapLikely = observation.silenceMillis?.let { it >= IDLE_GAP_THRESHOLD_MILLIS } ?: false,
                resolvedLikely = observation.resolvedLikely || looksResolved(observation.triggerText.orEmpty()),
                humansTalkingToEachOtherLikely =
                    observation.humansTalkingToEachOtherLikely || (observation.replyToHuman && !directAddressed),
                niaAddressedOrIdleOpportunity =
                    directAddressed || (observation.silenceMillis?.let { it >= IDLE_GAP_THRESHOLD_MILLIS } ?: false),
            )
        val turnTakingState =
            JudgeTurnTakingSceneState(
                directAddressPressure = observation.directAddressPressure.coerceAtLeast(textSignals.callPressure),
                replyChainDepth = observation.replyChainDepth,
                nicknameCall = observation.nicknameCall,
                previousIgnoredRequestCount = observation.previousIgnoredRequestCount,
            )
        val runtimeGuardState =
            JudgeRuntimeGuardState(
                rateLimitPressure = observation.rateLimitPressure,
                antiSpamPressure = observation.antiSpamPressure,
            )
        val relationshipState = observation.relationshipObservation.toRelationshipSceneState()
        val memoryState = observation.memoryObservation.toMemorySceneState()
        val snapshot =
            SingleJudgeSceneSnapshot(
                ref = observation.ref,
                directAddressed = directAddressed,
                replyToNia = observation.replyToNia,
                conversationMentionsNia = observation.conversationMentionsNia,
                recentAgentBurstCount = observation.recentAgentBurstCount,
                silenceMillis = observation.silenceMillis,
                pendingActionIds = observation.pendingActionIds,
                textSignals = textSignals,
                agentState = agentState,
                conversationState = conversationState,
                turnTakingState = turnTakingState,
                runtimeGuardState = runtimeGuardState,
                relationshipState = relationshipState,
                memoryState = memoryState,
            )
        return SingleJudgeSceneBuildResult(
            sceneSnapshot = snapshot,
            featureVector = observation.toFeatureVector(textSignals, directAddressed),
        )
    }

    private fun SingleJudgeSceneObservation.toTextSignals(currentMessageDirectAddressed: Boolean): JudgeSceneTextSignals {
        val text = triggerText.orEmpty()
        val contentAvailable = triggerText != null
        return JudgeSceneTextSignals(
            contentAvailable = contentAvailable,
            isQuestion = contentAvailable && looksQuestion(text),
            replyTargetKind = replyTargetKind(),
            emotionalIntensity = if (contentAvailable) emotionalIntensityOf(text) else 0.0,
            callPressure =
                if (contentAvailable) {
                    callPressureOf(text, currentMessageDirectAddressed)
                } else {
                    0.0
                },
        )
    }

    private fun SingleJudgeSceneObservation.toFeatureVector(
        textSignals: JudgeSceneTextSignals,
        directAddressed: Boolean,
    ): FeatureVectorView {
        val pairs =
            linkedMapOf(
                FeatureCatalog.BURST_IS_QUESTION to textDerivedFeature(textSignals.isQuestion, textSignals.contentAvailable),
                FeatureCatalog.BURST_HAS_MENTION to FeatureValue.present(if (directAddressed) 1.0 else 0.0),
                FeatureCatalog.BURST_IS_REPLY to FeatureValue.present(if (replyToNia || replyToHuman) 1.0 else 0.0),
                FeatureCatalog.THREAD_DIRECT_ADDRESS_PRESSURE to FeatureValue.present(directAddressPressure),
                FeatureCatalog.THREAD_REPLY_CHAIN_DEPTH to FeatureValue.present(replyChainDepth.toDouble()),
                FeatureCatalog.THREAD_PREVIOUS_IGNORED_REQUEST_COUNT to
                    FeatureValue.present(previousIgnoredRequestCount.toDouble()),
                FeatureCatalog.TEMPO_RATE_LIMIT_PRESSURE to FeatureValue.present(rateLimitPressure),
                FeatureCatalog.TEMPO_ANTI_SPAM_PRESSURE to FeatureValue.present(antiSpamPressure),
                FeatureCatalog.AGENT_RECENT_BURST_COUNT to FeatureValue.present(recentAgentBurstCount.toDouble()),
                FeatureCatalog.AGENT_LAST_SPOKE_AGE_SECONDS to lastSpokeFeature(lastNiaSpokeAgeSeconds),
                FeatureCatalog.AGENT_PENDING_ACTION_COUNT to FeatureValue.present(pendingActionIds.size.toDouble()),
            )
        pairs.putAll(relationshipFeaturePairs(relationshipObservation))
        pairs.putAll(memoryFeaturePairs(memoryObservation))
        return FeatureVectorView.of(version = FeatureCatalog.VERSION, pairs = pairs)
    }

    private fun RelationshipObservation?.toRelationshipSceneState(): JudgeRelationshipSceneState =
        when (this) {
            null -> JudgeRelationshipSceneState.EMPTY
            else ->
                JudgeRelationshipSceneState(
                    familiarity = familiarity.takeIf { observed },
                    reciprocity = reciprocity.takeIf { observed },
                    banterAcceptance = banterAcceptance.takeIf { observed },
                    sampleConfidence = RelationshipFeatures.sampleConfidence(sampleSize),
                )
        }

    private fun MemoryObservation?.toMemorySceneState(): JudgeMemorySceneState =
        when (this) {
            null -> JudgeMemorySceneState.EMPTY
            else ->
                JudgeMemorySceneState(
                    relevantPresent = relevantPresent,
                    topConfidence = topConfidence.takeIf { relevantPresent },
                    freshestAgeSeconds = freshestAgeSeconds.takeIf { relevantPresent },
                    pendingIntentActive = pendingIntentActive,
                )
        }

    private fun relationshipFeaturePairs(observation: RelationshipObservation?): Map<FeatureId, FeatureValue> =
        observation?.let { RelationshipFeatures.build(it) }
            ?: linkedMapOf(
                FeatureCatalog.REL_FAMILIARITY to FeatureValue.MISSING,
                FeatureCatalog.REL_RECIPROCITY to FeatureValue.MISSING,
                FeatureCatalog.REL_BANTER_ACCEPTANCE to FeatureValue.MISSING,
                FeatureCatalog.REL_SAMPLE_CONFIDENCE to FeatureValue.MISSING,
            )

    private fun memoryFeaturePairs(observation: MemoryObservation?): Map<FeatureId, FeatureValue> =
        observation?.let { MemoryFeatures.build(it) }
            ?: linkedMapOf(
                FeatureCatalog.MEMORY_RELEVANT_PRESENT to FeatureValue.MISSING,
                FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE to FeatureValue.MISSING,
                FeatureCatalog.MEMORY_RELEVANT_AGE_SECONDS to FeatureValue.MISSING,
                FeatureCatalog.MEMORY_PENDING_INTENT_ACTIVE to FeatureValue.MISSING,
            )

    private fun textDerivedFeature(
        value: Boolean,
        contentAvailable: Boolean,
    ): FeatureValue = if (contentAvailable) FeatureValue.present(if (value) 1.0 else 0.0) else FeatureValue.MISSING

    private fun lastSpokeFeature(value: Double?): FeatureValue = value?.let { FeatureValue.present(it) } ?: FeatureValue.MISSING

    private fun SingleJudgeSceneObservation.derivedDirectAddressed(): Boolean = directAddressed || replyToNia || nicknameCall

    private fun SingleJudgeSceneObservation.replyTargetKind(): String =
        when {
            replyToNia -> "nia"
            replyToHuman -> "human"
            else -> "none"
        }

    private fun looksQuestion(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.endsWith("?") ||
            trimmed.endsWith("？") ||
            QUESTION_CONTAINS_MARKERS.any { marker -> marker in trimmed } ||
            QUESTION_ENDING_MARKERS.any { marker -> trimmed.endsWith(marker) }
    }

    private fun looksResolved(text: String): Boolean {
        val trimmed = text.trim()
        return RESOLVED_MARKERS.any { marker -> trimmed.endsWith(marker) }
    }

    private fun emotionalIntensityOf(text: String): Double {
        var score = 0.0
        if ("!" in text || "！" in text) score += 0.25
        if ("ㅠ" in text || "ㅜ" in text) score += 0.25
        if ("ㅋㅋ" in text || "ㅎㅎ" in text) score += 0.15
        if (Regex("""(.)\1{2,}""").containsMatchIn(text)) score += 0.2
        if (text.length >= 80) score += 0.15
        return score.coerceIn(0.0, 1.0)
    }

    private fun callPressureOf(
        text: String,
        addressedInContext: Boolean,
    ): Double {
        var score = 0.0
        if (addressedInContext || "니아" in text) score += 0.4
        if (CALL_PRESSURE_MARKERS.any { marker -> marker in text }) score += 0.4
        if (Regex("""니아[야아님]*""").findAll(text).count() >= 2) score += 0.2
        return score.coerceIn(0.0, 1.0)
    }

    private val QUESTION_CONTAINS_MARKERS = listOf("뭐", "왜", "어떻게", "언제", "어디")
    private val QUESTION_ENDING_MARKERS = listOf("할까", "하냐", "나요", "니")
    private val RESOLVED_MARKERS = listOf("고마워", "감사", "됐어", "해결", "잘자", "수고")
    private val CALL_PRESSURE_MARKERS = listOf("답장", "대답", "위로", "반응", "말해", "무시")
}

data class SingleJudgeSceneObservation(
    val ref: SceneSnapshotRef,
    val triggerText: String?,
    val directAddressed: Boolean,
    val replyToNia: Boolean,
    val replyToHuman: Boolean,
    val conversationMentionsNia: Boolean,
    val recentAgentBurstCount: Int,
    val silenceMillis: Long?,
    val lastNiaSpokeAgeSeconds: Double?,
    val pendingActionIds: List<String> = emptyList(),
    val humanLikelyAnswering: Boolean = false,
    val resolvedLikely: Boolean = false,
    val directAddressPressure: Double = 0.0,
    val replyChainDepth: Int = 0,
    val nicknameCall: Boolean = false,
    val previousIgnoredRequestCount: Int = 0,
    val humansTalkingToEachOtherLikely: Boolean = false,
    val rateLimitPressure: Double = 0.0,
    val antiSpamPressure: Double = 0.0,
    val relationshipObservation: RelationshipObservation? = null,
    val memoryObservation: MemoryObservation? = null,
) {
    init {
        require(recentAgentBurstCount >= 0) { "recentAgentBurstCount 는 음수일 수 없다: $recentAgentBurstCount" }
        silenceMillis?.let { require(it >= 0) { "silenceMillis 는 음수일 수 없다: $it" } }
        lastNiaSpokeAgeSeconds?.let { require(it >= 0.0) { "lastNiaSpokeAgeSeconds 는 음수일 수 없다: $it" } }
        pendingActionIds.forEach { require(it.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" } }
        require(directAddressPressure in 0.0..1.0) {
            "directAddressPressure 는 [0,1] 범위여야 한다: $directAddressPressure"
        }
        require(replyChainDepth >= 0) { "replyChainDepth 는 음수일 수 없다: $replyChainDepth" }
        require(previousIgnoredRequestCount >= 0) {
            "previousIgnoredRequestCount 는 음수일 수 없다: $previousIgnoredRequestCount"
        }
        require(rateLimitPressure in 0.0..1.0) { "rateLimitPressure 는 [0,1] 범위여야 한다: $rateLimitPressure" }
        require(antiSpamPressure in 0.0..1.0) { "antiSpamPressure 는 [0,1] 범위여야 한다: $antiSpamPressure" }
    }
}

data class SingleJudgeSceneBuildResult(
    val sceneSnapshot: SingleJudgeSceneSnapshot,
    val featureVector: FeatureVectorView,
)
