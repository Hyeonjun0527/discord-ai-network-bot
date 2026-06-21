package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.model.ModelStatus
import com.discordassistant.central.participation.application.model.ShadowModelCandidate
import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [ShadowModelRegistryPort] 의 JPA 구현 어댑터(NEXA-P11-T020, Flyway V62). 학습 모델 후보(ID·hash·feature
 * schema·calibration·status)를 영속화한다.
 *
 * 어댑터는 순수 저장만 한다 — 승인/LIVE 선택 불변식은 application
 * [com.discordassistant.central.participation.application.model.ShadowModelRegistry] 가 강제한다(미승인 LIVE 금지).
 *
 * 원문 비저장: 모델 신원(코드·hash·버전)과 상태만.
 */
@Repository
class JpaShadowModelRegistryStore(
    private val repository: NexaShadowModelRegistryRepository,
) : ShadowModelRegistryPort {
    @Transactional
    override fun save(candidate: ShadowModelCandidate) {
        val entity =
            repository.findByModelId(candidate.modelId)
                ?: NexaShadowModelRegistryEntity(modelId = candidate.modelId)
        entity.artifactSha256 = candidate.artifactSha256
        entity.modelVersion = candidate.modelVersion
        entity.featureSchemaVersion = candidate.featureSchemaVersion
        entity.calibrationVersion = candidate.calibrationVersion
        entity.status = candidate.status.name
        entity.registeredAt = candidate.registeredAt
        repository.save(entity)
    }

    @Transactional(readOnly = true)
    override fun find(modelId: String): ShadowModelCandidate? = repository.findByModelId(modelId)?.toDomain()

    @Transactional(readOnly = true)
    override fun listAll(): List<ShadowModelCandidate> = repository.findAllByOrderByRegisteredAtDesc().map { it.toDomain() }
}

/** 모델 후보 행(modelId 당 1행). */
@Entity
@Table(name = "nexa_shadow_model_registry")
class NexaShadowModelRegistryEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "model_id") var modelId: String = "",
    @Column(name = "artifact_sha256") var artifactSha256: String = "",
    @Column(name = "model_version") var modelVersion: String = "",
    @Column(name = "feature_schema_version") var featureSchemaVersion: Int = 1,
    @Column(name = "calibration_version") var calibrationVersion: String = "",
    @Column(name = "status") var status: String = ModelStatus.REGISTERED.name,
    @Column(name = "registered_at") var registeredAt: java.time.Instant = java.time.Instant.EPOCH,
) {
    fun toDomain(): ShadowModelCandidate =
        ShadowModelCandidate(
            modelId = modelId,
            artifactSha256 = artifactSha256,
            modelVersion = modelVersion,
            featureSchemaVersion = featureSchemaVersion,
            calibrationVersion = calibrationVersion,
            status = ModelStatus.valueOf(status),
            registeredAt = registeredAt,
        )

    override fun toString(): String = "NexaShadowModelRegistryEntity(modelId=$modelId, status=$status)"
}

interface NexaShadowModelRegistryRepository : JpaRepository<NexaShadowModelRegistryEntity, Long> {
    fun findByModelId(modelId: String): NexaShadowModelRegistryEntity?

    fun findAllByOrderByRegisteredAtDesc(): List<NexaShadowModelRegistryEntity>
}
