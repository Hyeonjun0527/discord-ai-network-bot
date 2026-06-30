package com.discordassistant.central.participation.application.context

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.participation.application.judge.JudgeDecisionConstraints
import com.discordassistant.central.participation.application.judge.JudgeFewShotSetPayload
import com.discordassistant.central.participation.application.judge.JudgeMemoryRef
import com.discordassistant.central.participation.application.judge.SingleJudgeDecisionRequest
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneBuildResult
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneObservation
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneSnapshotBuilder

class NiaJudgeContextAssembler(
    private val windowBuilder: JudgeContextWindowBuilder =
        JudgeContextWindowBuilder(RawContextRetentionPolicy.DEFAULT_MAX_RAW_CHARS),
) {
    fun assemble(input: NiaJudgeContextInput): SingleJudgeDecisionRequest {
        val sceneBuild = input.sceneBuild ?: SingleJudgeSceneSnapshotBuilder.build(input.sceneObservation)
        return SingleJudgeDecisionRequest(
            rawContextWindow = windowBuilder.build(input.rawContextSnapshot),
            sceneSnapshot = sceneBuild.sceneSnapshot,
            featureVector = sceneBuild.featureVector,
            fewShotSet = input.fewShotSet,
            memoryRefs = input.memoryRefs,
            constraints = input.constraints,
            schemaVersion = input.schemaVersion,
            seed = input.seed,
        )
    }
}

data class NiaJudgeContextInput(
    val rawContextSnapshot: RawContextSnapshot,
    val sceneObservation: SingleJudgeSceneObservation,
    val constraints: JudgeDecisionConstraints,
    val seed: Long,
    val fewShotSet: JudgeFewShotSetPayload = JudgeFewShotSetPayload.EMPTY,
    val memoryRefs: List<JudgeMemoryRef> = emptyList(),
    val sceneBuild: SingleJudgeSceneBuildResult? = null,
    val schemaVersion: Int = SingleJudgeDecisionRequest.CURRENT_SCHEMA_VERSION,
) {
    init {
        require(seed >= 0) { "seed 는 음수일 수 없다: $seed" }
        require(schemaVersion >= 1) { "schemaVersion 은 1 이상이어야 한다: $schemaVersion" }
    }
}
