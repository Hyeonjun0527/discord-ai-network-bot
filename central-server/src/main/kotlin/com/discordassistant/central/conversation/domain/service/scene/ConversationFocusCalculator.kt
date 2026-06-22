package com.discordassistant.central.conversation.domain.service.scene

import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 현재 대화 초점 계산기(NEXA-P05-T016, 순수 함수). 활성 thread, 최근 addressee target, pending(OPEN) burst 라는
 * 세 구조 신호를 모아 [ConversationFocus] 분포를 만든다.
 *
 * 가중 합(KISS baseline) — 각 스레드의 점수 = 신호 가중치 합:
 * - 활성 thread: [FocusWeights.activeThread]
 * - 최근 target: [FocusWeights.recentTarget]
 * - pending burst: [FocusWeights.pendingBurst]
 * 점수를 정규화해 확률로 만들고, 남은 질량을 idle 로 둔다(신호가 약할수록 idle 이 커진다).
 *
 * **acceptance(T016) — 활성 스레드 없음을 정상 상태로**: 세 신호가 모두 비면 [ConversationFocus.idle] 을 돌려준다
 * (idle = 1.0). 신호가 있어도 총점이 작으면 idle 질량이 남아 "약한 초점" 을 표현한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음.
 */
object ConversationFocusCalculator {
    /**
     * 세 구조 신호로 초점 분포를 만든다.
     *
     * @param activeThreads 최근 발화가 있던 스레드들.
     * @param recentTargetThreads 최근 addressee target 이 가리킨 스레드들.
     * @param pendingBurstThreads 진행 중(OPEN) burst 가 있는 스레드들.
     * @param weights 신호별 가중치.
     * @param ruleVersion 분포 provenance.
     */
    fun calculate(
        activeThreads: Set<ConversationThreadId>,
        recentTargetThreads: Set<ConversationThreadId>,
        pendingBurstThreads: Set<ConversationThreadId>,
        weights: FocusWeights = FocusWeights.DEFAULT,
        ruleVersion: String,
    ): ConversationFocus {
        val threads = activeThreads + recentTargetThreads + pendingBurstThreads
        if (threads.isEmpty()) return ConversationFocus.idle(ruleVersion)

        // 각 스레드의 raw 점수와 evidence 를 모은다(입력 순서 보존을 위해 LinkedHashMap).
        data class Scored(
            val score: Double,
            val evidence: Set<FocusEvidence>,
        )
        val scored = LinkedHashMap<ConversationThreadId, Scored>()
        for (thread in threads) {
            var score = 0.0
            val evidence = linkedSetOf<FocusEvidence>()
            if (thread in activeThreads) {
                score += weights.activeThread
                evidence += FocusEvidence.ACTIVE_THREAD
            }
            if (thread in recentTargetThreads) {
                score += weights.recentTarget
                evidence += FocusEvidence.RECENT_TARGET
            }
            if (thread in pendingBurstThreads) {
                score += weights.pendingBurst
                evidence += FocusEvidence.PENDING_BURST
            }
            scored[thread] = Scored(score, evidence)
        }

        // idle 기준 질량을 더한 총합으로 정규화 — 신호 총점이 작을수록 idle 확률이 커진다.
        val signalTotal = scored.values.sumOf { it.score }
        val denominator = signalTotal + weights.idleBaseline
        val candidates =
            scored.map { (thread, s) ->
                FocusCandidate(
                    threadId = thread,
                    probability = s.score / denominator,
                    evidence = s.evidence,
                )
            }
        val idle = weights.idleBaseline / denominator
        return ConversationFocus(
            candidates = candidates,
            idleProbability = idle,
            ruleVersion = ruleVersion,
        )
    }
}

/**
 * 초점 신호 가중치(주입 — 순수 함수가 상태를 갖지 않도록 인자로 받는다). [idleBaseline] 은 항상 남는 idle 질량으로,
 * 신호가 약하면 idle 이 커지도록 한다(0 이면 idle 질량 없음 — 신호 있으면 확신).
 */
data class FocusWeights(
    val activeThread: Double = 1.0,
    val recentTarget: Double = 1.5,
    val pendingBurst: Double = 1.2,
    /** 정규화 시 항상 더해지는 idle 기준 질량(>0 이면 약한 신호에서 idle 확률을 남긴다). */
    val idleBaseline: Double = 0.5,
) {
    init {
        require(activeThread >= 0.0) { "activeThread 가중치는 음수일 수 없다" }
        require(recentTarget >= 0.0) { "recentTarget 가중치는 음수일 수 없다" }
        require(pendingBurst >= 0.0) { "pendingBurst 가중치는 음수일 수 없다" }
        require(idleBaseline >= 0.0) { "idleBaseline 은 음수일 수 없다" }
    }

    companion object {
        val DEFAULT: FocusWeights = FocusWeights()
    }
}
