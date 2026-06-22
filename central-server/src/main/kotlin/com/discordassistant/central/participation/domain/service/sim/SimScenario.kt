package com.discordassistant.central.participation.domain.service.sim

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.DelayBucket

/**
 * NEXA 시뮬레이터 입력/출력 도메인 모델(순수 — Spring/JPA 무의존). 어드민 "NEXA 테스트" 페이지가 보내는
 * **합성·가명 시나리오**(메시지 이벤트 시퀀스)를 표현하고, 재생 결과(이벤트별 NEXA 결정)를 담는다.
 *
 * 운영 데이터·고카디널리티 ID 를 담지 않는다 — 가명 actor/message 라벨만(원문 외부 노출 금지 제약).
 */
data class SimScenario(
    val scenarioId: String,
    val title: String,
    val channelKind: SimChannelKind,
    val seed: Long,
    val actors: List<SimActor>,
    val events: List<SimEvent>,
) {
    init {
        if (scenarioId.isBlank()) throw SimScenarioException("scenarioId 는 비어 있을 수 없다")
        if (actors.isEmpty()) throw SimScenarioException("actors 는 비어 있을 수 없다")
        if (actors.none { it.kind == SimActorKind.NEXA }) throw SimScenarioException("시나리오에는 NEXA actor 가 하나 있어야 한다")
        if (events.isEmpty()) throw SimScenarioException("events 는 비어 있을 수 없다")
        val actorIds = actors.map { it.actorId }.toSet()
        var prevSeq = 0
        var prevOffset = -1L
        val seen = mutableSetOf<String>()
        for (ev in events) {
            if (ev.seq <= prevSeq) throw SimScenarioException("events.seq 는 strictly increasing 이어야 한다")
            prevSeq = ev.seq
            if (ev.atOffsetMs < prevOffset) throw SimScenarioException("events.atOffsetMs 는 non-decreasing 이어야 한다")
            prevOffset = ev.atOffsetMs
            validateEvent(ev, actorIds, seen)
        }
    }

    private fun validateEvent(
        ev: SimEvent,
        actorIds: Set<String>,
        seen: MutableSet<String>,
    ) {
        when (ev.type) {
            SimEventType.MESSAGE_CREATE -> {
                val mid = ev.messageId ?: throw SimScenarioException("message_create 는 messageId 가 필요하다")
                if (!seen.add(mid)) throw SimScenarioException("중복 messageId: $mid")
                val author = ev.authorId ?: throw SimScenarioException("message_create 는 authorId 가 필요하다")
                if (author !in actorIds) throw SimScenarioException("알 수 없는 authorId: $author")
            }
            SimEventType.MESSAGE_UPDATE, SimEventType.MESSAGE_DELETE -> {
                val mid = ev.messageId ?: throw SimScenarioException("${ev.type} 는 messageId 가 필요하다")
                if (mid !in seen) throw SimScenarioException("알 수 없는 메시지 참조: $mid")
            }
            SimEventType.REACTION_ADD, SimEventType.REACTION_REMOVE -> {
                val mid = ev.messageId ?: throw SimScenarioException("${ev.type} 는 messageId 가 필요하다")
                if (mid !in seen) throw SimScenarioException("알 수 없는 메시지 참조: $mid")
            }
            SimEventType.TYPING_START, SimEventType.NICKNAME_CHANGE -> Unit
            SimEventType.FAULT_INJECT -> {
                val fault = ev.fault ?: throw SimScenarioException("fault_inject 는 fault 가 필요하다")
                if (fault !in SUPPORTED_FAULTS) throw SimScenarioException("알 수 없는 fault: $fault")
            }
        }
    }

    companion object {
        /** 시뮬레이터가 처리할 수 있는 주입 결함 코드(SSOT [SimFaults]). */
        val SUPPORTED_FAULTS: Set<String> = SimFaults.ALL
    }
}

/**
 * 주입 가능한 결함 코드의 SSOT(schema.json fault enum 과 동일). 독립 object 로 두어 [SimScenario] ↔
 * [NexaSimulator] companion 의 순환 초기화(ExceptionInInitializerError)를 막는다.
 */
object SimFaults {
    const val POLICY_LATENCY = "policy_latency"
    const val RATE_LIMIT = "rate_limit"
    const val DUPLICATE_EVENT = "duplicate_event"
    const val SEND_FAILURE = "send_failure"
    const val POLICY_TIMEOUT = "policy_timeout"
    const val GLM_TIMEOUT = "glm_timeout"
    const val GLM_LATE = "glm_late"
    const val SCHEDULER_CRASH = "scheduler_crash"
    const val PERMISSION_LOSS = "permission_loss"

    val ALL: Set<String> =
        setOf(
            POLICY_LATENCY,
            RATE_LIMIT,
            DUPLICATE_EVENT,
            SEND_FAILURE,
            POLICY_TIMEOUT,
            GLM_TIMEOUT,
            GLM_LATE,
            SCHEDULER_CRASH,
            PERMISSION_LOSS,
        )
}

/** 채널 종류 — ASSISTANT(무조건 답변) vs MEMBER(사람처럼 participation). */
enum class SimChannelKind {
    ASSISTANT,
    MEMBER,
}

/** 시나리오 참가자(가명). */
data class SimActor(
    val actorId: String,
    val kind: SimActorKind,
    val displayName: String? = null,
)

enum class SimActorKind {
    HUMAN,
    NEXA,
}

/** 재생할 단일 이벤트(가명·합성). 타입별로 필요한 필드만 채운다. */
data class SimEvent(
    val seq: Int,
    val atOffsetMs: Long,
    val type: SimEventType,
    val messageId: String? = null,
    val authorId: String? = null,
    val content: String? = null,
    val mentionsNexa: Boolean = false,
    val threadId: String? = null,
    val fault: String? = null,
)

enum class SimEventType {
    MESSAGE_CREATE,
    MESSAGE_UPDATE,
    MESSAGE_DELETE,
    REACTION_ADD,
    REACTION_REMOVE,
    TYPING_START,
    NICKNAME_CHANGE,
    FAULT_INJECT,
}

/**
 * 재생 결과(이벤트별 NEXA 결정 + 집계). [sends] 는 **항상 0** — shadow only 의 구조적 증거(실제 전송 없음).
 */
data class SimResult(
    val scenarioId: String,
    val channelKind: SimChannelKind,
    val decisions: List<SimDecision>,
    val faults: List<String>,
    val energyLevel: Double,
    val speakCount: Int,
    val reactCount: Int,
    val cancelCount: Int,
) {
    /** shadow 모드 불변식: 실제 Discord 전송 수. 시뮬레이터는 외부 호출을 하지 않으므로 항상 0 이다. */
    val sends: Int = 0

    /** shadow 모드임을 명시(페이지 SHADOW 배지 근거). */
    val shadow: Boolean = true
}

/** 한 평가가 낸 단 하나의 결정 + 사후 재현 근거. */
data class SimDecision(
    val seq: Int,
    val atMs: Long,
    val triggerMessageId: String?,
    val action: SocialActionKind,
    val delayBucket: DelayBucket,
    val targetMessageId: String?,
    val reason: String,
    val consumesGenerationQuota: Boolean,
)

/** 시나리오 로드/검증 실패(순수 도메인 예외 — 어드민 API 가 400 으로 변환). */
class SimScenarioException(
    message: String,
) : IllegalArgumentException(message)
