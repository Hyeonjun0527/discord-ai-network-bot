package com.discordassistant.central.conversation.application.ingest

import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import java.time.Instant

/**
 * 수신 봉투 메타데이터(NEXA-P03-T007). 정규화된 [NormalizedDiscordEvent] 를 conversation 으로 인입할 때 함께
 * 운반되는 **수집 출처/재생 추적용** 메타데이터다.
 *
 * 정규화 이벤트 자체는 "무엇이 일어났는가"(도메인 사실)만 담는다. 이 봉투는 "그 사실을 **어디서·언제·어떤 매퍼로**
 * 받았는가"(수집 컨텍스트)를 분리해 담아, 장애 재생/원인 추적 시 "이 이벤트가 어느 샤드·세션·게이트웨이 시퀀스에서
 * 어느 mapperVersion 으로 만들어졌는지" 를 결정론적으로 복원할 수 있게 한다.
 *
 * 이 타입은 application 레이어 소속이며 JDA/Spring/JPA 타입을 참조하지 않는다(어댑터가 채워 넣는다). 표준 라이브러리
 * [Instant] 와 도메인 [NormalizedDiscordEvent] 만 본다.
 *
 * **acceptance(T007)**: [receivedAt] 과 [mapperVersion] 은 **하드코딩하지 않고 주입**된다([EnvelopeMetadataFactory]
 * 가 Clock·mapperVersion 을 주입받아 채운다). 그래서 재생 시 같은 입력·같은 주입값이면 같은 봉투가 재현되고, 시각/매퍼
 * 버전이 원인 추적의 근거가 된다.
 */
data class IngestEnvelope(
    /** 인입되는 정규화 도메인 이벤트(무엇이 일어났는가). */
    val event: NormalizedDiscordEvent,
    /** conversation 수집 경계가 이 이벤트를 수신한 시각 — Clock 주입값(하드코딩 금지). */
    val receivedAt: Instant,
    /** 이 이벤트가 들어온 게이트웨이 샤드. 비샤딩이면 [GatewaySource.NO_SHARD]. */
    val shardId: ShardId,
    /** 게이트웨이 세션 식별자(resume 경계 추적). 알 수 없으면 null(unavailable 명시). */
    val sessionId: String?,
    /** 게이트웨이 시퀀스 번호(s 필드). 알 수 없으면 null(unavailable 명시). */
    val gatewaySequence: Long?,
    /** 이 봉투를 만든 매퍼 버전 — 주입값(하드코딩 금지). 매퍼 로직 변경을 재생에서 구분한다. */
    val mapperVersion: MapperVersion,
    /** 수집 시점의 동의 스냅샷(관찰/발화 허용 여부) — 사후 정책 변경과 무관하게 그때의 결정을 보존한다. */
    val consentSnapshot: ConsentSnapshot,
)

/** 게이트웨이 샤드 식별자(순수 application value type). 비샤딩 단일 게이트웨이는 [NO_SHARD]. */
@JvmInline
value class ShardId(
    val value: Int,
) {
    companion object {
        /** 샤딩을 쓰지 않는 단일 게이트웨이 연결(현재 DiscordBot.createLight 모델). */
        val NO_SHARD: ShardId = ShardId(0)
    }
}

/** 게이트웨이 출처 상수 모음(샤딩 없음 등 명시 표현). */
object GatewaySource {
    /** 비샤딩 단일 게이트웨이. */
    val NO_SHARD: ShardId = ShardId.NO_SHARD
}

/** 매퍼 버전(순수 application value type). 주입되어 봉투에 박힌다 — 매퍼 로직 변경을 재생에서 구분. */
@JvmInline
value class MapperVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "mapperVersion 은 비어 있을 수 없다" }
    }
}

/**
 * 수집 시점 동의 스냅샷. 사후에 정책이 바뀌어도 "그 이벤트를 받을 때의 허용 여부" 를 보존한다.
 *
 * conversation.domain 의 ConsentDecision 과 의미는 같지만, 봉투는 application 메타데이터라 도메인 결정 타입을 그대로
 * 끌어오지 않고 수집 컨텍스트용 최소 스냅샷만 둔다(2축: 관찰/발화).
 */
data class ConsentSnapshot(
    /** 수집 시점 관찰 허용 여부. */
    val observationAllowed: Boolean,
    /** 수집 시점 발화 허용 여부(관찰 전제). */
    val speechAllowed: Boolean,
) {
    init {
        require(observationAllowed || !speechAllowed) {
            "speechAllowed 는 observationAllowed 없이 true 일 수 없다(관찰 전제)"
        }
    }
}
