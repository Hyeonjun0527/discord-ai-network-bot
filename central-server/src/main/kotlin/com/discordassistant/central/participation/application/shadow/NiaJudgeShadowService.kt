package com.discordassistant.central.participation.application.shadow

import com.discordassistant.central.participation.application.judge.SingleJudgeDecision
import com.discordassistant.central.participation.application.judge.SingleJudgeDecisionRequest
import com.discordassistant.central.participation.application.judge.SingleParticipationJudgePort
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.ShadowPredictionRecord
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class NiaJudgeShadowService(
    private val judge: SingleParticipationJudgePort,
    private val predictionStore: ShadowPredictionStorePort,
    private val clock: Clock,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    fun record(request: SingleJudgeDecisionRequest): NiaJudgeShadowResult =
        runCatching {
            val decision = judge.decide(request)
            val now = Instant.now(clock)
            val record = request.toShadowRecord(decision, now)
            predictionStore.append(record)
            NiaJudgeShadowResult.Recorded(record)
        }.getOrElse { error ->
            NiaJudgeShadowResult.Failed(error.message ?: error::class.simpleName.orEmpty())
        }

    private fun SingleJudgeDecisionRequest.toShadowRecord(
        decision: SingleJudgeDecision,
        predictedAt: Instant,
    ): ShadowPredictionRecord =
        ShadowPredictionRecord(
            scene =
                SceneKey(
                    guildPseudonym = sceneSnapshot.ref.guildPseudonym,
                    channelId = sceneSnapshot.ref.channelId,
                    sceneSeq = sceneSnapshot.ref.sceneSeq,
                ),
            contextVersion = sceneSnapshot.ref.contextVersion,
            modelVersion = MODEL_VERSION,
            actionWeights = oneHot(decision.action),
            sampledAction = decision.action,
            expectedFireAt = decision.expectedFireAt(predictedAt),
            seed = seed,
            featureHash = featureHash(),
            featureVectorVersion = featureVector.version,
            predictedAt = predictedAt,
        )

    private fun oneHot(action: SocialActionKind): Map<SocialActionKind, Double> =
        SocialActionKind.entries.associateWith { kind -> if (kind == action) 1.0 else 0.0 }

    private fun SingleJudgeDecision.expectedFireAt(predictedAt: Instant): Instant? =
        when (action) {
            SocialActionKind.IGNORE,
            SocialActionKind.CANCEL_PENDING,
            -> null
            SocialActionKind.WAIT,
            SocialActionKind.REACT,
            SocialActionKind.SPEAK,
            -> predictedAt.plusMillis(delay.millis)
        }

    private fun SingleJudgeDecisionRequest.featureHash(): String =
        mapper
            .writeValueAsString(
                mapOf(
                    "featureVectorVersion" to featureVector.version,
                    "features" to featureVector.features.toStableFeatureMap(),
                    "rawMessageRefs" to rawContextWindow.messages.map { it.ref },
                    "scopeFingerprint" to rawContextWindow.scopeFingerprint,
                    "fewShotSetId" to fewShotSet.setId,
                    "fewShotVersion" to fewShotSet.version,
                    "schemaVersion" to schemaVersion,
                ),
            ).sha256()

    private fun Map<FeatureId, *>.toStableFeatureMap(): Map<String, Any?> =
        entries
            .sortedBy { it.key.id }
            .associate { (key, value) -> key.id to value }

    companion object {
        const val MODEL_VERSION: String = "nia-single-judge-shadow-v1"
    }
}

sealed interface NiaJudgeShadowResult {
    data class Recorded(
        val record: ShadowPredictionRecord,
    ) : NiaJudgeShadowResult

    data class Failed(
        val reason: String,
    ) : NiaJudgeShadowResult
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
