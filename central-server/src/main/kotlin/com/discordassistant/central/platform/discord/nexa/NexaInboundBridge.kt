package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.ingest.IngestDiscordEventService
import com.discordassistant.central.conversation.application.ingest.IngestEnvelope
import com.discordassistant.central.conversation.application.ingest.IngestOutcome
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Discord inbound → conversation ingestion 연결 브리지(NEXA-P15-T004, platform/discord 어댑터).
 *
 * JDA 리스너가 **기존 명령/자동응답 처리와 별도로** 정규화 이벤트를 NEXA conversation ingestion 에 전달하는
 * 단일 진입점이다. discord-adapter-boundary.md 의 inbound 포트 역할 — 변환은 [DiscordEventMapper]/매퍼들이 하고,
 * 이 브리지는 **flag 가드 + 위임**만 한다(행동 결정·문장 생성 안 함, 불변식 3).
 *
 * **acceptance(T004) — slash/preset 명령 처리 지연·중복 소비 없음**:
 *  1. **별도 경로**: 이 브리지는 명령 디스패치 경로(`DiscordBot.dispatch`/`onSlashCommandInteraction`)와 분리돼
 *     있다 — 명령은 명령대로, ingestion 은 ingestion 대로. 호출자(리스너)가 명령 처리 후/와 무관하게 부른다.
 *  2. **flag 가드(기본 OFF)**: [forward] 는 (guild, channel)의 NEXA flag 가 활성일 때만 ingestion 을 호출한다.
 *     flag OFF(legacy)면 **아무 것도 하지 않는다**(`return`) — 기존 동작에 단 한 줄의 지연·side effect 도 없다(회귀 0).
 *  3. **멱등**: ingestion 자체가 [com.discordassistant.central.conversation.application.port.out.EventStorePort]
 *     의 EventId 유니크로 중복 재수신을 흡수한다([IngestOutcome.DUPLICATE]) — 중복 소비가 outbox 중복을 만들지 않는다.
 *
 * 실패 격리: ingestion 실패가 **메시지 처리(명령/자동응답)를 깨지 않도록** 예외를 흡수하고 로그만 남긴다
 * (관찰 파이프라인 장애가 사용자 응답을 막지 않는다 — fire-and-forget 관찰).
 */
@Component
class NexaInboundBridge(
    private val flags: NexaParticipationFlagService,
    private val ingest: IngestDiscordEventService,
) {
    private val log = LoggerFactory.getLogger(NexaInboundBridge::class.java)

    /**
     * [envelope] 를 (guild, channel) flag 가 활성일 때만 NEXA ingestion 에 전달한다. flag OFF 면 no-op(기존 동작
     * 보존). 봉투는 JDA 매퍼/[com.discordassistant.central.conversation.application.ingest.EnvelopeMetadataFactory]
     * 가 만든다(receivedAt/mapperVersion 주입) — 이 브리지는 flag 가드 + 위임만 한다.
     * ingestion 결과를 돌려준다(테스트·관찰용) — flag OFF/실패면 [IngestOutcome] 없이 [ForwardResult.Skipped]/Failed.
     */
    fun forward(envelope: IngestEnvelope): ForwardResult {
        val event = envelope.event
        if (!flags.isNexaActive(guildId = event.guildId.value, channelId = event.channelId.value)) {
            return ForwardResult.Skipped // flag OFF(legacy) — 기존 동작 100% 보존
        }
        return try {
            ForwardResult.Ingested(ingest.ingest(envelope))
        } catch (e: Exception) {
            // 관찰 실패는 사용자 응답(명령/자동응답)을 막지 않는다 — 흡수 후 로그만(예외 원칙: 관찰은 best-effort).
            log.warn("NEXA ingestion 실패(channel={}) — 관찰만 건너뜀: {}", event.channelId.value, e.message)
            ForwardResult.Failed
        }
    }
}

/** [NexaInboundBridge.forward] 결과 — flag OFF(skip)/적재/실패를 명시 구분(관찰·테스트). */
sealed interface ForwardResult {
    /** flag OFF(legacy) 또는 비활성 — ingestion 미호출(기존 동작 보존). */
    data object Skipped : ForwardResult

    /** ingestion 호출됨 — 그 결과(APPENDED/DUPLICATE/REJECTED_CONSENT). */
    data class Ingested(
        val outcome: IngestOutcome,
    ) : ForwardResult

    /** ingestion 호출 중 예외 흡수 — 사용자 응답에는 영향 없음. */
    data object Failed : ForwardResult
}
