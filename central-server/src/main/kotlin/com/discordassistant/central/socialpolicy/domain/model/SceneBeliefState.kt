package com.discordassistant.central.socialpolicy.domain.model

import java.time.Instant

/** 대화 원문 대신 다음 판단에 이어지는 채널·스레드별 사회적 믿음 상태다. */
data class SceneBeliefState(
    val guildPseudonym: String,
    val channelId: String,
    val focusThreadKey: String,
    val sceneSeq: Long,
    val contextVersion: Long,
    val commonGround: List<CommonGroundBelief>,
    val intentHypotheses: List<IntentHypothesisBelief>,
    val recentNiaActions: List<RecentNiaActionBelief>,
    val recentOutcomes: List<RecentInteractionOutcomeBelief>,
    val updatedAt: Instant,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
        require(commonGround.size <= MAX_COMMON_GROUND) { "공통 기반은 최대 $MAX_COMMON_GROUND 개다" }
        require(intentHypotheses.size <= MAX_HYPOTHESES) { "의도 가설은 최대 $MAX_HYPOTHESES 개다" }
        require(recentNiaActions.size <= MAX_RECENT_ACTIONS) { "최근 니아 행동은 최대 $MAX_RECENT_ACTIONS 개다" }
        require(recentOutcomes.size <= MAX_RECENT_OUTCOMES) { "최근 상호작용 결과는 최대 $MAX_RECENT_OUTCOMES 개다" }
    }

    fun apply(delta: SceneBeliefDelta): SceneBeliefState =
        copy(
            commonGround = replaceCommonGround(commonGround, delta.commonGround),
            intentHypotheses = replaceHypotheses(intentHypotheses, delta.intentHypotheses),
        )

    fun record(action: RecentNiaActionBelief): SceneBeliefState =
        copy(recentNiaActions = (recentNiaActions.filterNot { it.actionId == action.actionId } + action).takeLast(MAX_RECENT_ACTIONS))

    fun record(outcome: RecentInteractionOutcomeBelief): SceneBeliefState =
        copy(recentOutcomes = (recentOutcomes.filterNot { it.actionId == outcome.actionId } + outcome).takeLast(MAX_RECENT_OUTCOMES))

    private fun replaceCommonGround(
        current: List<CommonGroundBelief>,
        updates: List<CommonGroundBelief>,
    ): List<CommonGroundBelief> =
        updates.fold(current) { acc, update -> acc.filterNot { it.code == update.code } + update }.takeLast(MAX_COMMON_GROUND)

    private fun replaceHypotheses(
        current: List<IntentHypothesisBelief>,
        updates: List<IntentHypothesisBelief>,
    ): List<IntentHypothesisBelief> =
        updates
            .fold(current) { acc, update ->
                acc.filterNot { it.participantPseudonym == update.participantPseudonym && it.code == update.code } + update
            }.groupBy(IntentHypothesisBelief::participantPseudonym)
            .values
            .flatMap { normalizeProbabilities(it) }
            .takeLast(MAX_HYPOTHESES)

    private fun normalizeProbabilities(values: List<IntentHypothesisBelief>): List<IntentHypothesisBelief> {
        val active = values.filter { it.status == BeliefStatus.ACTIVE }
        val total = active.sumOf(IntentHypothesisBelief::probability)
        if (total <= 1.0 || total == 0.0) return values
        return values.map { belief ->
            if (belief.status == BeliefStatus.ACTIVE) belief.copy(probability = belief.probability / total) else belief
        }
    }

    companion object {
        const val MAX_COMMON_GROUND: Int = 20
        const val MAX_HYPOTHESES: Int = 12
        const val MAX_RECENT_ACTIONS: Int = 12
        const val MAX_RECENT_OUTCOMES: Int = 12

        fun initial(
            guildPseudonym: String,
            channelId: String,
            focusThreadKey: String,
            at: Instant,
        ): SceneBeliefState =
            SceneBeliefState(
                guildPseudonym = guildPseudonym,
                channelId = channelId,
                focusThreadKey = focusThreadKey,
                sceneSeq = 0,
                contextVersion = 0,
                commonGround = emptyList(),
                intentHypotheses = emptyList(),
                recentNiaActions = emptyList(),
                recentOutcomes = emptyList(),
                updatedAt = at,
            )
    }
}

data class RecentInteractionOutcomeBelief(
    val actionId: String,
    val code: String,
    val evidenceRef: String,
    val occurredAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "결과 actionId 는 비어 있을 수 없다" }
        require(code.isStableCode()) { "결과 code 형식이 잘못됐다: $code" }
        require(evidenceRef.isStableRef()) { "결과 evidenceRef 형식이 잘못됐다" }
    }
}

data class CommonGroundBelief(
    val code: String,
    val confidence: Double,
    val evidenceRefs: Set<String>,
    val status: BeliefStatus = BeliefStatus.ACTIVE,
) {
    init {
        require(code.isStableCode()) { "공통 기반 code 형식이 잘못됐다: $code" }
        require(confidence in 0.0..1.0) { "공통 기반 confidence 는 [0,1]이어야 한다" }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.all(String::isStableRef)) { "공통 기반에는 안정된 근거 ref가 필요하다" }
    }
}

data class IntentHypothesisBelief(
    val participantPseudonym: String,
    val code: String,
    val probability: Double,
    val evidenceRefs: Set<String>,
    val status: BeliefStatus = BeliefStatus.ACTIVE,
) {
    init {
        require(participantPseudonym.isNotBlank()) { "가설 참여자는 비어 있을 수 없다" }
        require(code.isStableCode()) { "가설 code 형식이 잘못됐다: $code" }
        require(probability in 0.0..1.0) { "가설 probability 는 [0,1]이어야 한다" }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.all(String::isStableRef)) { "가설에는 안정된 근거 ref가 필요하다" }
    }
}

data class RecentNiaActionBelief(
    val actionId: String,
    val actionKind: String,
    val intentSummary: String?,
    val targetMessageRef: String?,
    val contextVersion: Long,
    val occurredAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        require(actionKind.isStableCode()) { "actionKind 형식이 잘못됐다: $actionKind" }
        intentSummary?.let { require(it.isNotBlank() && it.length <= MAX_INTENT_CHARS) { "intentSummary 가 너무 길거나 비어 있다" } }
        targetMessageRef?.let { require(it.isStableRef()) { "targetMessageRef 형식이 잘못됐다" } }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
    }

    companion object {
        const val MAX_INTENT_CHARS: Int = 240
    }
}

data class SceneBeliefDelta(
    val commonGround: List<CommonGroundBelief> = emptyList(),
    val intentHypotheses: List<IntentHypothesisBelief> = emptyList(),
) {
    init {
        require(commonGround.size <= 8) { "한 판단의 공통 기반 갱신은 최대 8개다" }
        require(intentHypotheses.size <= 8) { "한 판단의 의도 가설 갱신은 최대 8개다" }
    }
}

enum class BeliefStatus {
    ACTIVE,
    SUPERSEDED,
    REJECTED,
}

private fun String.isStableCode(): Boolean = matches(Regex("[a-z0-9][a-z0-9_.-]{0,159}"))

private fun String.isStableRef(): Boolean = matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))
