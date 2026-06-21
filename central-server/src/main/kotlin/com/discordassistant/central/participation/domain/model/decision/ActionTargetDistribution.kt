package com.discordassistant.central.participation.domain.model.decision

/**
 * 행동 대상(target) 확률분포(NEXA-P08-T003, 순수 도메인 값 객체·불변).
 *
 * NEXA 가 행동(REACT/SPEAK)을 **무엇에 대해** 하는지를 단일 정답이 아니라 **후보별 확률분포** 로 표현한다 —
 * 특정 message, 특정 member, 특정 thread, 또는 대상 없음(none, 채널 전체/혼잣말). 정책이 모호할 때 분포로 운반한다.
 *
 * **acceptance(T003) — 대상 확률 합과 존재하지 않는 ID 검증**:
 * - 후보 확률 + [noneProbability] 합 = 1.0(허용오차 내) — [init] 검증.
 * - 각 확률 [0,1] — [init] 검증.
 * - **존재하지 않는 ID 검증**: 후보 target 의 식별자([TargetRef.id])가 [knownTargetIds] 에 모두 포함되는지
 *   [requireKnownTargets] 로 검증한다. 장면에 없는 message/member/thread 를 대상으로 고르는 오류를 막는다.
 *
 * 식별자는 원시 String 으로만 운반한다(JDA snowflake 타입 금지, 원문 비포함). 어떤 종류의 대상인지는
 * [TargetKind] 코드로 구분한다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
data class ActionTargetDistribution(
    /** 후보 대상별 확률(중복 ref 금지). 빈 리스트면 [noneProbability] = 1.0 이어야 한다. */
    val candidates: List<TargetCandidate>,
    /** 특정 대상이 없을 확률(채널 전체/혼잣말/모호). 항상 명시한다. */
    val noneProbability: Double,
    /** 이 분포를 만든 규칙 버전(추적용). */
    val resolverVersion: String,
) {
    init {
        require(resolverVersion.isNotBlank()) { "resolverVersion 은 비어 있을 수 없다" }
        require(noneProbability in 0.0..1.0) { "noneProbability 는 [0,1] 범위여야 한다: $noneProbability" }
        candidates.forEach {
            require(it.probability in 0.0..1.0) { "후보 확률은 [0,1] 범위여야 한다: ${it.probability}" }
        }
        require(candidates.map { it.target }.toSet().size == candidates.size) {
            "후보 대상(ref)은 중복될 수 없다"
        }
        val total = noneProbability + candidates.sumOf { it.probability }
        require(kotlin.math.abs(total - 1.0) <= EPSILON) {
            "확률 합은 1.0 이어야 한다(허용오차 $EPSILON): 합=$total"
        }
    }

    /** 가장 확률이 높은 후보(동률이면 입력 순서상 먼저). 후보가 없으면 null(none 만 존재). */
    val mostLikely: TargetCandidate?
        get() = candidates.maxByOrNull { it.probability }

    /** "특정 대상 없음" 이 가장 그럴듯한가 — 어떤 후보보다도 [noneProbability] 가 높으면 true. */
    val isLikelyNone: Boolean
        get() = noneProbability >= (mostLikely?.probability ?: 0.0)

    /**
     * acceptance(T003) — **존재하지 않는 ID 검증**. 모든 후보 target 의 id 가 [knownTargetIds] 에 있는지 확인한다.
     * 장면에 실재하는 식별자만 대상으로 삼도록 강제한다. 미지 id 가 있으면 [IllegalArgumentException].
     */
    fun requireKnownTargets(knownTargetIds: Set<String>) {
        val unknown = candidates.map { it.target.id }.filterNot { it in knownTargetIds }
        require(unknown.isEmpty()) { "장면에 존재하지 않는 대상 ID 가 있다: $unknown" }
    }

    companion object {
        /** 확률 합 검증의 부동소수 허용오차. */
        const val EPSILON: Double = 1e-9

        /** 특정 대상이 전혀 없는 분포(none = 1.0). 채널 전체 발화의 기본형. */
        fun none(resolverVersion: String): ActionTargetDistribution =
            ActionTargetDistribution(
                candidates = emptyList(),
                noneProbability = 1.0,
                resolverVersion = resolverVersion,
            )
    }
}

/**
 * 한 대상 후보(순수 도메인 값 객체). [target] 에 [probability] 만큼 행동할 가능성이 있다.
 */
data class TargetCandidate(
    val target: TargetRef,
    val probability: Double,
)

/**
 * 행동 대상 참조(순수 도메인 값 객체). 종류([kind])와 식별자([id], 원시 String — snowflake 타입 비포함)로
 * message/member/thread 를 가리킨다. none 은 후보가 아니라 [ActionTargetDistribution.noneProbability] 로 표현한다.
 */
data class TargetRef(
    val kind: TargetKind,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "target id 는 비어 있을 수 없다" }
    }
}

/**
 * 대상 종류(순수 도메인 enum). [NONE] 은 후보로 쓰지 않고 noneProbability 로 분리한다(여기엔 분류 완전성 용도로 둠).
 */
enum class TargetKind(
    val wireName: String,
) {
    MESSAGE("message"),
    MEMBER("member"),
    THREAD("thread"),
    NONE("none"),
}
