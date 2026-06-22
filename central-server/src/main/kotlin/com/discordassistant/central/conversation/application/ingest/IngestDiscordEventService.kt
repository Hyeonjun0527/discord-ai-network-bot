package com.discordassistant.central.conversation.application.ingest

import com.discordassistant.central.conversation.application.port.out.AppendResult
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * conversation 이벤트 수집 유스케이스(NEXA-P03-T011). 정규화된 [NormalizedDiscordEvent] 를 **동의 확인 →
 * 정규화 검증 → append(+outbox)** 의 단일 트랜잭션 경계로 인입한다.
 *
 * **단일 트랜잭션 원자성(acceptance T011)**: [EventStorePort.append] 가 event 행과 outbox 행을 같은 트랜잭션에서
 * 같이 기록하므로(JpaEventStore @Transactional), "event 만 저장되고 outbox 가 없는"(또는 반대) 상태가 생기지 않는다.
 * 이 서비스도 @Transactional 이라 동의 검사 이후 append 까지 한 경계 안에서 일어난다.
 *
 * **fail-closed 동의(consent-model.md)**: 관찰이 허용되지 않으면([ConsentDecision.observationAllowed] == false)
 * 이벤트를 적재하지 않고 [IngestOutcome.REJECTED_CONSENT] 를 돌려준다 — 동의 전엔 관찰조차 시작하지 않는다.
 *
 * **멱등(T010/T011)**: append 가 [EventId] 유니크로 중복 재수신을 흡수한다. 같은 이벤트 재인입은
 * [IngestOutcome.DUPLICATE] 로 명시 구분돼 중복 side effect(중복 outbox)가 생기지 않는다.
 *
 * application 레이어 소속 — 포트(ConsentPolicyPort/EventStorePort)로만 외부와 결합한다(adapter 구현 미참조).
 */
@Service
class IngestDiscordEventService(
    private val consentPolicy: ConsentPolicyPort,
    private val eventStore: EventStorePort,
) {
    /**
     * [envelope] 의 정규화 이벤트를 동의 확인 후 적재한다.
     *
     * 1) 동의 확인 — (guild, author, channel) 관찰 허용 여부. 거부면 적재하지 않고 REJECTED_CONSENT.
     * 2) append(+outbox) — 같은 트랜잭션. 신규면 APPENDED, 중복 재수신이면 DUPLICATE(멱등).
     */
    @Transactional
    fun ingest(envelope: IngestEnvelope): IngestOutcome {
        val event = envelope.event
        if (!isObservationAllowed(event)) {
            return IngestOutcome.REJECTED_CONSENT
        }
        return when (eventStore.append(event)) {
            AppendResult.APPENDED -> IngestOutcome.APPENDED
            AppendResult.DUPLICATE -> IngestOutcome.DUPLICATE
        }
    }

    /**
     * 이 이벤트의 (guild, actor, channel) 맥락에서 관찰이 허용되는가. actor 식별자가 없는 이벤트(typing 등 일부)는
     * 작성자 축이 없으므로 guild·channel 만으로 동의를 합성한다(actorId=0 = 채널 스코프 검사).
     */
    private fun isObservationAllowed(event: NormalizedDiscordEvent): Boolean {
        val actorId = ActorIdExtractor.actorIdOf(event)
        return consentPolicy
            .observationDecision(
                guildId = event.guildId.value,
                userId = actorId,
                channelId = event.channelId.value,
            ).observationAllowed
    }
}

/** [IngestDiscordEventService.ingest] 결과 — 동의 거부/신규 적재/중복 흡수를 명시 구분한다. */
enum class IngestOutcome {
    /** 동의(관찰) 허용으로 신규 적재됨(event + outbox 같은 트랜잭션). */
    APPENDED,

    /** 이미 적재된 이벤트의 재수신 — 멱등 흡수(중복 side effect 없음). */
    DUPLICATE,

    /** 관찰 미허용(미동의/옵트아웃/제외 채널) — 적재하지 않음(fail-closed). */
    REJECTED_CONSENT,
}
