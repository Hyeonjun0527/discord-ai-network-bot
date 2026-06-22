package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.ShadowPredictionRecord
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionSummary
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ShadowPredictionStorePort] 의 JPA 구현 어댑터(NEXA-P09-T009, Flyway V59). shadow 예측을 **원문 없이** 정책별
 * 분포·샘플·예상 발사 시점·feature hash 로 영속화한다.
 *
 * **멱등 append(T009)**: (장면, modelVersion) 유니크로 처음이면 insert, 있으면 갱신 — 결정론 예측을 N 번 기록해도
 * 한 행으로 수렴한다.
 *
 * **여러 baseline 비교(acceptance T009)**: [findByScene] 가 (guildPseudonym, channelId, sceneSeq) 한 장면의 모든
 * 정책 예측을 돌려준다.
 *
 * **원문 비저장**: action_weights 는 안정 코드 확률 직렬화, feature 는 hash 만. [toString] 메타만 노출(누출 방지).
 */
@Repository
class JpaShadowPredictionStore(
    private val predictions: NexaShadowPredictionRepository,
) : ShadowPredictionStorePort {
    @Transactional
    override fun append(record: ShadowPredictionRecord) {
        val entity =
            predictions.findByGuildPseudonymAndChannelIdAndSceneSeqAndModelVersion(
                record.scene.guildPseudonym,
                record.scene.channelId,
                record.scene.sceneSeq,
                record.modelVersion,
            ) ?: NexaShadowPredictionEntity()
        entity.guildPseudonym = record.scene.guildPseudonym
        entity.channelId = record.scene.channelId
        entity.sceneSeq = record.scene.sceneSeq
        entity.contextVersion = record.contextVersion
        entity.modelVersion = record.modelVersion
        entity.actionWeights = serializeWeights(record.actionWeights)
        entity.sampledAction = record.sampledAction.wireName
        entity.expectedFireAt = record.expectedFireAt
        entity.seed = record.seed
        entity.featureHash = record.featureHash
        entity.featureVectorVersion = record.featureVectorVersion
        entity.predictedAt = record.predictedAt
        predictions.save(entity)
    }

    @Transactional(readOnly = true)
    override fun findByScene(scene: SceneKey): List<ShadowPredictionRecord> =
        predictions
            .findByGuildPseudonymAndChannelIdAndSceneSeq(scene.guildPseudonym, scene.channelId, scene.sceneSeq)
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun summarizeGuild(guildPseudonym: String): ShadowPredictionSummary {
        val count = predictions.countByGuildPseudonym(guildPseudonym)
        if (count == 0L) return ShadowPredictionSummary(predictionCount = 0, firstPredictedAt = null, lastPredictedAt = null)
        return ShadowPredictionSummary(
            predictionCount = count,
            firstPredictedAt = predictions.minPredictedAt(guildPseudonym),
            lastPredictedAt = predictions.maxPredictedAt(guildPseudonym),
        )
    }

    @Transactional
    override fun purgeExpired(olderThan: Instant): Int = predictions.deleteByPredictedAtBefore(olderThan)

    private fun NexaShadowPredictionEntity.toDomain(): ShadowPredictionRecord =
        ShadowPredictionRecord(
            scene = SceneKey(guildPseudonym = guildPseudonym, channelId = channelId, sceneSeq = sceneSeq),
            contextVersion = contextVersion,
            modelVersion = modelVersion,
            actionWeights = deserializeWeights(actionWeights),
            sampledAction = SocialActionKind.entries.first { it.wireName == sampledAction },
            expectedFireAt = expectedFireAt,
            seed = seed,
            featureHash = featureHash,
            featureVectorVersion = featureVectorVersion,
            predictedAt = predictedAt,
        )

    companion object {
        /** action kind 분포를 "code:prob" 콤마 직렬화한다(안정 코드 — 원문 비포함). */
        fun serializeWeights(weights: Map<SocialActionKind, Double>): String =
            weights.entries.joinToString(",") { (kind, p) -> "${kind.wireName}:$p" }

        /** "code:prob" 콤마 직렬화를 분포로 복원한다(미지 코드는 무시 — fail-soft). */
        fun deserializeWeights(raw: String): Map<SocialActionKind, Double> =
            raw
                .split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { token ->
                    val sep = token.lastIndexOf(':')
                    if (sep <= 0) return@mapNotNull null
                    val code = token.substring(0, sep)
                    val prob = token.substring(sep + 1).toDoubleOrNull() ?: return@mapNotNull null
                    val kind = SocialActionKind.entries.firstOrNull { it.wireName == code } ?: return@mapNotNull null
                    kind to prob
                }.toMap()
    }
}

/**
 * shadow 예측 JPA 엔티티(T009). (장면, modelVersion) 유니크로 멱등 append. 원문/원본 feature 비저장(분포 코드·
 * hash·가명·seed·시각만). [toString] 메타만 노출.
 */
@Entity
@Table(name = "nexa_shadow_prediction")
class NexaShadowPredictionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: String = "",
    @Column(name = "scene_seq") var sceneSeq: Long = 0,
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "model_version") var modelVersion: String = "",
    @Column(name = "action_weights") var actionWeights: String = "",
    @Column(name = "sampled_action") var sampledAction: String = "",
    @Column(name = "expected_fire_at") var expectedFireAt: Instant? = null,
    @Column(name = "seed") var seed: Long = 0,
    @Column(name = "feature_hash") var featureHash: String = "",
    @Column(name = "feature_vector_version") var featureVectorVersion: Int = 1,
    @Column(name = "predicted_at") var predictedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaShadowPredictionEntity(modelVersion=$modelVersion, sampledAction=$sampledAction, predictedAt=$predictedAt)"
}

interface NexaShadowPredictionRepository : JpaRepository<NexaShadowPredictionEntity, Long> {
    fun findByGuildPseudonymAndChannelIdAndSceneSeqAndModelVersion(
        guildPseudonym: String,
        channelId: String,
        sceneSeq: Long,
        modelVersion: String,
    ): NexaShadowPredictionEntity?

    fun findByGuildPseudonymAndChannelIdAndSceneSeq(
        guildPseudonym: String,
        channelId: String,
        sceneSeq: Long,
    ): List<NexaShadowPredictionEntity>

    fun countByGuildPseudonym(guildPseudonym: String): Long

    @Query("SELECT MIN(e.predictedAt) FROM NexaShadowPredictionEntity e WHERE e.guildPseudonym = :guild")
    fun minPredictedAt(
        @Param("guild") guildPseudonym: String,
    ): Instant?

    @Query("SELECT MAX(e.predictedAt) FROM NexaShadowPredictionEntity e WHERE e.guildPseudonym = :guild")
    fun maxPredictedAt(
        @Param("guild") guildPseudonym: String,
    ): Instant?

    @Modifying
    @Query("DELETE FROM NexaShadowPredictionEntity e WHERE e.predictedAt < :olderThan")
    fun deleteByPredictedAtBefore(
        @Param("olderThan") olderThan: Instant,
    ): Int
}
