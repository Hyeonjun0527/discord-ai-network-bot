package com.discordassistant.central.global.observability

/**
 * 과다 발화 alert 평가기(NEXA-P18-T011, 순수 평가 로직).
 *
 * NEXA 가 **너무 많이 말하는** 신호(점유율·연속 burst·mention 응답 spike·queue backlog)를 임계와 비교해 alert 를
 * 낸다. 이 평가기는 순수 함수다 — 외부 호출·전송 없이 입력 측정값만으로 판정한다(실제 알림 발송·downgrade 발동은
 * 운영/어댑터 책임, human_gate).
 *
 * **acceptance(T011) — alert 발생 시 자동 downgrade/kill 조건과 사람 확인이 구분된다**:
 *  - 각 신호는 두 임계를 가진다: warn(=[NexaAlertResponse.HUMAN_CONFIRM] — 사람이 보고 판단)·critical
 *    (=[NexaAlertResponse.AUTO_DOWNGRADE] — 즉시 자동 강등/kill 권고). 결과 [NexaOverTalkAlert] 가 어느
 *    response 인지 명시해 "자동 조치"와 "사람 확인"이 코드/대시보드에서 구분된다.
 *  - 어떤 신호도 임계를 안 넘으면 결과는 빈 목록(정상).
 *
 * 순수성: 표준 타입만. Spring/JPA/JDA 미참조. 임계는 [NexaOverTalkThresholds] 로 주입(운영 튜닝).
 */
class NexaOverTalkAlertEvaluator(
    private val thresholds: NexaOverTalkThresholds = NexaOverTalkThresholds.DEFAULT,
) {
    /**
     * 한 시점의 측정값을 평가해 발생한 alert 목록을 돌려준다(없으면 빈 목록). 각 신호는 critical 임계를 먼저 보고
     * (자동 강등), 아니면 warn(사람 확인)을 본다.
     */
    fun evaluate(signals: NexaOverTalkSignals): List<NexaOverTalkAlert> =
        buildList {
            classify(
                NexaOverTalkSignalKind.SHARE_RATIO,
                signals.shareRatio,
                thresholds.shareWarn,
                thresholds.shareCritical,
            )?.let(::add)
            classify(
                NexaOverTalkSignalKind.CONSECUTIVE_BURSTS,
                signals.consecutiveBursts.toDouble(),
                thresholds.consecutiveBurstsWarn.toDouble(),
                thresholds.consecutiveBurstsCritical.toDouble(),
            )?.let(::add)
            classify(
                NexaOverTalkSignalKind.MENTION_RESPONSE_SPIKE,
                signals.mentionResponsesPerMin,
                thresholds.mentionSpikeWarn,
                thresholds.mentionSpikeCritical,
            )?.let(::add)
            classify(
                NexaOverTalkSignalKind.QUEUE_BACKLOG,
                signals.queueBacklog.toDouble(),
                thresholds.queueBacklogWarn.toDouble(),
                thresholds.queueBacklogCritical.toDouble(),
            )?.let(::add)
        }

    private fun classify(
        kind: NexaOverTalkSignalKind,
        value: Double,
        warn: Double,
        critical: Double,
    ): NexaOverTalkAlert? =
        when {
            value >= critical -> NexaOverTalkAlert(kind, NexaAlertResponse.AUTO_DOWNGRADE, value)
            value >= warn -> NexaOverTalkAlert(kind, NexaAlertResponse.HUMAN_CONFIRM, value)
            else -> null
        }
}

/** 한 시점의 과다 발화 측정값(집계 — 원문/개별 사용자 비포함). */
data class NexaOverTalkSignals(
    /** NEXA burst 점유율 [0,1](최근 창). */
    val shareRatio: Double,
    /** 인간 사이 NEXA 연속 burst 수. */
    val consecutiveBursts: Int,
    /** 분당 mention 응답 수(spike 감지). */
    val mentionResponsesPerMin: Double,
    /** 전송 대기 queue backlog 크기. */
    val queueBacklog: Int,
)

/** 과다 발화 신호 종류(저카디널리티). */
enum class NexaOverTalkSignalKind {
    SHARE_RATIO,
    CONSECUTIVE_BURSTS,
    MENTION_RESPONSE_SPIKE,
    QUEUE_BACKLOG,
}

/**
 * alert 발생 시 권고 response — 자동 조치와 사람 확인을 **구분**한다(acceptance T011).
 */
enum class NexaAlertResponse {
    /** warn 임계 — 사람이 보고 판단(자동 조치 없음). */
    HUMAN_CONFIRM,

    /** critical 임계 — 즉시 자동 강등/kill 권고(운영 자동화가 발동). */
    AUTO_DOWNGRADE,
}

/** 발생한 과다 발화 alert 1건. */
data class NexaOverTalkAlert(
    val signal: NexaOverTalkSignalKind,
    val response: NexaAlertResponse,
    /** 임계를 넘긴 측정값(감사·대시보드 표시용). */
    val observedValue: Double,
)

/** 과다 발화 alert 임계(운영 튜닝). warn=사람 확인, critical=자동 강등. */
data class NexaOverTalkThresholds(
    val shareWarn: Double,
    val shareCritical: Double,
    val consecutiveBurstsWarn: Int,
    val consecutiveBurstsCritical: Int,
    val mentionSpikeWarn: Double,
    val mentionSpikeCritical: Double,
    val queueBacklogWarn: Int,
    val queueBacklogCritical: Int,
) {
    companion object {
        /** 보수적 기본 임계(docs/nexa/operations/alerts.md 와 일치). */
        val DEFAULT =
            NexaOverTalkThresholds(
                shareWarn = 0.35,
                shareCritical = 0.5,
                consecutiveBurstsWarn = 3,
                consecutiveBurstsCritical = 5,
                mentionSpikeWarn = 10.0,
                mentionSpikeCritical = 20.0,
                queueBacklogWarn = 20,
                queueBacklogCritical = 50,
            )
    }
}
