package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * Gateway 세션 경계 관찰 메타 이벤트(NEXA-P03-T020, 순수 도메인).
 *
 * Discord Gateway 는 끊김/resume/새 세션(invalidate→identify)을 겪는다. resume 은 마지막으로 본 시퀀스(s)부터
 * 이어받지만, **새 세션은 게이트웨이 시퀀스를 0 부터 재시작**한다 — 같은 채널 sourceSequence 가 재사용되면 수집
 * 경계가 "이전 세션의 1 번" 과 "새 세션의 1 번" 을 같은 순서로 오해할 수 있다. 이 메타 이벤트는 그 **세션 경계와
 * 시퀀스 갭**을 정규화 이벤트 스트림에 명시 기록해, 재생/순서 복원이 세션 재시작 지점을 구분하도록 한다.
 *
 * 이 이벤트는 Discord 콘텐츠가 아니라 **수집 인프라 관찰 사실**이라 원문/식별자를 운반하지 않는다 —
 * privacyClass 는 LOW(결정/상태만). 봉투([NormalizedDiscordEvent]) 공통 필드 + 경계 종류·세션·시퀀스 메타만 둔다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter
 * 타입을 일절 참조하지 않는다. 식별자는 순수 [Long]/도메인 value type 으로만 운반한다(JDA 타입 금지).
 *
 * **acceptance(T020)**: 세션 변경 후 sourceSequence 재사용이 내부 순서를 깨지 않는다 — [boundary] 가 NEW_SESSION 이면
 * [createsSequenceGap] 가 true 라, 그 뒤 sourceSequence 가 이전 세션보다 작아도(재시작) 수집 경계가 갭으로 인지한다.
 * resume 은 시퀀스를 이어받으므로 갭이 없다(false). [sessionId] 가 직전과 다른지로 새 세션을 결정론적으로 판정한다.
 */
data class SessionBoundaryObserved(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 관찰된 세션 경계 종류(끊김/resume/새 세션). */
    val boundary: SessionBoundaryKind,
    /** 이 경계가 시작한(또는 끝낸) 게이트웨이 세션 식별자. 모르면 null(unavailable 명시). */
    val sessionId: String?,
    /** 경계 직전까지 본 마지막 게이트웨이 시퀀스(resume 의 이어받기 기준). 모르면 null. */
    val lastGatewaySequence: Long?,
) : NormalizedDiscordEvent {
    /**
     * 이 경계가 **시퀀스 갭**(이후 sourceSequence/게이트웨이 시퀀스가 재시작될 수 있는 불연속)을 만드는가.
     *
     * NEW_SESSION 은 게이트웨이가 시퀀스를 0 부터 다시 시작하므로 갭이다 — 이 뒤로 들어오는 이벤트의 작은
     * sourceSequence 를 "이전 세션의 큰 값보다 앞" 으로 오해하지 않도록 수집 경계가 세션 분기를 적용해야 한다.
     * RESUME 은 마지막 시퀀스부터 이어받아 연속이라 갭이 아니다(false). DISCONNECT 자체는 아직 재시작 전이라 false.
     */
    val createsSequenceGap: Boolean get() = boundary == SessionBoundaryKind.NEW_SESSION
}

/**
 * Gateway 세션 경계 종류(순수 도메인 enum).
 *
 * [wireName] 은 [EventIdentity] 키에 들어가는 안정 라벨이다 — enum 이름이 바뀌어도 키 호환을 유지한다.
 */
enum class SessionBoundaryKind(
    val wireName: String,
) {
    /** 게이트웨이 연결이 끊겼다(아직 재연결/재시작 전). */
    DISCONNECTED("session.disconnected"),

    /** 같은 세션을 resume 했다 — 마지막 시퀀스부터 이어받아 연속(갭 없음). */
    RESUMED("session.resumed"),

    /** 새 세션을 시작했다(invalidate→identify) — 게이트웨이 시퀀스가 재시작(갭 발생). */
    NEW_SESSION("session.new"),
}
