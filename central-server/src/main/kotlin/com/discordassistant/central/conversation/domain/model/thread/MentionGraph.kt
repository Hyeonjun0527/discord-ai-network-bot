package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.AuthorId

/**
 * Mention 그래프(NEXA-P05-T003, 순수 도메인 불변 그래프).
 *
 * burst 안의 mention 을 종류별로 다른 edge 로 표현한다 — 같은 "@" 라도 의미가 다르기 때문이다:
 * - [MentionKind.DIRECT]: 특정 사용자 직접 호출 → addressee 후보(누구에게 말하는가).
 * - [MentionKind.ROLE]: 역할 mention → 그 역할의 다수에게 알림, 단일 addressee 가 아니다.
 * - [MentionKind.EVERYONE]: @everyone/@here → 단순 broadcast 알림, addressee 후보 아님.
 *
 * **acceptance(T003)**: [directAddresseeCandidates] 는 DIRECT mention 만 모아 addressee 후보로 주고, ROLE/
 * EVERYONE 은 [notificationOnlyMentions] 로 분리한다 — 직접 호출과 단순 알림 mention 을 구분할 수 있다.
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다. value type 으로만 운반한다.
 */
data class MentionGraph(
    /** burst → mention 대상으로 향하는 종류별 edge 들(삽입 순서 보존). */
    val edges: List<MentionEdge>,
) {
    /** [source] burst 에서 나가는 mention edge 들(없으면 빈 리스트). */
    fun outgoing(source: BurstId): List<MentionEdge> = edges.filter { it.source == source }

    /**
     * [source] burst 의 **직접 addressee 후보** member 들 — DIRECT mention 만(중복 제거, 순서 보존).
     * ROLE/EVERYONE 은 특정 addressee 가 아니므로 제외한다(acceptance).
     */
    fun directAddresseeCandidates(source: BurstId): List<AuthorId> =
        outgoing(source)
            .filter { it.kind == MentionKind.DIRECT }
            .mapNotNull { it.member }
            .distinct()

    /**
     * [source] burst 의 **단순 알림** mention edge 들 — ROLE/EVERYONE(특정 addressee 아님).
     * 직접 호출(DIRECT)과 구분되는 broadcast/그룹 신호다(acceptance).
     */
    fun notificationOnlyMentions(source: BurstId): List<MentionEdge> = outgoing(source).filter { it.kind != MentionKind.DIRECT }

    companion object {
        /** 빈 그래프(edge 없음). */
        val EMPTY: MentionGraph = MentionGraph(emptyList())
    }
}

/**
 * Burst 단위 mention edge(순수 도메인). [source] burst 가 누군가/무언가를 mention 했다. DIRECT 면 [member] 가
 * 채워지고(특정 사용자), ROLE/EVERYONE 이면 [member] 는 null(특정 사용자가 아닌 그룹 알림이다).
 */
data class MentionEdge(
    val source: BurstId,
    val kind: MentionKind,
    /** DIRECT mention 의 대상 사용자. ROLE/EVERYONE 이면 특정 사용자가 아니므로 null. */
    val member: AuthorId?,
) {
    init {
        when (kind) {
            MentionKind.DIRECT -> require(member != null) { "DIRECT mention 은 대상 member 가 있어야 한다" }
            MentionKind.ROLE, MentionKind.EVERYONE ->
                require(member == null) { "$kind mention 은 특정 member 를 갖지 않는다(그룹 알림)" }
        }
    }
}

/** Mention 종류(순수 도메인 enum). 직접 addressee 후보(DIRECT)와 단순 알림(ROLE/EVERYONE)을 구분한다. */
enum class MentionKind {
    /** 특정 사용자 직접 호출 — addressee 후보. */
    DIRECT,

    /** 역할 mention — 그 역할의 다수에게 알림(단일 addressee 아님). */
    ROLE,

    /** @everyone/@here — 채널 전체 broadcast(addressee 후보 아님). */
    EVERYONE,
}
