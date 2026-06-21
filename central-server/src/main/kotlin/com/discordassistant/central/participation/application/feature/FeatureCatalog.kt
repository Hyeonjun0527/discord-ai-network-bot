package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId

/**
 * 정책 feature 카탈로그 — 코드 측 SSOT(NEXA-P08-T009, application 레이어).
 *
 * feature 이름·범위·missing semantics·provenance·privacy class 의 사람이 읽는 SSOT 는
 * `docs/nexa/policy/features.md` 와 `contracts/policy/feature-vector.schema.json` 이다. 이 객체는 그
 * **feature ID 와 메타** 를 코드에서 단일 출처로 노출해, **코드와 ML 데이터셋이 같은 feature ID/version 을 쓰도록**
 * 한다(acceptance T009). 빌더(T010~T013)는 자유 텍스트 키 대신 여기 상수만 쓴다.
 *
 * 순수성 경계: application 레이어 — 표준 타입·[FeatureId] 만. Spring/JPA/JDA 미참조.
 */
object FeatureCatalog {
    /** feature 벡터 버전 — features.schema.json 의 version 과 일치해야 한다(코드·데이터셋 동기화). */
    const val VERSION: Int = 1

    // ── burst features (T010) ─────────────────────────────────────────────────
    val BURST_FRAGMENT_COUNT = define("burst.fragment_count", FeatureType.COUNT, PrivacyClass.OBSERVABLE)
    val BURST_TOTAL_LENGTH = define("burst.total_length", FeatureType.COUNT, PrivacyClass.OBSERVABLE)
    val BURST_GAP_SECONDS = define("burst.gap_seconds", FeatureType.DURATION, PrivacyClass.OBSERVABLE)
    val BURST_IS_QUESTION = define("burst.is_question", FeatureType.BOOLEAN, PrivacyClass.OBSERVABLE)
    val BURST_HAS_MENTION = define("burst.has_mention", FeatureType.BOOLEAN, PrivacyClass.OBSERVABLE)
    val BURST_IS_REPLY = define("burst.is_reply", FeatureType.BOOLEAN, PrivacyClass.OBSERVABLE)
    val BURST_SOURCE_TYPE = define("burst.source_type", FeatureType.CATEGORICAL, PrivacyClass.OBSERVABLE)

    // ── thread / addressee features (T011) ────────────────────────────────────
    val THREAD_FOCUS_PRESENT = define("thread.focus_present", FeatureType.BOOLEAN, PrivacyClass.OBSERVABLE)
    val THREAD_TARGET_ENTROPY = define("thread.target_entropy", FeatureType.NORMALIZED, PrivacyClass.OBSERVABLE)
    val THREAD_ACTIVE_SPEAKERS = define("thread.active_speakers", FeatureType.COUNT, PrivacyClass.OBSERVABLE)
    val THREAD_TOPIC_AGE_SECONDS = define("thread.topic_age_seconds", FeatureType.DURATION, PrivacyClass.OBSERVABLE)

    // ── channel tempo features (T012) ─────────────────────────────────────────
    val TEMPO_HUMAN_BURST_RATE = define("tempo.human_burst_rate", FeatureType.RATE, PrivacyClass.OBSERVABLE)
    val TEMPO_MEDIAN_GAP_SECONDS = define("tempo.median_gap_seconds", FeatureType.DURATION, PrivacyClass.OBSERVABLE)
    val TEMPO_OVERLAP_RATIO = define("tempo.overlap_ratio", FeatureType.NORMALIZED, PrivacyClass.OBSERVABLE)
    val TEMPO_NEXA_SHARE = define("tempo.nexa_share", FeatureType.NORMALIZED, PrivacyClass.OBSERVABLE)

    // ── relationship features (T013) ──────────────────────────────────────────
    val REL_FAMILIARITY = define("relationship.familiarity", FeatureType.NORMALIZED, PrivacyClass.AGGREGATE)
    val REL_RECIPROCITY = define("relationship.reciprocity", FeatureType.NORMALIZED, PrivacyClass.AGGREGATE)
    val REL_BANTER_ACCEPTANCE = define("relationship.banter_acceptance", FeatureType.NORMALIZED, PrivacyClass.AGGREGATE)
    val REL_SAMPLE_CONFIDENCE = define("relationship.sample_confidence", FeatureType.NORMALIZED, PrivacyClass.AGGREGATE)

    // ── memory features (T014) ────────────────────────────────────────────────
    val MEMORY_RELEVANT_PRESENT = define("memory.relevant_present", FeatureType.BOOLEAN, PrivacyClass.AGGREGATE)
    val MEMORY_RELEVANT_CONFIDENCE = define("memory.relevant_confidence", FeatureType.NORMALIZED, PrivacyClass.AGGREGATE)
    val MEMORY_RELEVANT_AGE_SECONDS = define("memory.relevant_age_seconds", FeatureType.DURATION, PrivacyClass.AGGREGATE)
    val MEMORY_PENDING_INTENT_ACTIVE = define("memory.pending_intent_active", FeatureType.BOOLEAN, PrivacyClass.AGGREGATE)

    // ── agent saturation features (T015) ──────────────────────────────────────
    val AGENT_RECENT_BURST_COUNT = define("agent.recent_burst_count", FeatureType.COUNT, PrivacyClass.OBSERVABLE)
    val AGENT_SHARE = define("agent.share", FeatureType.NORMALIZED, PrivacyClass.OBSERVABLE)
    val AGENT_LAST_SPOKE_AGE_SECONDS = define("agent.last_spoke_age_seconds", FeatureType.DURATION, PrivacyClass.OBSERVABLE)
    val AGENT_PENDING_ACTION_COUNT = define("agent.pending_action_count", FeatureType.COUNT, PrivacyClass.OBSERVABLE)

    /** 모든 정의된 feature(ID→메타). 데이터셋·schema 와의 drift 검증·문서 생성에 쓸 수 있다. */
    val all: Map<FeatureId, FeatureMeta> = REGISTRY.toMap()

    private fun define(
        id: String,
        type: FeatureType,
        privacyClass: PrivacyClass,
    ): FeatureId {
        val featureId = FeatureId(id)
        require(REGISTRY.put(featureId, FeatureMeta(featureId, type, privacyClass)) == null) {
            "중복 feature id: $id"
        }
        return featureId
    }
}

private val REGISTRY: MutableMap<FeatureId, FeatureMeta> = linkedMapOf()

/**
 * 한 feature 의 메타(application 값 객체). 타입과 privacy class 를 코드에서 고정한다(features.md SSOT 미러).
 */
data class FeatureMeta(
    val id: FeatureId,
    val type: FeatureType,
    val privacyClass: PrivacyClass,
)

/** feature 값 타입(features.schema.json 의 type 미러). */
enum class FeatureType {
    /** 0..1 정규화 수치. */
    NORMALIZED,

    /** 비음수 카운트. */
    COUNT,

    /** 초 단위 지속(비음수). */
    DURATION,

    /** 분당 비율 등 rate. */
    RATE,

    /** 0/1 불리언. */
    BOOLEAN,

    /** 범주 코드(정수 인코딩). */
    CATEGORICAL,
}

/**
 * feature privacy class(observable-state-policy 미러). 관찰 가능한 행동/집계만 허용하고 민감 추론은 금지한다.
 */
enum class PrivacyClass {
    /** 단일 관찰 행동에서 곧장 나오는 값(observable-state-policy 허용). */
    OBSERVABLE,

    /** 여러 관찰의 집계(빈도·최근성 등 — 성격/감정 추론 아님). */
    AGGREGATE,
}
