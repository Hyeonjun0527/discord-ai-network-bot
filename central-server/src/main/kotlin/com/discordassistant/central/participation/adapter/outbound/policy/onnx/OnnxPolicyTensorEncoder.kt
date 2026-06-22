package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureVectorView

/**
 * PolicyDecisionRequest feature → ONNX 입력 tensor 인코더(NEXA-P11-T018, adapter 레이어).
 *
 * **deliverable(T018) — feature 를 tensor 로 변환한다**: [FeatureCatalog] 의 안정 순서대로 feature 값과 missing
 * mask 를 이어 붙여 `[value(dim) ++ missing(dim)]` float 행을 만든다. 이는 ML 학습 측 design matrix
 * (`features ++ missing_mask`, trainer.design_matrix)와 **같은 차원·순서** 라 Python·JVM 추론이 같은 입력을 본다
 * (T019 parity 의 전제).
 *
 * missing 보존: 값이 부재하거나 [com.discordassistant.central.participation.application.port.out.FeatureValue.missing]
 * 면 value 0.0 + missing 1.0(0 과 '모름' 구분). 카탈로그에 없는 feature 는 입력 차원에 영향을 주지 않는다(무시 —
 * 차원은 카탈로그가 고정).
 *
 * 순수성 경계: adapter 레이어 — application feature 카탈로그·계약 값 객체만. Spring/JPA/JDA·routing/GLM 미참조.
 */
object OnnxPolicyTensorEncoder {
    /** 카탈로그 순서대로 고정된 feature ID 목록(입력 차원 순서의 SSOT). */
    private val ORDERED_IDS = FeatureCatalog.all.keys.toList()

    /** 입력 tensor 차원 — features(dim) + missing mask(dim). ML design matrix 와 동일. */
    val inputDim: Int = ORDERED_IDS.size * 2

    /**
     * [features] 를 `[value(dim) ++ missing(dim)]` float 행으로 인코딩한다(카탈로그 순서). 부재/missing 은
     * value 0.0·missing 1.0.
     */
    fun encode(features: FeatureVectorView): FloatArray {
        val dim = ORDERED_IDS.size
        val row = FloatArray(dim * 2)
        ORDERED_IDS.forEachIndexed { i, id ->
            val cell = features[id]
            if (cell == null || cell.missing) {
                row[i] = 0.0f
                row[dim + i] = 1.0f
            } else {
                row[i] = cell.value.toFloat()
                row[dim + i] = 0.0f
            }
        }
        return row
    }
}
