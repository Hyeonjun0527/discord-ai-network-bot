package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue

/**
 * burst feature builder(NEXA-P08-T010, application 레이어·순수 함수). 한 발화 버스트 관찰에서 정책 feature 를
 * 계산한다 — 조각 수, 총 길이, 조각 간 gap, 질문/멘션/답글 여부, source type.
 *
 * **acceptance(T010) — content unavailable 에서도 missing flag 를 보존한다**:
 * 입력([BurstObservation])의 콘텐츠 파생 신호([contentAvailable] = false)면 length·is_question 처럼 **본문이
 * 있어야 계산되는 feature 를 0 으로 뭉개지 않고** [FeatureValue.MISSING] 로 둔다(missing semantics 보존).
 * 본문 없이도 관측 가능한 메타(조각 수·gap·멘션/답글 메타·source type)는 정상 값으로 채운다.
 *
 * participation 은 conversation 도메인 타입을 직접 import 하지 않는다 — 읽기 포트가 채우는 자기 입력 뷰
 * ([BurstObservation])만 소비한다(module-dag.md: 읽기 포트로만 연결).
 *
 * 순수성 경계: application 레이어 — 표준 타입·[FeatureId]/[FeatureValue]/카탈로그만. Spring/JPA/JDA 미참조.
 */
object BurstFeatures {
    /** [observation] 으로부터 burst feature 들을 만든다. content unavailable 면 본문 파생 feature 는 missing. */
    fun build(observation: BurstObservation): Map<FeatureId, FeatureValue> {
        val contentFeature: (Double) -> FeatureValue = { v ->
            if (observation.contentAvailable) FeatureValue.present(v) else FeatureValue.MISSING
        }
        return linkedMapOf(
            // 메타(본문 불필요) — 항상 present.
            FeatureCatalog.BURST_FRAGMENT_COUNT to FeatureValue.present(observation.fragmentCount.toDouble()),
            FeatureCatalog.BURST_GAP_SECONDS to FeatureValue.present(observation.gapSeconds),
            FeatureCatalog.BURST_HAS_MENTION to FeatureValue.present(observation.hasMention.toFeature()),
            FeatureCatalog.BURST_IS_REPLY to FeatureValue.present(observation.isReply.toFeature()),
            FeatureCatalog.BURST_SOURCE_TYPE to FeatureValue.present(observation.sourceType.code.toDouble()),
            // 본문 파생 — content unavailable 이면 missing 보존(0 으로 뭉개지 않음).
            FeatureCatalog.BURST_TOTAL_LENGTH to contentFeature(observation.totalLength.toDouble()),
            FeatureCatalog.BURST_IS_QUESTION to contentFeature(observation.isQuestion.toFeature()),
        )
    }

    private fun Boolean.toFeature(): Double = if (this) 1.0 else 0.0
}

/**
 * burst 관찰 입력 뷰(application 값 객체). conversation 읽기 포트가 채우는 participation 자기 입력이다 —
 * 원문 비포함(메타·카운트·코드만). [contentAvailable] = false 면 본문 파생 필드는 신뢰할 수 없다(missing 처리).
 */
data class BurstObservation(
    /** 조각 수(본문 불필요 메타). */
    val fragmentCount: Int,
    /** 총 글자 길이(본문 파생 — contentAvailable=false 면 무의미). */
    val totalLength: Int,
    /** 직전 burst 와의 gap(초). */
    val gapSeconds: Double,
    /** 질문 형태인가(본문 파생). */
    val isQuestion: Boolean,
    /** 멘션 포함(메타 — 본문 불필요). */
    val hasMention: Boolean,
    /** 답글인가(메타 — 본문 불필요). */
    val isReply: Boolean,
    /** burst source 종류(메타). */
    val sourceType: BurstSourceType,
    /** 본문(content) 파생 feature 를 신뢰할 수 있는가 — false 면 본문 파생 feature 는 missing 으로 둔다. */
    val contentAvailable: Boolean,
) {
    init {
        require(fragmentCount >= 0) { "fragmentCount 는 음수일 수 없다" }
        require(totalLength >= 0) { "totalLength 는 음수일 수 없다" }
        require(gapSeconds >= 0.0) { "gapSeconds 는 음수일 수 없다" }
    }
}

/** burst source 종류(application enum). 정수 [code] 로 categorical feature 에 인코딩된다. */
enum class BurstSourceType(
    val code: Int,
) {
    HUMAN(0),
    NEXA(1),
    OTHER_BOT(2),
}
