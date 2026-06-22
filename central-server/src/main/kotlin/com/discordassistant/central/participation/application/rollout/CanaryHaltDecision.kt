package com.discordassistant.central.participation.application.rollout

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * Canary 자동 중단 조건의 **순수 결정 코어**(NEXA-P18-T023, application 레이어·순수 함수).
 *
 * canary(또는 LIVE) 운영 중 다음 위반이 관측되면 자동으로 단계를 강등한다(deliverable T023):
 *  - 과다 발화(over_talk): 시간당 발화가 한도 초과.
 *  - complaint: 사용자 불만/신고 누적 초과.
 *  - stale send: due 보다 오래 지연돼 전송된 메시지(폭주 위험) 발생.
 *  - privacy error: 동의/삭제 관련 오류 발생(즉시 중단 사유).
 *  - model mismatch: 사용 model version 이 기대(active)와 다름(rollback 중 혼합 추론).
 *
 * 강등 방향(안전):
 *  - privacy error 또는 model mismatch → [ShadowMode.OFF] 로 즉시 정지(가장 강한 중단 — 정책 평가도 끔).
 *  - over_talk / complaint / stale send → [ShadowMode.SHADOW_PREDICT] 로 강등(관측은 계속하되 실제 전송은 차단).
 *
 * **acceptance(T023)**: [evaluate] 는 위반이 있으면 [CanaryHaltDecision.shouldHalt]=true 와 강등 대상 단계·사유를
 * 돌려준다. 위반이 없거나 이미 전송이 꺼진 단계(allowsRealSend=false)면 no-op([shouldHalt]=false) — 이미 안전.
 * 중단 후 pending 취소·운영자 알림은 서비스([CanaryAutoHaltService])가 이 결정을 받아 수행한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 enum·표준 타입만.
 */
object CanaryHalt {
    /**
     * [currentMode] 와 관측 [signals]·[limits] 로 자동 중단 여부를 판정한다. 실제 전송이 켜진 단계
     * ([ShadowMode.allowsRealSend]=true, 즉 CANARY/LIVE)에서만 중단을 고려한다 — shadow/off 단계는 이미 안전하다.
     *
     * 여러 위반이 동시에 잡히면 **가장 강한 강등**(OFF)을 우선한다(privacy/model 위반이 하나라도 있으면 OFF).
     */
    fun evaluate(
        currentMode: ShadowMode,
        signals: CanarySignals,
        limits: CanaryLimits,
    ): CanaryHaltDecision {
        if (!currentMode.allowsRealSend) {
            return CanaryHaltDecision.noHalt() // 이미 전송이 꺼진 단계 — 중단할 것이 없다.
        }
        val breaches = mutableListOf<HaltReason>()
        if (signals.privacyErrors > 0) breaches += HaltReason.PRIVACY_ERROR
        if (signals.modelMismatches > 0) breaches += HaltReason.MODEL_MISMATCH
        if (signals.utterancesPerHour > limits.maxUtterancesPerHour) breaches += HaltReason.OVER_TALK
        if (signals.complaints > limits.maxComplaints) breaches += HaltReason.COMPLAINT
        if (signals.staleSends > limits.maxStaleSends) breaches += HaltReason.STALE_SEND

        if (breaches.isEmpty()) return CanaryHaltDecision.noHalt()

        // privacy/model 위반은 즉시 OFF(가장 강한 중단). 그 외(과다발화·complaint·stale)는 SHADOW_PREDICT 로 강등.
        val hard = breaches.any { it == HaltReason.PRIVACY_ERROR || it == HaltReason.MODEL_MISMATCH }
        val target = if (hard) ShadowMode.OFF else ShadowMode.SHADOW_PREDICT
        return CanaryHaltDecision(shouldHalt = true, target = target, reasons = breaches.toList())
    }
}

/**
 * canary 관측 신호(application 값 객체·불변). 최근 평가 창에서 모은 위반 카운트다. 원문/PII 비포함 — 숫자만.
 */
data class CanarySignals(
    /** 최근 창의 시간당 발화 수(과다 발화 판정). */
    val utterancesPerHour: Int = 0,
    /** 누적 사용자 불만/신고 수. */
    val complaints: Int = 0,
    /** due 대비 과도 지연 전송(stale) 발생 수. */
    val staleSends: Int = 0,
    /** 동의/삭제 관련 privacy 오류 수(>0 이면 즉시 중단). */
    val privacyErrors: Int = 0,
    /** 사용 model version 불일치(rollback 중 혼합) 발생 수(>0 이면 즉시 중단). */
    val modelMismatches: Int = 0,
) {
    init {
        require(utterancesPerHour >= 0) { "utterancesPerHour 는 0 이상" }
        require(complaints >= 0) { "complaints 는 0 이상" }
        require(staleSends >= 0) { "staleSends 는 0 이상" }
        require(privacyErrors >= 0) { "privacyErrors 는 0 이상" }
        require(modelMismatches >= 0) { "modelMismatches 는 0 이상" }
    }
}

/**
 * canary 자동 중단 한도(application 값 객체·불변). 운영자가 canary 계획(T022)에서 정한 임계값을 담는다.
 * privacy error·model mismatch 는 한도 없이 1건이라도 즉시 중단이므로 별도 한도가 없다.
 */
data class CanaryLimits(
    val maxUtterancesPerHour: Int,
    val maxComplaints: Int,
    val maxStaleSends: Int,
) {
    init {
        require(maxUtterancesPerHour >= 0) { "maxUtterancesPerHour 는 0 이상" }
        require(maxComplaints >= 0) { "maxComplaints 는 0 이상" }
        require(maxStaleSends >= 0) { "maxStaleSends 는 0 이상" }
    }
}

/**
 * 자동 중단 결정(application 값 객체·불변). [shouldHalt] 면 [target] 단계로 강등하고 [reasons] 를 audit·알림에 남긴다.
 */
data class CanaryHaltDecision(
    val shouldHalt: Boolean,
    /** 강등 대상 단계([shouldHalt]=true 일 때만 의미). OFF(즉시 정지) 또는 SHADOW_PREDICT(관측 강등). */
    val target: ShadowMode?,
    val reasons: List<HaltReason>,
) {
    companion object {
        fun noHalt(): CanaryHaltDecision = CanaryHaltDecision(shouldHalt = false, target = null, reasons = emptyList())
    }
}

/** 자동 중단 사유(저카디널리티 안정 코드 — audit·알림에 남긴다). */
enum class HaltReason {
    OVER_TALK,
    COMPLAINT,
    STALE_SEND,
    PRIVACY_ERROR,
    MODEL_MISMATCH,
}
