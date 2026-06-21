package com.discordassistant.central.socialmemory.domain.model.relationship

import java.time.Duration
import java.time.Instant

/**
 * unresolved interaction(미해결 상호작용) 상태(NEXA-P06-T015, 순수 도메인 값 객체·불변).
 *
 * NEXA 가 말하다 취소했거나 상대 질문이 열린 상태를 **만료(expiry)와 함께** 보존한다(observable-state-policy 허용:
 * "답을 약속했으나 아직 안 함" 같은 미해결 상호작용 관찰). 원문이 아니라 종류 코드·source event·시각만 담는다.
 *
 * **acceptance(T015) — 영구 미해결 상태가 쌓이지 않고 만료/해결 이벤트가 있다**:
 * 모든 미해결은 [expiresAt] 를 가져 [now] 가 지나면 [isExpired] 다. 명시적 [resolve]/[isExpired] 로 상태가
 * 닫혀, 영구 누적을 막는다(영구 낙인 금지, 불변식 3). 만료/해결을 [status] 로 표현한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class UnresolvedInteraction(
    /** guild-scoped 가명 관계 키. */
    val key: MemberKey,
    /** 미해결 종류(NEXA 가 말 끊음 / 상대 질문 열림). */
    val kind: UnresolvedKind,
    /** 발생 근거 원천 이벤트 ID(provenance). 비어 있을 수 없다. */
    val sourceEventId: String,
    /** 발생 시각. */
    val openedAt: Instant,
    /** 만료 시각 — 이후 [isExpired]. 영구 누적 방지. */
    val expiresAt: Instant,
    /** 현재 상태(열림/해결/만료). 기본 [UnresolvedStatus.OPEN]. */
    val status: UnresolvedStatus = UnresolvedStatus.OPEN,
    /** 해결/만료가 일어난 시각. OPEN 이면 null. */
    val closedAt: Instant? = null,
) {
    init {
        require(sourceEventId.isNotBlank()) { "sourceEventId 는 비어 있을 수 없다(provenance)" }
        require(!expiresAt.isBefore(openedAt)) { "expiresAt 는 openedAt 이후여야 한다" }
        require((status == UnresolvedStatus.OPEN) == (closedAt == null)) {
            "OPEN 이면 closedAt 이 null, 닫혔으면 closedAt 이 있어야 한다"
        }
    }

    /** [now] 기준으로 만료 시각을 지났는가(상태가 아직 OPEN 이어도 시간상 만료). */
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    /** 아직 유효한 미해결인가([now] 가 만료 전이고 OPEN). */
    fun isActive(now: Instant): Boolean = status == UnresolvedStatus.OPEN && !isExpired(now)

    /** [at] 에 해결됨으로 닫은 새 상태(이미 닫혔으면 그대로). */
    fun resolve(at: Instant): UnresolvedInteraction =
        if (status != UnresolvedStatus.OPEN) {
            this
        } else {
            copy(status = UnresolvedStatus.RESOLVED, closedAt = at)
        }

    /** [now] 기준 만료됐으면 EXPIRED 로 닫은 새 상태(만료 이벤트). 아직 활성이면 그대로. */
    fun expireIfDue(now: Instant): UnresolvedInteraction =
        if (status == UnresolvedStatus.OPEN && isExpired(now)) {
            copy(status = UnresolvedStatus.EXPIRED, closedAt = expiresAt)
        } else {
            this
        }

    companion object {
        /** [openedAt] 에서 [ttl] 만큼 유효한 미해결을 연다. */
        fun open(
            key: MemberKey,
            kind: UnresolvedKind,
            sourceEventId: String,
            openedAt: Instant,
            ttl: Duration,
        ): UnresolvedInteraction {
            require(!ttl.isZero && !ttl.isNegative) { "ttl 은 양수여야 한다(영구 미해결 금지)" }
            return UnresolvedInteraction(
                key = key,
                kind = kind,
                sourceEventId = sourceEventId,
                openedAt = openedAt,
                expiresAt = openedAt.plus(ttl),
            )
        }
    }
}

/** 미해결 상호작용 종류(NEXA-P06-T015, 순수 enum). 닫힌 코드 — 자유 텍스트 아님. */
enum class UnresolvedKind(
    val wireName: String,
) {
    /** NEXA 가 말하다 취소·중단함(열린 NEXA 행동). */
    NEXA_CUT_OFF("nexa_cut_off"),

    /** 상대 질문이 열린 채 응답 대기 중. */
    MEMBER_QUESTION_OPEN("member_question_open"),
}

/** 미해결 상호작용 상태(NEXA-P06-T015, 순수 enum). 영구 누적 방지를 위해 닫힘 상태를 명시한다. */
enum class UnresolvedStatus(
    val wireName: String,
) {
    OPEN("open"),
    RESOLVED("resolved"),
    EXPIRED("expired"),
}
