package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId

/**
 * 작성자별 진행 중 버스트 상태(NEXA-P04-T003, 순수 도메인). 한 **위치**(채널 또는 스레드, [BurstLocationKey]) 안에서
 * 각 작성자의 OPEN 버스트와 마지막 조각 시각을 추적하는 불변 스냅샷이다. 상태 변경은 항상 새 인스턴스를 돌려준다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다.
 *
 * **acceptance(T003) — 세션이 섞이지 않는다**: 세션은 [location] 하나에 묶인다. 서로 다른 채널·스레드는 각자 다른
 * [BurstSession] 인스턴스이므로, 한 작성자가 채널 X 와 스레드 Y 에서 동시에 OPEN 버스트를 가져도 두 세션이 같은
 * 맵에 섞이지 않는다(위치별 분리). 같은 세션 안에서는 작성자([AuthorId])별로 OPEN 버스트가 1개씩만 추적된다.
 */
data class BurstSession(
    /** 이 세션이 담당하는 단일 위치(채널/스레드). 다른 위치는 다른 세션 인스턴스다. */
    val location: BurstLocationKey,
    /** 작성자별 현재 OPEN 버스트. finalize 되면 맵에서 제거된다(진행 중인 것만 남는다). */
    val openBursts: Map<AuthorId, UtteranceBurst>,
) {
    init {
        require(openBursts.values.all { it.location == location }) {
            "세션의 모든 OPEN 버스트는 같은 위치여야 한다(채널/스레드 섞임 금지)"
        }
        require(openBursts.values.all { it.status == BurstStatus.OPEN }) {
            "세션에는 OPEN 버스트만 추적된다(finalize 된 버스트는 제거)"
        }
    }

    /** [author] 의 현재 진행 중 OPEN 버스트(없으면 null). */
    fun openBurstOf(author: AuthorId): UtteranceBurst? = openBursts[author]

    /** [author] 의 OPEN 버스트를 [burst] 로 갱신/추가한 새 세션(불변). 위치 일치는 [UtteranceBurst] 가 보장한다. */
    fun withOpenBurst(burst: UtteranceBurst): BurstSession {
        require(burst.location == location) { "다른 위치의 버스트는 이 세션에 넣을 수 없다" }
        require(burst.status == BurstStatus.OPEN) { "OPEN 버스트만 세션에 추적된다" }
        return copy(openBursts = openBursts + (burst.authorId to burst))
    }

    /** [author] 의 OPEN 버스트를 제거한 새 세션(finalize 후 호출; 없으면 그대로). */
    fun withoutAuthor(author: AuthorId): BurstSession = copy(openBursts = openBursts - author)

    companion object {
        /** 빈 세션(아직 OPEN 버스트 없음)을 만든다. */
        fun empty(location: BurstLocationKey): BurstSession = BurstSession(location = location, openBursts = emptyMap())
    }
}
