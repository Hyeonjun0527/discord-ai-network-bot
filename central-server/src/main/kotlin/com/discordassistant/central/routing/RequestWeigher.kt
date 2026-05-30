package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestWeight
import org.springframework.stereotype.Component

/** 요청 메타데이터(무게 판단 입력). */
data class RequestMeta(
    val promptChars: Int,
    val attachments: Int = 0,
    val command: String = "ask",
)

enum class WeighDecision { ACCEPT, DOWNGRADE, REJECT }

/**
 * 무게 판단 결과.
 * - [effectiveBurden]: 실제 처리에 쓸 부담 수준(ACCEPT=required, DOWNGRADE=member 상한, REJECT=null).
 */
data class WeighResult(
    val weight: RequestWeight,
    val requiredBurden: ModelBurden,
    val decision: WeighDecision,
    val effectiveBurden: ModelBurden?,
)

/**
 * 요청 무게 판단 & 필요 모델 수준 결정 (K-차수 8, specs §7 6~7단계). 순수 함수.
 */
@Component
class RequestWeigher {

    /** 메타데이터 → 요청 무게(휴리스틱). 첨부/긴 프롬프트/무거운 명령일수록 무겁다. */
    fun weigh(meta: RequestMeta): RequestWeight {
        if (meta.attachments > 0 || meta.promptChars >= 2000) return RequestWeight.HEAVY
        if (meta.promptChars >= 400 || meta.command in HEAVYISH_COMMANDS) return RequestWeight.MEDIUM
        return RequestWeight.LIGHT
    }

    /**
     * 권한 상한과 필요 수준을 비교해 처리 방식을 정한다.
     * - member 상한 ≥ 필요 → ACCEPT
     * - 1단계 부족 → DOWNGRADE(member 상한으로 처리)
     * - 2단계 이상 부족 → REJECT
     */
    fun resolve(meta: RequestMeta, memberMaxBurden: ModelBurden): WeighResult {
        val weight = weigh(meta)
        val required = weight.requiredBurden()
        val gap = required.ordinal - memberMaxBurden.ordinal
        return when {
            gap <= 0 -> WeighResult(weight, required, WeighDecision.ACCEPT, required)
            gap == 1 -> WeighResult(weight, required, WeighDecision.DOWNGRADE, memberMaxBurden)
            else -> WeighResult(weight, required, WeighDecision.REJECT, null)
        }
    }

    companion object {
        private val HEAVYISH_COMMANDS = setOf("summarize", "digest", "summarize-channels")
    }
}
