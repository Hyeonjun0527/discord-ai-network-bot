package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue

/**
 * memory feature builder(NEXA-P08-T014, application 레이어·순수 함수). socialmemory(P07) 읽기 포트가 채운
 * 관련 기억 요약에서 정책 feature 를 만든다 — 관련 기억 존재 여부, confidence, age(최근성), pending intent 활성 여부.
 *
 * **acceptance(T014) — 기억 원문이나 민감 object 가 정책 모델 입력에 불필요하게 포함되지 않는다**:
 * 빌더는 입력 뷰([MemoryObservation])의 **수치 요약만** feature 로 싣는다 — summary 텍스트·subject 가명·topic
 * 문자열·source event ID 같은 식별/원문 object 는 feature 로 새지 않는다. 입력 뷰 자체가 원문을 담지 않도록
 * 숫자/불리언/age(초)만 노출한다(P07 읽기 포트가 기억 aggregate 를 수치로 투영해 넘긴다).
 *
 * 관련 기억이 없으면([MemoryObservation.relevantPresent] = false) 존재 feature 는 0(false)이고 confidence·age 는
 * missing 으로 둔다(0 으로 뭉개지 않음 — content unavailable 과 동일한 missing semantics).
 *
 * participation 은 socialmemory 도메인 타입을 직접 import 하지 않고 읽기 포트가 채운 [MemoryObservation] 만 본다.
 *
 * 순수성 경계: application 레이어 — 표준 타입·[FeatureId]/[FeatureValue]/카탈로그만. Spring/JPA/JDA 미참조.
 */
object MemoryFeatures {
    fun build(observation: MemoryObservation): Map<FeatureId, FeatureValue> {
        // 관련 기억이 없으면 값 feature 는 missing(0 으로 뭉개지 않음 — "모름").
        val relevantFeature: (Double) -> FeatureValue = { v ->
            if (observation.relevantPresent) FeatureValue.present(v) else FeatureValue.MISSING
        }
        return linkedMapOf(
            // 존재 여부·pending intent 활성은 항상 관측 가능(불리언) → present.
            FeatureCatalog.MEMORY_RELEVANT_PRESENT to
                FeatureValue.present(if (observation.relevantPresent) 1.0 else 0.0),
            FeatureCatalog.MEMORY_PENDING_INTENT_ACTIVE to
                FeatureValue.present(if (observation.pendingIntentActive) 1.0 else 0.0),
            // 관련 기억이 있어야 의미 있는 값 → 없으면 missing.
            FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE to relevantFeature(observation.topConfidence),
            FeatureCatalog.MEMORY_RELEVANT_AGE_SECONDS to relevantFeature(observation.freshestAgeSeconds),
        )
    }
}

/**
 * 관련 기억 관찰 입력 뷰(application 값 객체). socialmemory(P07) 읽기 포트가 채운다 — **원문/민감 object 비포함**
 * (수치 요약만, acceptance T014). summary 텍스트·subject 가명·topic·source event ID 는 이 뷰에 들어오지 않는다.
 * [relevantPresent] = false 면 confidence·age 값은 무의미(빌더가 missing 으로 처리).
 */
data class MemoryObservation(
    /** 이 장면에 관련된 기억이 하나라도 있는가(존재 신호 — 원문 아님). */
    val relevantPresent: Boolean,
    /** 가장 관련 깊은 기억의 confidence [0,1](P07 confidence 집계). relevantPresent=false 면 무의미. */
    val topConfidence: Double,
    /** 가장 최신 관련 기억의 age(초, 비음수 — 최근성). relevantPresent=false 면 무의미. */
    val freshestAgeSeconds: Double,
    /** 이 장면 대상에 대해 활성(ACTIVE·미만료) pending intent 가 있는가(P07 PendingIntent 활성 여부). */
    val pendingIntentActive: Boolean,
) {
    init {
        require(topConfidence in 0.0..1.0) { "topConfidence 는 [0,1] 범위여야 한다: $topConfidence" }
        require(freshestAgeSeconds >= 0.0) { "freshestAgeSeconds 는 음수일 수 없다: $freshestAgeSeconds" }
    }
}
