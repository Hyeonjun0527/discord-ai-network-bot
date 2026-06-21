package com.discordassistant.central.conversation.domain.service.scene

import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 현재 대화 초점 분포(NEXA-P05-T016, 순수 도메인 값 객체·불변). "지금 어느 논리 스레드가 대화의 초점인가" 를
 * **단일 정답이 아니라 확률분포** 로 표현한다 — 여러 대화가 동시에 흐를 수 있어 초점도 모호하기 때문이다.
 *
 * [com.discordassistant.central.conversation.domain.model.addressee.AddresseeDistribution] 과 같은 분포 패턴:
 * 후보 스레드 각각에 확률을 주고, **활성 스레드가 없을 확률**([idleProbability])도 항상 명시한다.
 *
 * **acceptance(T016) — 활성 스레드 없음을 정상 상태로 표현**: 활성 thread/recent target/pending burst 가 전혀
 * 없으면 [idle] 분포([idleProbability] = 1.0, 후보 없음)다 — 이는 오류가 아니라 "지금 초점 대화 없음" 의 정상값이다.
 *
 * 순수성: Spring/JPA/JDA 미참조. value type 으로만 운반한다.
 */
data class ConversationFocus(
    /** 초점 후보 스레드별 확률(중복 스레드 금지). 빈 리스트면 [idleProbability] = 1.0 이어야 한다. */
    val candidates: List<FocusCandidate>,
    /** 초점 대화가 없을 확률(정상 상태 — 활성 스레드/타겟/pending 없음). 항상 명시한다. */
    val idleProbability: Double,
    /** 이 분포를 만든 규칙 버전(추적용). */
    val ruleVersion: String,
) {
    init {
        require(ruleVersion.isNotBlank()) { "ruleVersion 은 비어 있을 수 없다" }
        require(idleProbability in 0.0..1.0) { "idleProbability 는 [0,1] 범위여야 한다: $idleProbability" }
        candidates.forEach {
            require(it.probability in 0.0..1.0) { "후보 확률은 [0,1] 범위여야 한다: ${it.probability}" }
        }
        require(candidates.map { it.threadId }.toSet().size == candidates.size) {
            "후보 스레드는 중복될 수 없다"
        }
        val total = idleProbability + candidates.sumOf { it.probability }
        require(kotlin.math.abs(total - 1.0) <= EPSILON) {
            "확률 합은 1.0 이어야 한다(허용오차 $EPSILON): 합=$total"
        }
    }

    /** 가장 확률 높은 초점 스레드(동률이면 입력 순서 먼저). 후보 없으면 null(idle). */
    val primary: FocusCandidate?
        get() = candidates.maxByOrNull { it.probability }

    /** 초점 대화가 없는 정상 상태인가 — idle 확률이 어떤 후보보다 높으면 true(acceptance: 정상 상태). */
    val isIdle: Boolean
        get() = idleProbability >= (primary?.probability ?: 0.0)

    companion object {
        /** 확률 합 검증의 부동소수 허용오차. */
        const val EPSILON: Double = 1e-9

        /** 활성 스레드가 전혀 없는 정상 상태(idle = 1.0). 활성 thread/target/pending 부재의 기본형(acceptance T016). */
        fun idle(ruleVersion: String): ConversationFocus =
            ConversationFocus(
                candidates = emptyList(),
                idleProbability = 1.0,
                ruleVersion = ruleVersion,
            )
    }
}

/**
 * 초점 후보 한 스레드(순수 도메인 값 객체). [threadId] 가 지금 초점일 [probability]. [evidence] 로 어떤 신호가
 * 그 확률을 만들었는지 코드로만 남긴다(원문 비포함).
 */
data class FocusCandidate(
    val threadId: ConversationThreadId,
    val probability: Double,
    val evidence: Set<FocusEvidence> = emptySet(),
)

/** 초점 판정 근거 코드(순수 도메인 enum). 원문 없이 어떤 구조 신호로 초점이 잡혔는지 코드로만 남긴다. */
enum class FocusEvidence {
    /** 활성(최근 발화) 스레드. */
    ACTIVE_THREAD,

    /** 최근 addressee target 이 이 스레드를 가리킴. */
    RECENT_TARGET,

    /** 진행 중(pending/OPEN) burst 가 이 스레드에 있음. */
    PENDING_BURST,
}
