package com.discordassistant.central.participation.application.shadow

import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * 관리자 shadow 상태 조회 application 서비스(NEXA-P09-T010). 길드별 **모드·수집 기간·예측 수·오류율**을 read-only
 * 로 집계한다.
 *
 * **acceptance(T010) — 원문과 개별 사용자 행동은 기본 응답에 노출되지 않는다**:
 * [statusFor]/[listStatuses] 는 **집계 수치와 단계 코드만** 돌려준다(예측 수·시각 범위·오류율·모드). 개별 예측
 * 레코드(샘플·feature hash 등)나 사용자 행동은 포함하지 않는다.
 *
 * 오류율은 shadow 예측 파이프라인이 실패할 때 [recordError] 로 누적한 카운터(성공은 store 의 예측 수)로 계산한다 —
 * DB schema 를 늘리지 않고 운영 관찰에 충분한 in-memory 집계다(인스턴스 재시작 시 리셋되는 운영 지표).
 */
@Service
class ShadowStatusService(
    private val modeStore: ShadowModeStorePort,
    private val predictionStore: ShadowPredictionStorePort,
) {
    /** guildPseudonym → 누적 예측 오류 수(in-memory 운영 지표). */
    private val errorCounters = ConcurrentHashMap<String, LongAdder>()

    /** shadow 예측 파이프라인 실패 시 호출 — 오류율 분자 누적. */
    fun recordError(guildPseudonym: String) {
        errorCounters.computeIfAbsent(guildPseudonym) { LongAdder() }.increment()
    }

    /** 한 길드의 상태(모드·수집 기간·예측 수·오류율). 집계 수치만(원문/개별 행동 비포함). */
    fun statusFor(guildPseudonym: String): ShadowGuildStatus {
        val mode = modeStore.currentMode(guildPseudonym)
        val summary = predictionStore.summarizeGuild(guildPseudonym)
        val errors = errorCounters[guildPseudonym]?.sum() ?: 0L
        val attempts = summary.predictionCount + errors
        val errorRate = if (attempts == 0L) 0.0 else errors.toDouble() / attempts.toDouble()
        return ShadowGuildStatus(
            guildPseudonym = guildPseudonym,
            mode = mode,
            predictionCount = summary.predictionCount,
            firstPredictedAt = summary.firstPredictedAt,
            lastPredictedAt = summary.lastPredictedAt,
            errorCount = errors,
            errorRate = errorRate,
        )
    }

    /** 모드가 설정된 모든 길드의 상태 목록(집계만). */
    fun listStatuses(): List<ShadowGuildStatus> = modeStore.listModes().map { statusFor(it.guildPseudonym) }

    /**
     * 한 장면에 대한 **정책별 발화율·침묵률** 비교(T011 시각화 데이터). 같은 장면을 여러 baseline 이 어떻게 다르게
     * 봤는지 — SPEAK 확률(발화율)·IGNORE 확률(침묵률)을 정책마다 돌려준다. 집계 분포만(원문/개별 행동 비포함).
     */
    fun comparePolicies(scene: SceneKey): List<PolicyComparisonRow> =
        predictionStore.findByScene(scene).map { record ->
            PolicyComparisonRow(
                modelVersion = record.modelVersion,
                speakRate = record.actionWeights[SocialActionKind.SPEAK] ?: 0.0,
                silenceRate = record.actionWeights[SocialActionKind.IGNORE] ?: 0.0,
                sampledAction = record.sampledAction.wireName,
                expectedFireAt = record.expectedFireAt,
            )
        }
}

/**
 * 한 정책의 장면 비교 행(application 값 객체). 정책별 발화율/침묵률·샘플 결과만(집계 — 원문/개별 행동 비포함).
 */
data class PolicyComparisonRow(
    val modelVersion: String,
    val speakRate: Double,
    val silenceRate: Double,
    val sampledAction: String,
    val expectedFireAt: Instant?,
)

/**
 * 길드별 shadow 상태(application 값 객체). **집계 수치·단계 코드만** — 원문/개별 사용자 행동 비포함(acceptance T010).
 */
data class ShadowGuildStatus(
    val guildPseudonym: String,
    val mode: ShadowMode,
    val predictionCount: Long,
    val firstPredictedAt: Instant?,
    val lastPredictedAt: Instant?,
    val errorCount: Long,
    val errorRate: Double,
)
