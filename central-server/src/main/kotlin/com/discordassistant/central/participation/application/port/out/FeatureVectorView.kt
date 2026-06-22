package com.discordassistant.central.participation.application.port.out

/**
 * 정책 feature 벡터 뷰(NEXA-P08-T009 backing, application 값 객체·불변).
 *
 * 결정 엔진 입력으로 쓰이는 **정규화된 feature 집합** 이다. 각 feature 는 안정 ID([FeatureId])로 식별되고,
 * 수치 값과 **missing 여부**(content unavailable 등)를 보존한다. feature 의 이름/타입/범위/missing semantics/
 * provenance/privacy class 의 SSOT 는 contracts/policy/feature-vector.schema.json + docs/nexa/policy/features.md.
 *
 * **acceptance(T009 연동) — 코드와 ML 데이터셋이 같은 feature ID/version 을 쓴다**:
 * [version] 으로 벡터 버전을 고정하고, feature 는 자유 텍스트 키가 아니라 안정 [FeatureId] 로만 들어간다.
 *
 * **missing 보존(T010 연동)**: 값이 없으면 빠뜨리지 않고 [FeatureValue.missing] 로 표시한다 — content unavailable
 * 에서도 missing flag 가 보존되어 정책이 "모름" 을 구분할 수 있다(0 으로 뭉개지 않음).
 *
 * **member ID 직접 사용 금지(T011 연동)**: feature 값은 수치/정규화 신호다 — 특정 member ID 를 feature 로
 * 직접 싣지 않는다(빌더가 entropy·share 같은 집계로 변환한다).
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
data class FeatureVectorView(
    /** feature ID → 값(missing 보존). 같은 ID 중복 금지. */
    val features: Map<FeatureId, FeatureValue>,
    /** feature 벡터 버전(코드·데이터셋 동기화 — features.schema 의 version). */
    val version: Int,
) {
    init {
        require(version >= 1) { "feature 벡터 version 은 1 이상이어야 한다: $version" }
    }

    /** [id] 의 값(없으면 null — 해당 feature 가 벡터에 아예 부재). missing 과 부재는 다르다. */
    operator fun get(id: FeatureId): FeatureValue? = features[id]

    /** [id] 가 존재하고 missing 이 아니며 실제 수치를 갖는가. */
    fun hasValue(id: FeatureId): Boolean = features[id]?.let { !it.missing } ?: false

    companion object {
        /** 빈 벡터(feature 없음). 결정 입력 부재 상태의 seed. */
        fun empty(version: Int): FeatureVectorView = FeatureVectorView(emptyMap(), version)

        /** [pairs] 로 벡터를 만든다(빌더 합성용 편의). */
        fun of(
            version: Int,
            pairs: Map<FeatureId, FeatureValue>,
        ): FeatureVectorView = FeatureVectorView(pairs, version)
    }
}

/**
 * 안정 feature 식별자(application 값 객체). 자유 텍스트가 아니라 SSOT 카탈로그(features.md)의 안정 코드다 —
 * 코드와 ML 데이터셋이 같은 ID 를 공유한다(acceptance T009).
 */
@JvmInline
value class FeatureId(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "feature id 는 비어 있을 수 없다" }
    }
}

/**
 * 한 feature 의 값(application 값 객체). missing semantics 를 1급으로 보존한다 — [missing] = true 면 [value] 는
 * 의미 없음(content unavailable·표본 부족 등). 정책은 missing 을 0 과 구분해 다룬다.
 */
data class FeatureValue(
    /** 정규화 수치(missing 이면 무의미, 관례상 0.0). */
    val value: Double,
    /** 값을 관측할 수 없었는가(content unavailable, 표본 부족 등). true 면 정책이 "모름" 으로 다룬다. */
    val missing: Boolean = false,
) {
    companion object {
        /** 관측된 수치 feature. */
        fun present(value: Double): FeatureValue = FeatureValue(value, missing = false)

        /** 관측 불가 feature(missing flag 보존). */
        val MISSING: FeatureValue = FeatureValue(0.0, missing = true)
    }
}
