package com.discordassistant.central.conversation.domain.event

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageId

/**
 * 버스트 종료 도메인 이벤트(NEXA-P04-T017). conversation 이 한 [UtteranceBurst] 를 finalize 했을 때 발행해 다음
 * projection(participation·socialmemory)에 전달한다. domain-events.md 카탈로그 계약을 따른다:
 * **발행자=conversation, 소비자=participation·socialmemory, 멱등성 키=[burstId], PII=medium**.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 원문 텍스트를 운반하지 않는다(PII medium — fragment **식별자**와 결정 메타만; 원문 비전파,
 * domain-events.md 규칙 3).
 *
 * **acceptance(T017) — 버스트당 1회**: 멱등성 키는 [burstId] 다. 소비자는 이 키로 중복 수신을 무시한다(at-least-once).
 * 같은 버스트가 두 번 finalize 될 수 없으므로([UtteranceBurst.finalize] 가 OPEN 에서만 허용) 동일 [burstId] 에 대해
 * 의미상 한 번만 발행된다 — [fromBurst] 가 FINALIZED 버스트에서만 생성을 허용해 그 불변식을 생성 지점에서 강제한다.
 */
data class BurstFinalized(
    /** 멱등성 키(domain-events.md). 소비자는 이 키로 중복 수신을 무시한다. */
    val burstId: BurstId,
    val guildId: GuildId,
    val authorId: AuthorId,
    /** 종료된 버스트에 포함된 메시지 식별자(시간순) — 원문이 아니라 식별자만 운반한다(PII medium). */
    val fragmentIds: List<MessageId>,
    /** 이 버스트를 만든 segmentation 규칙 버전 — 재투영/재현 시 어떤 분할 규칙으로 닫혔는지 추적. */
    val segmentationVersion: Int,
    /** 종료 이유(gap 경과·작성자 개입·위치 이동·스트림 종료 등) — 다음 projection 의 분기 근거. */
    val terminationReason: BurstTerminationReason,
) {
    init {
        require(fragmentIds.isNotEmpty()) { "BurstFinalized 는 최소 1개 fragment 를 가진다(빈 버스트 종료 금지)" }
    }

    companion object {
        /**
         * FINALIZED 버스트로부터 [BurstFinalized] 를 만든다(버스트당 1회 발행의 생성 지점 가드).
         * OPEN/CORRECTED 버스트로는 만들 수 없다 — finalize 된 버스트만 이 이벤트의 대상이다(T017 멱등 불변식).
         */
        fun fromBurst(
            burst: UtteranceBurst,
            segmentationVersion: Int,
            terminationReason: BurstTerminationReason,
        ): BurstFinalized {
            require(burst.status == BurstStatus.FINALIZED) {
                "BurstFinalized 는 FINALIZED 버스트에서만 발행한다(현재 ${burst.status})"
            }
            return BurstFinalized(
                burstId = burst.burstId,
                guildId = burst.guildId,
                authorId = burst.authorId,
                fragmentIds = burst.messageIds,
                segmentationVersion = segmentationVersion,
                terminationReason = terminationReason,
            )
        }
    }
}

/**
 * 버스트 종료 이유(순수 도메인 enum). segmenter 가 어떤 규칙으로 버스트를 닫았는지 분류해 [BurstFinalized] 에 싣는다.
 * 결정 자체는 [com.discordassistant.central.conversation.domain.service.burst.FixedGapBurstSegmenter] 가 소유한다.
 */
enum class BurstTerminationReason {
    /** 같은 작성자 다음 조각이 effective gap 을 초과해 시간 경계로 닫힘(T004/T010). */
    GAP_ELAPSED,

    /** 다른 작성자의 발화 개입으로 닫힘(T007). */
    OTHER_AUTHOR_INTRUSION,

    /** reply 대상 변경 또는 thread/채널 위치 이동으로 닫힘(T008/T009). */
    CONTEXT_SWITCH,

    /** 입력 스트림 종료 또는 finalize 스케줄러의 deadline 만료로 닫힘(T019). */
    STREAM_END,
}
