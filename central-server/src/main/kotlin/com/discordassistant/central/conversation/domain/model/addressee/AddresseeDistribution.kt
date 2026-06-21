package com.discordassistant.central.conversation.domain.model.addressee

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.MessageId

/**
 * 한 burst 가 "누구에게 말하는가" 에 대한 확률분포(NEXA-P05-T009, 순수 도메인 값 객체·불변).
 *
 * addressee 는 단일 정답이 아니라 **확률분포** 다 — reply/mention 이 없는 자유 발화에서는 대상이 모호하기
 * 때문이다. 그래서 후보 member/message 각각에 확률을 주고, 아무에게도 안 한 발화일 가능성([noneProbability],
 * 그룹/혼잣말 등)도 항상 명시한다. resolver(T005~T008)가 이 객체를 만들고 합성한다.
 *
 * **acceptance(T009)**:
 * - 확률 합 = 1.0(후보 확률 + [noneProbability], 부동소수 허용오차 내) — [init] 에서 검증.
 * - 각 확률은 [0,1] 범위 — [init] 에서 검증.
 * - evidence 에는 **원문이 저장되지 않는다** — [AddresseeEvidence] 는 코드(enum)만 담고 텍스트 필드가 없다.
 * - [resolverVersion] 으로 어떤 규칙 버전이 만들었는지 추적한다(재현·디버깅).
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다. value type 으로만 운반한다.
 */
data class AddresseeDistribution(
    /** 후보 addressee 별 확률(member 키, 중복 키 금지). 빈 맵이면 [noneProbability] = 1.0 이어야 한다. */
    val candidates: List<AddresseeCandidate>,
    /** 특정 대상이 없을 확률(그룹/혼잣말/모호). 항상 명시한다. */
    val noneProbability: Double,
    /** 이 분포를 만든 resolver 규칙 버전(추적용). */
    val resolverVersion: String,
    /** 판정 근거 코드들(원문 비저장 — enum 코드만). 순서 보존, 중복 허용 안 함. */
    val evidence: Set<AddresseeEvidence>,
) {
    init {
        require(resolverVersion.isNotBlank()) { "resolverVersion 은 비어 있을 수 없다" }
        require(noneProbability in 0.0..1.0) { "noneProbability 는 [0,1] 범위여야 한다: $noneProbability" }
        candidates.forEach {
            require(it.probability in 0.0..1.0) { "후보 확률은 [0,1] 범위여야 한다: ${it.probability}" }
        }
        require(candidates.map { it.member }.toSet().size == candidates.size) {
            "후보 member 키는 중복될 수 없다"
        }
        val total = noneProbability + candidates.sumOf { it.probability }
        require(kotlin.math.abs(total - 1.0) <= EPSILON) {
            "확률 합은 1.0 이어야 한다(허용오차 $EPSILON): 합=$total"
        }
    }

    /** 가장 확률이 높은 후보(동률이면 입력 순서상 먼저). 후보가 없으면 null(none 만 존재). */
    val mostLikely: AddresseeCandidate?
        get() = candidates.maxByOrNull { it.probability }

    /** "특정 대상 없음" 이 가장 그럴듯한가 — 어떤 후보보다도 [noneProbability] 가 높으면 true(group/모호). */
    val isLikelyNone: Boolean
        get() = noneProbability >= (mostLikely?.probability ?: 0.0)

    companion object {
        /** 확률 합 검증의 부동소수 허용오차. */
        const val EPSILON: Double = 1e-9

        /** 특정 대상이 전혀 없는 분포(none = 1.0). 자유 발화·그룹 발화의 기본형. */
        fun none(
            resolverVersion: String,
            evidence: Set<AddresseeEvidence> = emptySet(),
        ): AddresseeDistribution =
            AddresseeDistribution(
                candidates = emptyList(),
                noneProbability = 1.0,
                resolverVersion = resolverVersion,
                evidence = evidence,
            )
    }
}

/**
 * 한 addressee 후보(순수 도메인 값 객체). [member] 에게 [probability] 만큼 말했을 가능성이 있다. reply 로
 * 특정 메시지를 가리켰다면 [message] 도 채워진다(없으면 null — mention/adjacency 후보).
 */
data class AddresseeCandidate(
    val member: AuthorId,
    val probability: Double,
    /** reply 가 가리킨 특정 대상 메시지(있으면). mention/adjacency 후보면 null. */
    val message: MessageId? = null,
)

/**
 * Addressee 판정 근거 코드(순수 도메인 enum). **원문을 저장하지 않는다** — 어떤 신호로 그 확률이 나왔는지
 * 코드로만 남긴다(acceptance T009: evidence 에 원문 비저장). 분포 합성·디버깅·감사에 쓰인다.
 */
enum class AddresseeEvidence {
    /** Discord reply 가 존재해 대상을 특정함(T005). */
    DIRECT_REPLY,

    /** 자기 자신에게 답글(self-reply) — 대상 가중을 낮춘다(T005). */
    SELF_REPLY,

    /** reply 대상이 삭제됨(tombstone) — fallback 적용(T005). */
    DELETED_REPLY_TARGET,

    /** 본문 직접 mention 으로 대상을 특정함(T006). */
    DIRECT_MENTION,

    /** 닉네임 문자열 호출(약한 신호) — 단독으로 확정하지 않는다(T006). */
    NICKNAME_STRING,

    /** 직전 화자가 후보(adjacency, T007). */
    RECENT_SPEAKER,

    /** 질문 형태 발화라 직전 화자 가중을 높임(T007). */
    QUESTION_FORM,

    /** 화자 교대 패턴(A↔B turn-taking)이 후보를 강화함(T007). */
    ALTERNATION,

    /** 그룹 전체 발화로 판정됨 — 특정인에 귀속하지 않음(T008). */
    GROUP_ADDRESSED,
}
