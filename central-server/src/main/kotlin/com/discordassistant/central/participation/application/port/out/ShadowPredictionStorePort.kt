package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import java.time.Instant

/**
 * shadow 예측 저장 아웃바운드 포트(NEXA-P09-T009, application 레이어). 같은 장면에 대해 **정책별로** 예측 분포·
 * 샘플 결과·예상 실행 시점·contextVersion 을 **원문 없이** 영속화한다.
 *
 * **acceptance(T009) — 동일 scene 에서 여러 baseline 결과를 비교할 수 있다**:
 * (guildPseudonym, channelId, sceneSeq) 가 한 장면을 가리키고, [findByScene] 가 그 장면에 대한 **모든 정책의**
 * 예측을 돌려준다 — AlwaysSilent/Mention/FixedProb/Cooldown/BurstAware/legacy 를 나란히 비교한다.
 *
 * 순수성 경계: application 레이어 — 도메인 코드·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ShadowPredictionStorePort {
    /** 예측 한 건을 추가한다. 같은 (scene, modelVersion) 재기록은 멱등(중복 1건 — 결정론 재현). */
    fun append(record: ShadowPredictionRecord)

    /** 한 장면([SceneKey])에 대한 모든 정책의 예측을 돌려준다(여러 baseline 비교 — acceptance T009). */
    fun findByScene(scene: SceneKey): List<ShadowPredictionRecord>

    /**
     * [guildPseudonym] 의 수집 요약 — 예측 수·수집 시작/끝 시각(집계 수치만, 원문/개별 사용자 행동 비포함).
     * 관리자 상태 API(T010)가 길드별 수집 기간·예측 수를 노출하는 데 쓴다. 행이 없으면 모두 0/null.
     */
    fun summarizeGuild(guildPseudonym: String): ShadowPredictionSummary

    /** [olderThan] 보다 오래된 예측을 삭제하고 건수를 돌려준다(보존 정책). */
    fun purgeExpired(olderThan: Instant): Int
}

/**
 * 길드별 shadow 예측 수집 요약(application 값 객체). **집계 수치만** — 원문/개별 사용자 행동 비포함(acceptance T010).
 */
data class ShadowPredictionSummary(
    /** 이 길드에 기록된 총 예측 수. */
    val predictionCount: Long,
    /** 수집 시작 시각(가장 오래된 예측, 없으면 null). */
    val firstPredictedAt: Instant?,
    /** 수집 끝 시각(가장 최근 예측, 없으면 null). */
    val lastPredictedAt: Instant?,
)

/**
 * 한 장면 식별 키(application 값 객체). 같은 키의 여러 예측이 같은 장면에 대한 정책별 결과다.
 */
data class SceneKey(
    val guildPseudonym: String,
    val channelId: String,
    val sceneSeq: Long,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
    }
}

/**
 * shadow 예측 레코드(application 값 객체·불변). **원문 비포함** — 정책별 분포·샘플 결과·예상 실행 시점·provenance만.
 *
 * 분포는 action kind 별 확률([actionWeights])로 직렬화하고, 샘플은 seed 로 접은 단일 행동([sampledAction])과 그
 * 예상 발사 시각([expectedFireAt], NEVER 면 null)을 담는다. shadow 라 절대 실제 전송되지 않는다(T008).
 */
data class ShadowPredictionRecord(
    /** 장면 키(여러 정책 비교의 join 축). */
    val scene: SceneKey,
    /** 정책 무효화 추적 context 버전(이 버전 기준 예측 — 재현 키). */
    val contextVersion: Long,
    /** 이 예측을 낸 정책/모델 버전(AlwaysSilent/Mention/… 식별). */
    val modelVersion: String,
    /** 예측 분포: action kind → 확률(합 1.0). 정책별 발화/침묵 성향 비교의 핵심. */
    val actionWeights: Map<SocialActionKind, Double>,
    /** seed 로 접은 단일 샘플 행동(분포→하나). */
    val sampledAction: SocialActionKind,
    /** 예상 발사 시각(샘플 delay 적용, NEVER/IGNORE 면 null — 발사 안 함). */
    val expectedFireAt: Instant?,
    /** 결정론 seed(재현 키). */
    val seed: Long,
    /** 근거 feature 벡터 해시(원본 feature 비저장 — 재현/diff). */
    val featureHash: String,
    /** feature 벡터 버전(재현 시 같은 schema). */
    val featureVectorVersion: Int,
    /** 예측 생성 시각(보존/수집 기간 기준). */
    val predictedAt: Instant,
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(actionWeights.isNotEmpty()) { "actionWeights 는 비어 있을 수 없다" }
        require(featureHash.isNotBlank()) { "featureHash 는 비어 있을 수 없다" }
        require(featureVectorVersion >= 1) { "featureVectorVersion 은 1 이상이어야 한다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
    }
}
