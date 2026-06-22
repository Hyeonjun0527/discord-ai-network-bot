package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import java.time.Instant

/**
 * 버스트 식별자(NEXA-P04-T002, 순수 도메인 value type). 같은 작성자·위치·시작 조각이면 같은 버스트로 결정론적으로
 * 식별되도록 안정 키를 만든다 — 첫 조각의 messageId 가 버스트의 자연 식별 앵커다(재생 시 같은 입력이면 같은 id).
 */
@JvmInline
value class BurstId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "BurstId 는 비어 있을 수 없다" }
    }

    companion object {
        /** 첫 조각으로부터 결정론적 [BurstId] 생성(작성자 + 첫 메시지). 같은 입력 replay 면 같은 id. */
        fun fromFirstFragment(fragment: MessageFragment): BurstId = BurstId("burst:${fragment.authorId.value}:${fragment.messageId.value}")
    }
}

/**
 * 버스트 상태(NEXA-P04-T002, 순수 도메인 enum). OPEN → FINALIZED 가 정상 수명이고, FINALIZED 이후 원문 정정이
 * 관찰되면 CORRECTED 로 전이한다. 불법 순서(예: FINALIZED 인데 다시 조각 추가, CORRECTED 후 finalize)는 거부한다.
 */
enum class BurstStatus {
    /** 아직 진행 중 — 같은 작성자·위치의 조각을 더 받을 수 있다. */
    OPEN,

    /** 종료됨 — 더 이상 조각을 받지 않는다(BurstFinalized 발행 대상). */
    FINALIZED,

    /** 종료 후 정정됨 — 이미 finalize 된 버스트의 조각이 편집/삭제로 정정된 상태(재finalize 불가). */
    CORRECTED,
}

/**
 * 발화 버스트 aggregate(NEXA-P04-T002, 순수 도메인). 같은 작성자·같은 위치(채널/스레드)의 짧은 간격 연속 조각을
 * 하나의 발화 묶음으로 모은 불변 aggregate 다. 상태 전이는 항상 **새 인스턴스**를 돌려준다(불변; replay 안전).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 식별자는 순수 도메인 value type 으로만 운반한다.
 *
 * **acceptance(T002) — 불법 상태 전이 거부**:
 * - FINALIZED/CORRECTED 버스트에 [append] 하면 [IllegalBurstTransition](OPEN 만 조각 수용).
 * - 이미 FINALIZED/CORRECTED 인데 [finalize] 하면 거부(이중 finalize 금지).
 * - 작성자/위치가 다른 조각을 [append] 하면 거부(버스트는 단일 작성자·단일 위치 — 섞임 금지, T003).
 * - [correct] 는 FINALIZED 에서만 가능(OPEN 은 아직 정정 대상 아님, 이미 CORRECTED 면 멱등 거부).
 */
data class UtteranceBurst(
    val burstId: BurstId,
    val guildId: GuildId,
    val authorId: AuthorId,
    /** 버스트가 흐른 위치(채널/스레드) — 모든 조각이 같은 위치여야 한다(T003/T009). */
    val location: BurstLocationKey,
    val status: BurstStatus,
    /** 시간순(chronology)으로 정렬된 조각들. 항상 1개 이상(빈 버스트 금지). */
    val fragments: List<MessageFragment>,
) {
    init {
        require(fragments.isNotEmpty()) { "버스트는 최소 1개 조각을 가진다(빈 버스트 금지)" }
    }

    /** 버스트 시작 시각 = 첫(가장 이른) 조각의 발생 시각. */
    val startedAt: Instant get() = fragments.first().occurredAt

    /** 버스트의 마지막(가장 늦은) 조각 발생 시각 — gap 계산·typing 연장(T010)의 기준. */
    val lastFragmentAt: Instant get() = fragments.last().occurredAt

    /** 가장 최근 조각 — 다음 조각의 gap/reply target 비교 기준(T004/T008). */
    val lastFragment: MessageFragment get() = fragments.last()

    /** 버스트에 포함된 모든 메시지 식별자(시간순). */
    val messageIds: List<MessageId> get() = fragments.map { it.messageId }

    /**
     * 같은 작성자·같은 위치의 다음 [fragment] 를 버스트에 추가한 **새 버스트**를 돌려준다(불변).
     * OPEN 상태에서만 허용하고, 작성자/위치가 다르면 거부한다(버스트 섞임 금지).
     */
    fun append(fragment: MessageFragment): UtteranceBurst {
        if (status != BurstStatus.OPEN) {
            throw IllegalBurstTransition("OPEN 이 아닌 버스트($status)에는 조각을 추가할 수 없다")
        }
        require(fragment.authorId == authorId) { "다른 작성자의 조각은 같은 버스트에 섞일 수 없다" }
        require(fragment.locationKey == location) { "다른 위치(채널/스레드)의 조각은 같은 버스트에 섞일 수 없다" }
        return copy(fragments = (fragments + fragment).sortedWith(MessageFragment.chronology))
    }

    /**
     * 버스트를 종료(OPEN → FINALIZED)한 **새 버스트**를 돌려준다(불변; BurstFinalized 발행 대상).
     * 이미 FINALIZED/CORRECTED 면 거부한다(이중 finalize 금지).
     */
    fun finalize(): UtteranceBurst {
        if (status != BurstStatus.OPEN) {
            throw IllegalBurstTransition("OPEN 이 아닌 버스트($status)는 다시 finalize 할 수 없다")
        }
        return copy(status = BurstStatus.FINALIZED)
    }

    /**
     * 종료된 버스트를 정정(FINALIZED → CORRECTED)한 **새 버스트**를 돌려준다(불변).
     * FINALIZED 에서만 가능 — OPEN(아직 종료 전)·CORRECTED(이미 정정됨)면 거부한다.
     */
    fun correct(): UtteranceBurst {
        if (status != BurstStatus.FINALIZED) {
            throw IllegalBurstTransition("FINALIZED 가 아닌 버스트($status)는 정정할 수 없다")
        }
        return copy(status = BurstStatus.CORRECTED)
    }

    companion object {
        /** 첫 [fragment] 로 새 OPEN 버스트를 시작한다(id 는 첫 조각에서 결정론적으로 파생). */
        fun open(
            guildId: GuildId,
            fragment: MessageFragment,
        ): UtteranceBurst =
            UtteranceBurst(
                burstId = BurstId.fromFirstFragment(fragment),
                guildId = guildId,
                authorId = fragment.authorId,
                location = fragment.locationKey,
                status = BurstStatus.OPEN,
                fragments = listOf(fragment),
            )
    }
}

/** 버스트 상태 전이가 불법 순서일 때(FINALIZED 에 append, 이중 finalize 등). */
class IllegalBurstTransition(
    message: String,
) : IllegalStateException(message)
