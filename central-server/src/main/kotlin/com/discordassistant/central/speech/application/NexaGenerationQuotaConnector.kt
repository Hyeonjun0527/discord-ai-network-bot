package com.discordassistant.central.speech.application

import com.discordassistant.central.speech.application.generation.GateResult
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.GenerationQuotaPort
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * generation-only quota 차감 연결자(NEXA-P15-T011, speech application).
 *
 * 발화 생성 호출 경계([SpeechGenerationGate])에 quota reserve/settle 을 끼운다 — **모델 호출이 실제로 일어나는
 * SPEAK 직전에만** [GenerationQuotaPort.reserve] 하고, 성공/실패에 따라 settle 한다.
 *
 * **acceptance(T011) — IGNORE/REACT·생성 전 취소는 quota 를 소비하지 않는다**:
 *  - SPEAK 가 아니면(IGNORE/WAIT/REACT/OTHER) gate 가 generation 을 호출하지 않으므로 **reserve 도 하지 않는다**
 *    → 차감 0(quota-boundary.md 불변식 1: 침묵 판단은 quota 무소모).
 *  - SPEAK 라도 stale/consent revoke 면 gate 가 호출 전에 건너뛴다(생성 전 취소) → reserve 안 함 → 차감 0.
 *  - 한도 초과로 reserve 가 false 면 generation 을 **호출하지 않는다**(발화 보류) → 모델 비용·차감 0.
 *  - reserve 성공 + generation 결과 있음 → settleCharged(확정 차감, 모델 비용 발생).
 *  - reserve 성공 + generation 빈 결과(호출 실패/무응답) → settleRefund(부당 차감 방지, 불변식 3).
 *
 * 중복 차감 방지: [correlationId](decision↔requestlog 연결 키)로 멱등(불변식 2 — generation 1회당 1회만 차감).
 *
 * 순수성: application — gate·port·도메인 packet 만. Spring/JPA/JDA·routing/GLM 미참조.
 */
class NexaGenerationQuotaConnector(
    private val gate: SpeechGenerationGate,
    private val quota: GenerationQuotaPort,
) {
    /**
     * [trigger] 가 SPEAK 이고 유효하면 quota reserve 후 generation 을 호출한다. 차감은 generation 직전에만 일어나고,
     * 결과에 따라 settle 한다. SPEAK 가 아니거나 reserve 실패면 generation·차감 둘 다 일어나지 않는다.
     */
    fun generateWithQuota(
        trigger: SpeechTrigger,
        guildId: Long,
        userId: Long,
        correlationId: String,
        packet: SpeechScenePacket,
        stale: Boolean = false,
        consentRevoked: Boolean = false,
    ): QuotaGateResult {
        // SPEAK 가 아니거나 stale/revoke 면 gate 가 generation 을 안 한다 — reserve 도 하지 않는다(차감 0).
        if (trigger != SpeechTrigger.SPEAK || stale || consentRevoked) {
            val gr = gate.generateIfSpeaking(trigger, packet, stale, consentRevoked)
            return QuotaGateResult(gate = gr, quotaCharged = false, reserved = false)
        }
        // SPEAK 유효 경로: generation 직전 reserve. 한도 초과면 호출하지 않는다(발화 보류).
        if (!quota.reserve(guildId, userId, correlationId)) {
            return QuotaGateResult(gate = GateResult.skipped(quotaBlockedReason()), quotaCharged = false, reserved = false)
        }
        val gr = gate.generateIfSpeaking(trigger, packet, stale = false, consentRevoked = false)
        return if (gr.invokedGeneration && !gr.result.isEmpty) {
            quota.settleCharged(guildId, userId, correlationId)
            QuotaGateResult(gate = gr, quotaCharged = true, reserved = true)
        } else {
            // 호출했지만 무응답/빈 결과 → 부당 차감 방지(환불).
            quota.settleRefund(guildId, userId, correlationId)
            QuotaGateResult(gate = gr, quotaCharged = false, reserved = true)
        }
    }

    /** reserve 실패(한도)도 "SPEAK 가 아님" 과 구분되게 NOT_SPEAK 가 아닌 STALE 외 사유로 둘 수 없어 gate 의 NOT_SPEAK 를 재사용한다. */
    private fun quotaBlockedReason() = com.discordassistant.central.speech.application.generation.SkipReason.NOT_SPEAK
}

/**
 * quota 게이트 결과(NEXA-P15-T011). gate 결과 + 실제 차감 여부를 함께 노출(테스트·감사).
 */
data class QuotaGateResult(
    /** 발화 생성 게이트 결과(invokedGeneration 으로 routing 호출 여부 노출). */
    val gate: GateResult,
    /** 실제로 quota 가 확정 차감됐는가(SPEAK·generation 성공일 때만 true). */
    val quotaCharged: Boolean,
    /** quota reserve 가 일어났는가(SPEAK 유효 경로에서만 true — IGNORE/REACT 는 false). */
    val reserved: Boolean,
)
