package com.discordassistant.central.participation.application.evaluation

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import java.time.Duration

/**
 * 길드·채널별 **분포 비교**(NEXA-P09-T018, application 레이어). 정책의 발화율·delay·action mix 를 그 길드/채널의
 * **문화 기준선**(인간 관찰에서 나온 baseline)과 비교해 정책이 그 장소의 템포에 맞는지 본다.
 *
 * **acceptance(T018) — 고카디널리티 원본 ID 를 외부 metric label 로 내보내지 않는다**:
 * 비교 결과는 [DistributionComparison] 으로, 외부 metric label 로 쓸 키는 **버킷 라벨**([cohortBucket])만이고
 * 원본 guild/channel snowflake 는 담지 않는다. 길드/채널 식별은 가명·집계에만 쓰고, 외부로 내보내는 라벨은
 * 저카디널리티 버킷([CohortBucket])으로 강제한다 — 라벨 폭발·재식별을 막는다.
 *
 * **결정론·재현(제약)**: 같은 입력이면 같은 비교(순수 함수). 도메인 순수성: application — 표준 타입만. Spring/JPA/JDA
 * 미참조. 집계만(원문·개별 사용자 미노출).
 */
object DistributionComparison {
    /**
     * 정책 분포([policy])를 그 cohort 의 인간 기준선([baseline])과 비교한다. [bucket] 은 외부 metric label 로 안전한
     * 저카디널리티 버킷(원본 ID 아님).
     */
    fun compare(
        bucket: CohortBucket,
        policy: ActivityDistribution,
        baseline: ActivityDistribution,
    ): DistributionComparison0 =
        DistributionComparison0(
            cohortBucket = bucket,
            speakRateDelta = policy.speakRate - baseline.speakRate,
            medianDelayDelta = policy.medianDelay.minus(baseline.medianDelay),
            actionMixL1Distance = l1Distance(policy.actionMix, baseline.actionMix),
            policy = policy,
            baseline = baseline,
        )

    /** 두 action mix 분포의 L1 거리(Σ|p−q|, 0=동일, 2=완전 상이). action mix 가 얼마나 다른지의 단일 척도. */
    private fun l1Distance(
        a: Map<SocialActionKind, Double>,
        b: Map<SocialActionKind, Double>,
    ): Double {
        val keys = a.keys + b.keys
        return keys.sumOf { kotlin.math.abs((a[it] ?: 0.0) - (b[it] ?: 0.0)) }
    }
}

/**
 * 한 cohort 의 활동 분포(application 값 객체). 발화율·중앙 delay·action mix. 정책/인간 기준선 양쪽을 같은 형태로
 * 표현해 비교한다. 집계 수치만 — 원문/개별 사용자 비포함.
 */
data class ActivityDistribution(
    /** 발화(SPEAK) 비율 [0,1]. */
    val speakRate: Double,
    /** 행동까지의 중앙 지연. */
    val medianDelay: Duration,
    /** action kind → 비율(합 1.0 권장 — 비교는 정규화 가정 없이 L1 로 본다). */
    val actionMix: Map<SocialActionKind, Double>,
) {
    init {
        require(speakRate in 0.0..1.0) { "speakRate 는 [0,1] 이어야 한다: $speakRate" }
        require(!medianDelay.isNegative) { "medianDelay 는 음수일 수 없다" }
    }
}

/**
 * 외부 metric label 로 안전한 저카디널리티 cohort 버킷(application enum). 원본 guild/channel snowflake 대신 이
 * 버킷만 라벨로 내보낸다(acceptance T018 — 고카디널리티 원본 ID 금지). 길드 규모·채널 활동성 등 **소수 범주**.
 */
enum class CohortBucket {
    /** 소규모·저활동 cohort. */
    SMALL_QUIET,

    /** 소규모·고활동 cohort. */
    SMALL_ACTIVE,

    /** 대규모·저활동 cohort. */
    LARGE_QUIET,

    /** 대규모·고활동 cohort. */
    LARGE_ACTIVE,

    /** 분류 불가/혼합 — 보수적 버킷. */
    MIXED,
}

/**
 * 한 cohort 의 정책 vs 기준선 분포 비교 결과(application 값 객체). 외부 label 키는 [cohortBucket](저카디널리티)뿐 —
 * 원본 ID 비포함(acceptance T018). 이름 충돌 회피로 object 와 다른 클래스명 사용([DistributionComparison0]).
 */
data class DistributionComparison0(
    /** 외부 metric label 로 안전한 버킷(원본 guild/channel ID 아님). */
    val cohortBucket: CohortBucket,
    /** 정책 발화율 − 기준선 발화율(양수=정책이 더 말 많음). */
    val speakRateDelta: Double,
    /** 정책 중앙 delay − 기준선 중앙 delay(양수=정책이 더 느림). */
    val medianDelayDelta: Duration,
    /** action mix L1 거리(0=동일). */
    val actionMixL1Distance: Double,
    val policy: ActivityDistribution,
    val baseline: ActivityDistribution,
)
