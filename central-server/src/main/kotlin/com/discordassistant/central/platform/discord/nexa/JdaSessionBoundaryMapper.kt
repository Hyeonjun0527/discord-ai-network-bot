package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventIdentity
import com.discordassistant.central.conversation.domain.model.event.EventType
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.conversation.domain.model.event.SessionBoundaryKind
import com.discordassistant.central.conversation.domain.model.event.SessionBoundaryObserved

/**
 * Gateway 재연결·세션 경계 매퍼(NEXA-P03-T020). JDA 세션 라이프사이클 콜백에서 추출한 [SessionBoundarySnapshot] 을
 * 도메인 [SessionBoundaryObserved] 메타 이벤트로 정규화한다.
 *
 * 다른 매퍼와 동일하게 추출(JDA)·매핑(순수) 두 단계 중 **매핑 단계**다 — 스냅샷이 JDA-free 이므로 toEvent 는 JDA mock
 * 없이 단위 테스트된다. 세션 경계는 콘텐츠를 운반하지 않으므로 privacyClass LOW.
 *
 * **acceptance(T020)**: 새 세션은 게이트웨이 시퀀스를 재시작해 sourceSequence 가 재사용될 수 있다. 이 메타 이벤트의
 * [SessionBoundaryObserved.createsSequenceGap] 가 NEW_SESSION 에서 true 라, 수집/재생이 "이전 세션의 큰 순번 뒤에
 * 새 세션의 작은 순번" 을 갭으로 구분한다(내부 순서 깨짐 방지). 같은 [sessionId]·경계 종류·[lastGatewaySequence] 면
 * 같은 [EventIdentity] 라 재수신이 dedup 된다(at-least-once 안전).
 */
class JdaSessionBoundaryMapper {
    /** 세션 경계 스냅샷 → 도메인 [SessionBoundaryObserved]. */
    fun toEvent(snapshot: SessionBoundarySnapshot): SessionBoundaryObserved =
        SessionBoundaryObserved(
            eventId =
                EventIdentity(
                    // 세션 메타는 메시지 id 가 없으므로 마지막 시퀀스를 대상 키로, 경계 종류 순번을 revision 으로 분리해
                    // 같은 세션의 disconnect/resume/new 가 키 충돌 없이 구분된다(없으면 0).
                    discordId = snapshot.lastGatewaySequence ?: 0L,
                    type = EventType.SESSION_BOUNDARY,
                    revision =
                        when (snapshot.boundary) {
                            SessionBoundaryKindSnapshot.DISCONNECTED -> 0L
                            SessionBoundaryKindSnapshot.RESUMED -> 1L
                            SessionBoundaryKindSnapshot.NEW_SESSION -> 2L
                        },
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = PrivacyClass.LOW,
            boundary =
                when (snapshot.boundary) {
                    SessionBoundaryKindSnapshot.DISCONNECTED -> SessionBoundaryKind.DISCONNECTED
                    SessionBoundaryKindSnapshot.RESUMED -> SessionBoundaryKind.RESUMED
                    SessionBoundaryKindSnapshot.NEW_SESSION -> SessionBoundaryKind.NEW_SESSION
                },
            sessionId = snapshot.sessionId,
            lastGatewaySequence = snapshot.lastGatewaySequence,
        )
}
