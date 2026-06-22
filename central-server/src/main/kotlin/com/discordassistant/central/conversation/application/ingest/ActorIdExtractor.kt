package com.discordassistant.central.conversation.application.ingest

import com.discordassistant.central.conversation.domain.model.event.MemberIdentityChanged
import com.discordassistant.central.conversation.domain.model.event.MessageCreated
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.Reaction
import com.discordassistant.central.conversation.domain.model.event.TypingStarted

/**
 * 정규화 이벤트에서 동의 합성에 쓸 actor(작성자/행위자) 식별자를 뽑는다(NEXA-P03-T011 보조, application).
 *
 * 동의(consent-model.md/user-opt-out.md)는 (guild, user, channel) 축으로 합성된다. 일부 이벤트는 actor 축이 있고
 * (메시지/리액션/타이핑/멤버변경), 일부는 없다(메시지 삭제 등 시스템 관점). actor 가 없는 이벤트는 [NO_ACTOR](0)로
 * 채워 guild·channel 스코프만으로 동의를 판정한다(개인 옵트아웃 축은 비적용).
 *
 * application 레이어 소속 — 순수 도메인 이벤트 타입만 보고 Spring/JPA/JDA 를 참조하지 않는다.
 */
object ActorIdExtractor {
    /** actor 축이 없는 이벤트의 표식(개인 옵트아웃 검사 비적용 — guild/channel 스코프만). */
    const val NO_ACTOR: Long = 0L

    /** 이벤트가 운반하는 actor 식별자(없으면 [NO_ACTOR]). */
    fun actorIdOf(event: NormalizedDiscordEvent): Long =
        when (event) {
            is MessageCreated -> event.authorId.value
            is Reaction -> event.actorId.value
            is TypingStarted -> event.actorId.value
            is MemberIdentityChanged -> event.actorId.value
            else -> NO_ACTOR
        }
}
