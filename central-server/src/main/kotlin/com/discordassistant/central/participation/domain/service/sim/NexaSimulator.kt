package com.discordassistant.central.participation.domain.service.sim

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.DelayBucket

/**
 * NEXA 이벤트 재생 시뮬레이터(NEXA-P16 의 central 측 레퍼런스 모델, 어드민 "NEXA 테스트" 페이지용).
 *
 * scripts/nexa-simulate.py 와 **동일한 결정론 규칙**(mention 우선·share cap·cooldown·human-answer 재평가·
 * stale cancel·edit/delete 추적·결함 fail-safe)을 순수 Kotlin 으로 옮긴 모델이다. 시나리오 이벤트열을
 * virtual clock·seeded LCG 로 재생해 NEXA 의 participation 결정(IGNORE/WAIT/REACT/SPEAK/CANCEL_PENDING +
 * 타이밍)을 만든다.
 *
 * **shadow only(핵심 안전 계약)**: 실제 Discord 전송·실제 GLM 호출이 **0** 이다. 이 클래스는 외부 호출을
 * 일절 하지 않는 순수 함수 — [SimResult.sends] 는 항상 0 이고, SPEAK 도 "발화하기로 결정" 만 기록한다.
 *
 * **순수성**: Spring/JPA/JDA·adapter·CloudLlm 타입을 일절 참조하지 않는다(participation.domain 규칙,
 * NexaArchitectureTest.nexaDomainsArePure). 행동·타이밍 어휘는 도메인 enum([SocialActionKind]/[DelayBucket])
 * 을 그대로 쓰므로 Python 시뮬레이터와의 drift 는 NexaSimulatorVocabularyTest 가 잡는다.
 */
class NexaSimulator(
    private val scenario: SimScenario,
) {
    private val rng = SeededRandom(scenario.seed)
    private val nexaActorIds: Set<String> =
        scenario.actors
            .filter { it.kind == SimActorKind.NEXA }
            .map { it.actorId }
            .toSet()
    private val messages: MutableMap<String, MessageState> = LinkedHashMap()
    private val decisions: MutableList<SimDecision> = mutableListOf()
    private val faults: MutableList<String> = mutableListOf()

    // 정책 상태(결정론적 잠재 상태).
    private var energy = 0.5
    private val energyBaseline = 0.5
    private var energyUpdatedMs = 0L
    private var lastSpeakMs: Long? = null
    private val recentWindowSpeaks: MutableList<Long> = mutableListOf()
    private val recentWindowHumanMsgs: MutableList<Long> = mutableListOf()
    private var pending: PendingSpeak? = null
    private var mentionSpeaksInBurst = 0
    private var lastMentionBurstAnchorMs: Long? = null

    // 견고성/장애 상태(fail-safe: 한 번 차단되면 발화하지 않는다 — 침묵 fallback).
    private var speakBlocked = false
    private val firedTargets: MutableSet<String> = mutableSetOf()

    /** 시나리오를 재생해 결정 궤적을 만든다(결정론·전송 0). */
    fun run(): SimResult {
        for (event in scenario.events) {
            dispatch(event)
        }
        // 시나리오 끝: 아직 안 발사된 예약을 발사 시도(맥락 안 바뀌었으면 정상 SPEAK 로 확정).
        scenario.events.lastOrNull()?.let { maybeFirePending(it.atOffsetMs) }
        val speaks = decisions.count { it.action == SocialActionKind.SPEAK }
        return SimResult(
            scenarioId = scenario.scenarioId,
            channelKind = scenario.channelKind,
            decisions = decisions.toList(),
            faults = faults.toList(),
            energyLevel = energy,
            speakCount = speaks,
            reactCount = decisions.count { it.action == SocialActionKind.REACT },
            cancelCount = decisions.count { it.action == SocialActionKind.CANCEL_PENDING },
        )
    }

    private fun dispatch(event: SimEvent) {
        val atMs = event.atOffsetMs
        // 매 이벤트 직전, 예약된 SPEAK 의 발사 시점이 지났으면 발사/취소 판단(stale 검사).
        maybeFirePending(atMs)
        when (event.type) {
            SimEventType.MESSAGE_CREATE -> onMessageCreate(event, atMs)
            SimEventType.MESSAGE_UPDATE -> onMessageUpdate(event)
            SimEventType.MESSAGE_DELETE -> onMessageDelete(event, atMs)
            SimEventType.FAULT_INJECT -> onFault(event, atMs)
            // 관찰만(견고성 경로). 발화 결정을 직접 만들지 않는다 — nickname/typing/reaction 은 트리거 아님.
            SimEventType.NICKNAME_CHANGE,
            SimEventType.TYPING_START,
            SimEventType.REACTION_ADD,
            SimEventType.REACTION_REMOVE,
            -> Unit
        }
    }

    private fun onMessageCreate(
        event: SimEvent,
        atMs: Long,
    ) {
        val authorId = requireField(event.authorId, "authorId")
        val msg =
            MessageState(
                messageId = requireField(event.messageId, "messageId"),
                authorId = authorId,
                content = event.content.orEmpty(),
                mentionsNexa = event.mentionsNexa,
                createdMs = atMs,
                threadId = event.threadId,
            )
        messages[msg.messageId] = msg

        if (authorId in nexaActorIds) return // NEXA 자기 메시지는 트리거 아님.

        recentWindowHumanMsgs.add(atMs)

        // 같은 thread 에서 다른 사람이 먼저 답하면 예약을 취소한다(human-answer 재평가, cross-thread 오취소 방지).
        val pend = pending
        if (pend != null && isHumanAnswerToPending(msg, pend, atMs)) {
            emit(
                event.seq,
                atMs,
                msg.messageId,
                SocialActionKind.CANCEL_PENDING,
                DelayBucket.IMMEDIATE,
                pend.targetMessageId,
                "human answered before pending speak fired",
            )
            pending = null
        }

        evaluate(event, msg, atMs)
    }

    private fun isHumanAnswerToPending(
        msg: MessageState,
        pend: PendingSpeak,
        atMs: Long,
    ): Boolean {
        if (msg.messageId == pend.targetMessageId) return false
        if (atMs - pend.scheduledAtMs > HUMAN_ANSWER_WINDOW_MS) return false
        // 같은 thread 의 후속 메시지 = 그 대화가 사람들끼리 진행/해결됨 → NEXA 중복 답변 회피.
        return msg.threadId == pend.threadId
    }

    private fun onMessageUpdate(event: SimEvent) {
        val msg = messages[event.messageId] ?: return
        msg.content = event.content.orEmpty()
        msg.mentionsNexa = event.mentionsNexa
        msg.revision += 1
        // 예약 SPEAK 대상이 수정되면 최신 revision 을 따른다(예약 유지·최신 내용 기준).
    }

    private fun onMessageDelete(
        event: SimEvent,
        atMs: Long,
    ) {
        val msg = messages[event.messageId] ?: return
        msg.deleted = true
        val pend = pending
        if (pend != null && pend.targetMessageId == event.messageId) {
            emit(
                event.seq,
                atMs,
                null,
                SocialActionKind.CANCEL_PENDING,
                DelayBucket.IMMEDIATE,
                event.messageId,
                "pending speak target deleted",
            )
            pending = null
        }
    }

    /**
     * 주입된 결함을 처리한다(견고성/장애 시나리오). 모든 결함의 공통 안전 계약: stale 전송·중복 발화·다른 채널
     * fallback 을 만들지 않는다 — fail-safe = 침묵/취소.
     */
    private fun onFault(
        event: SimEvent,
        atMs: Long,
    ) {
        val fault = requireField(event.fault, "fault")
        faults.add(fault)
        when {
            fault == FAULT_POLICY_LATENCY -> Unit // fire window 가 지나면 _maybeFirePending 의 stale 취소가 처리.
            fault == FAULT_DUPLICATE_EVENT -> Unit // 멱등: 중복 전달은 새 결정/발화를 만들지 않는다.
            fault in FAULTS_BLOCK_SPEAK -> {
                // fail-safe: 예약 취소 + 이후 발화 영구 차단(다른 채널 fallback 없음).
                speakBlocked = true
                pending?.let {
                    emit(
                        event.seq,
                        atMs,
                        null,
                        SocialActionKind.CANCEL_PENDING,
                        DelayBucket.IMMEDIATE,
                        it.targetMessageId,
                        "fault $fault: cancel pending, fall back to silence (no cross-channel)",
                    )
                    pending = null
                }
            }
            fault == FAULT_SCHEDULER_CRASH -> {
                // crash 는 미발사 예약을 폐기(복구 경로는 firedTargets 가드로 멱등 → 중복 발화 0).
                pending?.let {
                    emit(
                        event.seq,
                        atMs,
                        null,
                        SocialActionKind.CANCEL_PENDING,
                        DelayBucket.IMMEDIATE,
                        it.targetMessageId,
                        "fault scheduler_crash: drop in-flight pending (recovery is idempotent)",
                    )
                    pending = null
                }
            }
        }
    }

    private fun evaluate(
        event: SimEvent,
        msg: MessageState,
        atMs: Long,
    ) {
        decayEnergy(atMs)
        trimWindows(atMs)

        // ASSISTANT 채널: 무조건 답변(AI 질문 채널). MEMBER 채널: 사람처럼 participation.
        if (scenario.channelKind == SimChannelKind.ASSISTANT) {
            scheduleSpeak(event, msg, atMs, "assistant channel always answers")
            return
        }
        if (msg.mentionsNexa) {
            handleMention(event, msg, atMs)
            return
        }
        // 호명 없음: 사람다운 멤버는 대부분 침묵한다(over-conservative 가 아니라 무례 회피).
        unaddressedDecision(event, msg, atMs)
    }

    private fun handleMention(
        event: SimEvent,
        msg: MessageState,
        atMs: Long,
    ) {
        val anchor = lastMentionBurstAnchorMs
        val inBurst = anchor != null && atMs - anchor <= COOLDOWN_AFTER_SPEAK_MS
        mentionSpeaksInBurst = if (inBurst) mentionSpeaksInBurst + 1 else 1
        lastMentionBurstAnchorMs = atMs

        // share cap: 최근 창 점유율이 cap 을 넘으면 연속 발화 억제 → 가벼운 reaction 만.
        if (overShareCap(atMs)) {
            emit(
                event.seq,
                atMs,
                msg.messageId,
                SocialActionKind.REACT,
                DelayBucket.IMMEDIATE,
                msg.messageId,
                "mention but share cap reached -> light reaction only",
            )
            return
        }
        // burst 안 2번째 이상 mention 은 매번 SPEAK 하지 않는다(과반응 방지) — REACT 또는 침묵.
        if (mentionSpeaksInBurst >= 2) {
            val react = mentionSpeaksInBurst == 2
            val action = if (react) SocialActionKind.REACT else SocialActionKind.IGNORE
            emit(
                event.seq,
                atMs,
                msg.messageId,
                action,
                DelayBucket.IMMEDIATE,
                if (react) msg.messageId else null,
                "repeated mention #$mentionSpeaksInBurst in burst -> no 1:1 reply",
            )
            return
        }
        // 정상: 첫 mention 은 답한다(진지한 직접 질문이면 생성 경로 정확히 한 번 열림).
        scheduleSpeak(event, msg, atMs, "addressed first mention")
    }

    private fun unaddressedDecision(
        event: SimEvent,
        msg: MessageState,
        atMs: Long,
    ) {
        val cooldownClear = lastSpeakMs.let { it == null || atMs - it >= COOLDOWN_AFTER_SPEAK_MS }
        val roll = rng.nextDouble()
        if (cooldownClear && !overShareCap(atMs) && energy > 0.7 && roll < 0.15) {
            emit(
                event.seq,
                atMs,
                msg.messageId,
                SocialActionKind.REACT,
                DelayBucket.SHORT,
                msg.messageId,
                "unaddressed but light social reaction (energy high)",
            )
            return
        }
        emit(
            event.seq,
            atMs,
            msg.messageId,
            SocialActionKind.IGNORE,
            DelayBucket.NEVER,
            null,
            "not addressed -> stay silent (human-like restraint)",
        )
    }

    private fun scheduleSpeak(
        event: SimEvent,
        msg: MessageState,
        atMs: Long,
        reason: String,
    ) {
        if (speakBlocked) {
            // fail-safe: 결함으로 발화가 차단된 상태면 새 예약을 만들지 않는다(침묵 fallback).
            emit(
                event.seq,
                atMs,
                msg.messageId,
                SocialActionKind.IGNORE,
                DelayBucket.NEVER,
                null,
                "speak blocked by injected fault -> stay silent (graceful failure)",
            )
            return
        }
        // SPEAK 는 즉시 확정하지 않고 짧게 예약 → 그 사이 사람이 답하거나 대상이 바뀌면 stale cancel.
        pending =
            PendingSpeak(
                decisionSeq = event.seq,
                targetMessageId = msg.messageId,
                scheduledAtMs = atMs,
                fireAtMs = atMs + PENDING_FIRE_DELAY_MS,
                threadId = msg.threadId,
            )
        // 예약 자체는 WAIT 결정으로 기록(아직 발화 아님 — 타이밍 보류).
        emit(
            event.seq,
            atMs,
            msg.messageId,
            SocialActionKind.WAIT,
            DelayBucket.SHORT,
            msg.messageId,
            "scheduled speak ($reason); awaiting fire window",
        )
    }

    private fun maybeFirePending(nowMs: Long) {
        val pend = pending ?: return
        if (nowMs < pend.fireAtMs) return
        val target = messages[pend.targetMessageId]
        // fail-safe: 발화 차단(결함) 또는 이미 발사된 대상(멱등)이면 발사하지 않는다.
        if (speakBlocked || pend.targetMessageId in firedTargets) {
            val reason =
                if (speakBlocked) {
                    "speak blocked by injected fault at fire time -> silence"
                } else {
                    "target already spoken (idempotent) -> no duplicate"
                }
            emit(
                pend.decisionSeq,
                pend.fireAtMs,
                null,
                SocialActionKind.CANCEL_PENDING,
                DelayBucket.IMMEDIATE,
                pend.targetMessageId,
                reason,
            )
            pending = null
            return
        }
        if (target == null || target.deleted) {
            emit(
                pend.decisionSeq,
                pend.fireAtMs,
                null,
                SocialActionKind.CANCEL_PENDING,
                DelayBucket.IMMEDIATE,
                pend.targetMessageId,
                "pending speak target gone at fire time",
            )
            pending = null
            return
        }
        // 발사 확정 → SPEAK(최신 revision 기준). shadow 모드라 실제 전송은 없다.
        emit(
            pend.decisionSeq,
            pend.fireAtMs,
            null,
            SocialActionKind.SPEAK,
            DelayBucket.IMMEDIATE,
            pend.targetMessageId,
            "fired speak on latest revision (rev=${target.revision})",
        )
        firedTargets.add(pend.targetMessageId)
        lastSpeakMs = pend.fireAtMs
        recentWindowSpeaks.add(pend.fireAtMs)
        nudgeEnergy(-0.1, pend.fireAtMs)
        pending = null
    }

    private fun emit(
        eventSeq: Int,
        atMs: Long,
        trigger: String?,
        action: SocialActionKind,
        delay: DelayBucket,
        target: String?,
        reason: String,
    ) {
        decisions.add(
            SimDecision(
                seq = eventSeq,
                atMs = atMs,
                triggerMessageId = trigger,
                action = action,
                delayBucket = delay,
                targetMessageId = target,
                reason = reason,
                consumesGenerationQuota = action == SocialActionKind.SPEAK,
            ),
        )
    }

    private fun decayEnergy(atMs: Long) {
        energy = energyDecay(energy, energyBaseline, atMs - energyUpdatedMs)
        energyUpdatedMs = atMs
    }

    private fun nudgeEnergy(
        delta: Double,
        atMs: Long,
    ) {
        decayEnergy(atMs)
        energy = minOf(1.0, maxOf(0.0, energy + delta))
    }

    private fun trimWindows(atMs: Long) {
        val windowStart = atMs - WINDOW_MS
        recentWindowSpeaks.removeAll { it < windowStart }
        recentWindowHumanMsgs.removeAll { it < windowStart }
    }

    private fun overShareCap(atMs: Long): Boolean {
        trimWindows(atMs)
        val speaks = recentWindowSpeaks.size
        val total = speaks + recentWindowHumanMsgs.size
        if (total == 0) return false
        return speaks.toDouble() / total >= SHARE_CAP && speaks >= 2
    }

    private fun requireField(
        value: String?,
        name: String,
    ): String = value ?: throw SimScenarioException("event field '$name' is required")

    companion object {
        // 정책 상수(scripts/nexa-simulate.py 와 동일한 결정론 레퍼런스 값).
        private const val SHARE_CAP = 0.5
        private const val COOLDOWN_AFTER_SPEAK_MS = 8_000L
        private const val HUMAN_ANSWER_WINDOW_MS = 12_000L
        private const val PENDING_FIRE_DELAY_MS = 4_000L
        private const val ENERGY_DECAY_HALF_LIFE_MS = 6L * 60 * 60 * 1000
        private const val WINDOW_MS = 5L * 60 * 1000 // 최근 5분 창.

        // 주입 가능한 결함 코드(schema.json fault enum 과 동일) — SSOT 는 [SimFaults]. 호환 alias.
        const val FAULT_POLICY_LATENCY = SimFaults.POLICY_LATENCY
        const val FAULT_RATE_LIMIT = SimFaults.RATE_LIMIT
        const val FAULT_DUPLICATE_EVENT = SimFaults.DUPLICATE_EVENT
        const val FAULT_SEND_FAILURE = SimFaults.SEND_FAILURE
        const val FAULT_POLICY_TIMEOUT = SimFaults.POLICY_TIMEOUT
        const val FAULT_GLM_TIMEOUT = SimFaults.GLM_TIMEOUT
        const val FAULT_GLM_LATE = SimFaults.GLM_LATE
        const val FAULT_SCHEDULER_CRASH = SimFaults.SCHEDULER_CRASH
        const val FAULT_PERMISSION_LOSS = SimFaults.PERMISSION_LOSS

        // 발사 자체를 영구 차단하는(침묵 fallback 으로 끝내는) 결함 — fail-safe.
        private val FAULTS_BLOCK_SPEAK =
            setOf(FAULT_RATE_LIMIT, FAULT_SEND_FAILURE, FAULT_POLICY_TIMEOUT, FAULT_GLM_TIMEOUT, FAULT_GLM_LATE, FAULT_PERMISSION_LOSS)

        /** SocialEnergy.decayed 의 레퍼런스: baseline 으로 지수 회귀. */
        private fun energyDecay(
            level: Double,
            baseline: Double,
            elapsedMs: Long,
        ): Double {
            if (elapsedMs <= 0) return level
            val retain = Math.pow(0.5, elapsedMs.toDouble() / ENERGY_DECAY_HALF_LIFE_MS)
            return baseline + (level - baseline) * retain
        }
    }
}

/** 결정론 LCG(Numerical Recipes 파라미터). unseeded Random 금지 — 같은 seed·이벤트열이면 같은 궤적. */
internal class SeededRandom(
    seed: Long,
) {
    private var state: Long = seed

    fun nextDouble(): Double {
        state = 6364136223846793005L * state + 1442695040888963407L
        // 상위 53비트를 [0,1) 로(Python 시뮬레이터의 `(state >> 11) / 2^53` 과 동일, unsigned 시프트).
        return (state ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

/** 재생 중 메시지의 가변 상태(content/mention/revision/deleted 추적). */
internal class MessageState(
    val messageId: String,
    val authorId: String,
    var content: String,
    var mentionsNexa: Boolean,
    val createdMs: Long,
    val threadId: String?,
    var deleted: Boolean = false,
    var revision: Int = 0,
)

/** 예약된 SPEAK — 발사 전 맥락이 바뀌면 CANCEL/재평가(stale 전송 금지). */
internal class PendingSpeak(
    val decisionSeq: Int,
    val targetMessageId: String,
    val scheduledAtMs: Long,
    val fireAtMs: Long,
    val threadId: String?,
)
