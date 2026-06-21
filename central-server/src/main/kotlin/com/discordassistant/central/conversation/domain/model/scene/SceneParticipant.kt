package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import java.time.Instant

/**
 * 장면 참여자 요약(NEXA-P05-T014, 순수 도메인 값 객체·불변). 한 사용자가 지금 대화에서 어떤 상태인지를 **원문 없이**
 * 요약한다 — 마지막 발화 시각, 진행 중(open) burst, mention 받은 상태만 담는다.
 *
 * **acceptance(T014) — 옵트아웃 사용자의 content-derived feature 미포함**:
 * 이 모델은 어떤 content 파생 feature(요약·키워드·감정 등)도 담지 않는다 — 식별자([authorId]), 시각([lastSpokeAt]),
 * 구조적 상태([openBurst]/[mentionedBy])만이다. 따라서 옵트아웃 사용자라도 안전하게 포함할 수 있다(원문 비파생).
 * [contentDerived] 가 항상 false 임을 [init] 에서 강제해, 이후 누가 content feature 를 끼워 넣지 못하게 가드한다.
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다. value type 으로만 운반한다.
 */
data class SceneParticipant(
    val authorId: AuthorId,
    /** 이 참여자의 마지막 발화 시각(가장 최근 burst 의 마지막 조각 시각). */
    val lastSpokeAt: Instant,
    /** 진행 중(OPEN)인 burst id(아직 말이 끝나지 않음). 없으면 null — 현재 발화 중이 아님. */
    val openBurst: BurstId? = null,
    /** 이 참여자를 mention 한 다른 참여자들(식별자만). 빈 집합이면 아무도 부르지 않음. */
    val mentionedBy: Set<AuthorId> = emptySet(),
) {
    /**
     * content 파생 feature 포함 여부 — 이 모델은 **항상 false**(구조·식별자·시각만). 옵트아웃 사용자 보호의 불변식이다.
     * 미래에 content feature 가 추가되면 이 가드가 깨져 회귀를 즉시 드러낸다(acceptance T014).
     */
    val contentDerived: Boolean
        get() = false

    init {
        require(!contentDerived) {
            "SceneParticipant 는 content-derived feature 를 담지 않는다(옵트아웃 보호, T014)"
        }
        require(authorId !in mentionedBy) { "참여자가 자기 자신을 mention 한 것으로 기록하지 않는다" }
    }

    /** 지금 발화 중인가 — OPEN burst 가 있으면 true(pending 발화). */
    val isSpeaking: Boolean
        get() = openBurst != null

    /** 누군가에게 호명된 상태인가 — mention 을 받았으면 true(응답 후보 신호). */
    val isAddressed: Boolean
        get() = mentionedBy.isNotEmpty()
}
