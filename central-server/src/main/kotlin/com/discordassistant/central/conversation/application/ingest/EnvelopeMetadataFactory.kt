package com.discordassistant.central.conversation.application.ingest

import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import java.time.Clock

/**
 * 수신 봉투([IngestEnvelope]) 조립기(NEXA-P03-T007). 정규화 이벤트에 수집 메타데이터를 붙여 봉투를 만든다.
 *
 * **acceptance(T007)**: [receivedAt] 과 [mapperVersion] 을 **하드코딩하지 않고 주입**한다 — [clock] 과
 * [mapperVersion] 을 생성자로 받는다. 그래서 같은 입력·같은 주입값이면 같은 봉투가 재현되고(결정론), 재생 시
 * "어느 시각·어느 매퍼 버전" 으로 만들어졌는지 추적 가능하다. 테스트는 고정 Clock 으로 시각을 검증한다.
 *
 * application 레이어 소속 — JDA/Spring/JPA 타입을 참조하지 않는다(어댑터가 출처 메타를 넘겨준다).
 */
class EnvelopeMetadataFactory(
    private val clock: Clock,
    private val mapperVersion: MapperVersion,
) {
    /**
     * 정규화 [event] 와 게이트웨이 출처 메타를 받아 봉투를 만든다. [receivedAt] 은 주입된 [clock] 에서 읽고,
     * [mapperVersion] 은 주입값을 박는다(하드코딩 금지).
     *
     * @param shardId 이 이벤트가 들어온 샤드(비샤딩이면 [GatewaySource.NO_SHARD]).
     * @param sessionId 게이트웨이 세션 식별자(모르면 null — unavailable 명시).
     * @param gatewaySequence 게이트웨이 시퀀스 번호(모르면 null — unavailable 명시).
     * @param consentSnapshot 수집 시점 동의 스냅샷.
     */
    fun wrap(
        event: NormalizedDiscordEvent,
        shardId: ShardId = GatewaySource.NO_SHARD,
        sessionId: String? = null,
        gatewaySequence: Long? = null,
        consentSnapshot: ConsentSnapshot,
    ): IngestEnvelope =
        IngestEnvelope(
            event = event,
            receivedAt = clock.instant(),
            shardId = shardId,
            sessionId = sessionId,
            gatewaySequence = gatewaySequence,
            mapperVersion = mapperVersion,
            consentSnapshot = consentSnapshot,
        )
}
