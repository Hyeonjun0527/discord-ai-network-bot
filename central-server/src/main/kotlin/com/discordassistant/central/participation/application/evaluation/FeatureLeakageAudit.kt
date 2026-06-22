package com.discordassistant.central.participation.application.evaluation

import com.discordassistant.central.participation.application.feature.FeatureMeta
import com.discordassistant.central.participation.application.feature.PrivacyClass
import com.discordassistant.central.participation.application.port.out.FeatureId
import java.time.Instant

/**
 * shadow feature **leakage 감사기**(NEXA-P09-T023, application 레이어). 한 예측의 feature 들이 **미래 정보**(예측
 * 시점 이후 관찰)나 **label 누설** 없이 만들어졌는지 관찰 신호로 검사한다.
 *
 * **acceptance(T023) — 각 feature 의 cutoff timestamp 와 source watermark 가 검증된다**:
 * [audit] 는 예측 cutoff([predictedAt])와 각 feature 의 [FeatureWatermark.observedAt](그 feature 가 본 가장 최근
 * 관찰 시각)를 비교해, **watermark 가 cutoff 를 넘으면**(미래 leakage) 위반으로 보고한다. 또한 카탈로그가 허용한
 * privacy class(OBSERVABLE/AGGREGATE)만 쓰는지 확인한다(observable-state-policy — 금지 추론 미사용).
 *
 * 정의·점검 방법·테스트 매핑은 [docs/nexa/experiments/EXP-shadow-leakage-audit.md] 참조.
 *
 * **결정론·재현(제약)**: 같은 입력이면 같은 감사 결과(순수 함수). 도메인 순수성: application — 표준 타입·feature
 * 메타만. Spring/JPA/JDA 미참조.
 */
object FeatureLeakageAudit {
    /**
     * 한 예측의 feature watermark 들([watermarks])이 cutoff([predictedAt])를 넘지 않는지, 그리고 허용 privacy class
     * 만 쓰는지 감사한다. [allowedClasses] 는 observable-state-policy 가 허용하는 class(기본 OBSERVABLE/AGGREGATE).
     */
    fun audit(
        predictedAt: Instant,
        watermarks: List<FeatureWatermark>,
        allowedClasses: Set<PrivacyClass> = setOf(PrivacyClass.OBSERVABLE, PrivacyClass.AGGREGATE),
    ): LeakageAuditResult {
        // 미래 leakage: feature 가 cutoff 이후 관찰을 봤다(예측 시점 이후 정보가 들어감).
        val futureLeaks =
            watermarks
                .filter { it.observedAt.isAfter(predictedAt) }
                .map { LeakageViolation(it.featureId, LeakageKind.FUTURE_OBSERVATION) }
        // 금지 추론: 카탈로그가 허용하지 않은 privacy class.
        val forbiddenClass =
            watermarks
                .filter { it.meta.privacyClass !in allowedClasses }
                .map { LeakageViolation(it.featureId, LeakageKind.FORBIDDEN_PRIVACY_CLASS) }
        return LeakageAuditResult(violations = futureLeaks + forbiddenClass)
    }
}

/**
 * 한 feature 의 source watermark(application 값 객체). 그 feature 가 **본 가장 최근 관찰 시각**과 카탈로그 메타.
 * cutoff 와 비교해 미래 leakage 를 검출한다(acceptance T023).
 */
data class FeatureWatermark(
    val featureId: FeatureId,
    /** 이 feature 가 집계에 사용한 가장 최근 관찰 시각(source watermark). */
    val observedAt: Instant,
    /** 카탈로그 메타(privacy class 검증용). */
    val meta: FeatureMeta,
)

/** leakage 감사 결과(application 값 객체). 위반 목록(비면 clean). */
data class LeakageAuditResult(
    val violations: List<LeakageViolation>,
) {
    /** leakage 없음(모든 feature 가 cutoff 이내·허용 class). */
    val isClean: Boolean
        get() = violations.isEmpty()
}

/** 한 feature 의 leakage 위반(application 값 객체). */
data class LeakageViolation(
    val featureId: FeatureId,
    val kind: LeakageKind,
)

/** leakage 종류(application enum). */
enum class LeakageKind {
    /** feature watermark 가 예측 cutoff 이후 관찰을 봤다(미래 정보 누설). */
    FUTURE_OBSERVATION,

    /** 카탈로그가 허용하지 않은 privacy class(금지 추론 사용). */
    FORBIDDEN_PRIVACY_CLASS,
}
