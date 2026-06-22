package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import kotlin.math.ln

/**
 * thread·addressee feature builder(NEXA-P08-T011, application 레이어·순수 함수). 장면의 thread/addressee 구조에서
 * 정책 feature 를 계산한다 — focus thread 존재, 대상 entropy, active speaker 수, topic age.
 *
 * **acceptance(T011) — 특정 member ID 자체를 모델 feature 로 직접 사용하지 않는다**:
 * 입력의 addressee 확률([ThreadObservation.addresseeProbabilities])은 **member ID 없는 확률 리스트** 다 —
 * 빌더는 이를 정규화 entropy([targetEntropy], 0=한 명에 집중, 1=고르게 분산)로 변환한다. 어떤 member ID 도
 * feature 로 새지 않는다(집계 신호만).
 *
 * participation 은 conversation 도메인 타입을 직접 import 하지 않고 읽기 포트가 채운 [ThreadObservation] 만 본다.
 *
 * 순수성 경계: application 레이어 — 표준·kotlin.math 만. Spring/JPA/JDA 미참조.
 */
object ThreadFeatures {
    fun build(observation: ThreadObservation): Map<FeatureId, FeatureValue> =
        linkedMapOf(
            FeatureCatalog.THREAD_FOCUS_PRESENT to FeatureValue.present(if (observation.hasFocusThread) 1.0 else 0.0),
            FeatureCatalog.THREAD_TARGET_ENTROPY to FeatureValue.present(targetEntropy(observation.addresseeProbabilities)),
            FeatureCatalog.THREAD_ACTIVE_SPEAKERS to FeatureValue.present(observation.activeSpeakerCount.toDouble()),
            FeatureCatalog.THREAD_TOPIC_AGE_SECONDS to FeatureValue.present(observation.topicAgeSeconds),
        )

    /**
     * 정규화 Shannon entropy [0,1] — 대상이 한 곳에 집중(0)인지 고르게 분산(1)인지. member ID 없이 확률만으로
     * 계산한다(acceptance: member ID 미사용). 후보가 0/1 개면 0(분산 없음).
     */
    fun targetEntropy(probabilities: List<Double>): Double {
        val positive = probabilities.filter { it > 0.0 }
        if (positive.size <= 1) return 0.0
        val sum = positive.sum()
        if (sum <= 0.0) return 0.0
        val normalized = positive.map { it / sum }
        val entropy = -normalized.sumOf { it * ln(it) }
        val maxEntropy = ln(positive.size.toDouble())
        return if (maxEntropy <= 0.0) 0.0 else (entropy / maxEntropy).coerceIn(0.0, 1.0)
    }
}

/**
 * thread·addressee 관찰 입력 뷰(application 값 객체). 읽기 포트가 채운다 — **member ID 비포함**(확률 리스트만).
 */
data class ThreadObservation(
    /** 활성 focus thread 가 있는가. */
    val hasFocusThread: Boolean,
    /** addressee 확률 리스트(member ID 없이 확률 값만 — acceptance: ID 미사용). entropy 로 변환된다. */
    val addresseeProbabilities: List<Double>,
    /** 활성 speaker 수. */
    val activeSpeakerCount: Int,
    /** 현재 화제(topic)가 시작된 뒤 경과 초. */
    val topicAgeSeconds: Double,
) {
    init {
        require(activeSpeakerCount >= 0) { "activeSpeakerCount 는 음수일 수 없다" }
        require(topicAgeSeconds >= 0.0) { "topicAgeSeconds 는 음수일 수 없다" }
        addresseeProbabilities.forEach {
            require(it in 0.0..1.0) { "addressee 확률은 [0,1] 범위여야 한다: $it" }
        }
    }
}
