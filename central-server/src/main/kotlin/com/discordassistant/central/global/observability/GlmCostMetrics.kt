package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * GLM 비용·token metric(NEXA-P18-T007). 길드 가명·모델·purpose 별 token 과 추정 비용을 집계한다.
 *
 * **acceptance(T007) — 개별 사용자 ID 를 metric label 로 사용하지 않는다**:
 *  - label 은 **guild 가명**(원본 snowflake 아님)·model·purpose·direction(prompt/completion)뿐 — 개별 user ID 를
 *    절대 label 로 쓰지 않는다([recordUsage] 가 user 식별자를 아예 받지 않는다, T001 taxonomy).
 *  - 비용은 정수 micro-USD 누적(`nexa_glm_cost_micros_total`)으로 센다 — 부동소수 누적 오차를 피한다.
 *
 * guild 가명은 logging-boundary.md 의 안정 가명(snowflake 원문 아님)이라 운영 분해에 쓰되 원문 유출이 없다.
 */
@Component
class GlmCostMetrics(
    private val meter: MeterRegistry,
) {
    /**
     * generation 1건의 token·비용을 기록한다. [guildPseudonym] 은 길드 안정 가명(원문 snowflake 아님),
     * [model] 은 GLM 모델 라벨, [purpose] 는 요청 목적(예: NEXA_SPEECH), [promptTokens]/[completionTokens] 는
     * 방향별 token, [costMicros] 는 추정 비용(micro-USD 정수, 0 이상).
     */
    fun recordUsage(
        guildPseudonym: String,
        model: String,
        purpose: String,
        promptTokens: Long,
        completionTokens: Long,
        costMicros: Long,
    ) {
        require(promptTokens >= 0 && completionTokens >= 0 && costMicros >= 0) {
            "token·비용은 음수일 수 없다"
        }
        meter
            .counter("nexa_glm_tokens_total", "guild", guildPseudonym, "model", model, "purpose", purpose, "direction", "prompt")
            .increment(promptTokens.toDouble())
        meter
            .counter("nexa_glm_tokens_total", "guild", guildPseudonym, "model", model, "purpose", purpose, "direction", "completion")
            .increment(completionTokens.toDouble())
        meter
            .counter("nexa_glm_cost_micros_total", "guild", guildPseudonym, "model", model, "purpose", purpose)
            .increment(costMicros.toDouble())
    }
}
