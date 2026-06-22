package com.discordassistant.central.participation.application.rollout

import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.shadow.ShadowApprovalAuthority
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeTransition
import java.time.Clock
import java.time.Instant

/**
 * Canary 자동 중단 유스케이스(NEXA-P18-T023, application 레이어).
 *
 * 관측 신호([CanarySignals])와 한도([CanaryLimits])로 순수 결정 코어([CanaryHalt])가 중단을 판정하면, 이 서비스가
 * 부수효과를 **원자적 의도**로 수행한다(deliverable T023 — 과다 발화/complaint/stale send/privacy error/model
 * mismatch 시 LIVE→SHADOW/OFF 전환):
 *  1. shadow 단계를 강등한다(LIVE/CANARY → target). 강등이므로 실제 전송을 새로 켜지 않아 일반 운영 권한으로 충분.
 *  2. **acceptance(T023) — 중단 후 pending action 도 취소**: 강등된 길드의 pending 예약을 모두 취소한다.
 *  3. **acceptance(T023) — 운영자 알림**: 무엇이/왜/어디로 강등됐는지 운영자에게 통지한다.
 *
 * 안전 순서: 먼저 단계를 강등(신규 전송 차단)한 뒤 pending 을 취소한다(막은 뒤 청소). 위반이 없거나 이미 전송이
 * 꺼진 단계면 no-op([handle] 이 false 를 돌려준다 — 멱등).
 *
 * 순수성 경계: application — 포트·도메인·[Clock] 만. Spring/JPA/JDA 미참조(어댑터가 와이어).
 */
class CanaryAutoHaltService(
    private val shadowModeStore: ShadowModeStorePort,
    private val cancellation: PendingActionCancellationPort,
    private val operatorAlert: OperatorAlertPort,
    private val clock: Clock,
) {
    /**
     * [guildPseudonym] 의 canary 위반을 평가하고, 중단 조건이면 강등·pending 취소·운영자 알림을 수행한다. 중단을
     * 실행했으면 true, 위반 없음/이미 안전이면 false 를 돌려준다.
     */
    fun handle(
        guildPseudonym: String,
        signals: CanarySignals,
        limits: CanaryLimits,
        actorId: String = SYSTEM_ACTOR,
    ): Boolean {
        val current = shadowModeStore.currentMode(guildPseudonym)
        val decision = CanaryHalt.evaluate(current, signals, limits)
        if (!decision.shouldHalt) return false

        val target = decision.target ?: return false
        val now = Instant.now(clock)

        // 1) 단계 강등(LIVE/CANARY → target). 실제 전송을 새로 켜지 않으므로 운영 권한이면 충분.
        val audit =
            ShadowModeTransition.transition(
                from = current,
                to = target,
                authority = AUTO_HALT_AUTHORITY,
                guildPseudonym = guildPseudonym,
                actorId = actorId,
                reason = "auto-halt:${decision.reasons.joinToString(",") { it.name }}",
                at = now,
            )
        shadowModeStore.applyTransition(audit)

        // 2) 중단 후 pending action 도 취소(막은 뒤 청소 — content 까지).
        val cancelled = cancellation.cancelPendingFor(guildPseudonym)

        // 3) 운영자 알림.
        operatorAlert.notifyAutoHalt(
            CanaryHaltAlert(
                guildPseudonym = guildPseudonym,
                target = target,
                reasons = decision.reasons,
                cancelledPending = cancelled,
            ),
        )
        return true
    }

    private companion object {
        const val SYSTEM_ACTOR = "system-auto-halt"

        // 자동 중단은 단계를 *낮추는* 강등만 한다(실제 전송 활성화 아님) — 운영 권한만 있으면 된다.
        val AUTO_HALT_AUTHORITY =
            ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = false)
    }

    init {
        // 결정 코어가 강등(전송 끄기) 방향으로만 target 을 고른다는 전제 — 방어적 확인은 transition 권한 가드가 한다.
        check(ShadowMode.OFF.allowsRealSend.not()) { "OFF 는 전송 금지 단계여야 한다" }
    }
}
