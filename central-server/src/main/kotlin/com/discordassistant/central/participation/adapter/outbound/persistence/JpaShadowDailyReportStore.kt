package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.ShadowDailyReportStorePort
import com.discordassistant.central.participation.application.reporting.PolicyDailyStat
import com.discordassistant.central.participation.application.reporting.ShadowDailyReport
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * [ShadowDailyReportStorePort] 의 JPA 구현 어댑터(NEXA-P09-T021, Flyway V61). 하루치 집계 리포트를 **원문 없이**
 * (집계 수치만) 영속화한다.
 *
 * **멱등 save(T021)**: 같은 [ShadowDailyReport.date] 재저장은 기존 행 갱신(정책 줄 교체) — 재집계를 N 번 돌려도
 * 한 리포트로 수렴(결정론 재현).
 *
 * 원문 비저장: 예측량·발화 share·proxy 비율·오류·누락 수치만. 개별 사용자 행동/원문 미저장.
 */
@Repository
class JpaShadowDailyReportStore(
    private val reports: NexaShadowDailyReportRepository,
) : ShadowDailyReportStorePort {
    @Transactional
    override fun save(report: ShadowDailyReport) {
        val entity = reports.findByReportDate(report.date) ?: NexaShadowDailyReportEntity()
        entity.reportDate = report.date
        entity.errorCount = report.errorCount
        entity.dataGapCount = report.dataGapCount
        entity.policyStats.clear()
        report.perPolicy.forEach { stat ->
            entity.policyStats.add(
                NexaShadowDailyPolicyStatEntity(
                    modelVersion = stat.modelVersion,
                    predictionCount = stat.predictionCount,
                    speakShare = stat.speakShare,
                    falseInterruptionRate = stat.falseInterruptionRate,
                    missedInterventionRate = stat.missedInterventionRate,
                ),
            )
        }
        reports.save(entity)
    }

    @Transactional(readOnly = true)
    override fun findByDate(date: LocalDate): ShadowDailyReport? = reports.findByReportDate(date)?.toDomain()

    private fun NexaShadowDailyReportEntity.toDomain(): ShadowDailyReport =
        ShadowDailyReport(
            date = reportDate,
            perPolicy =
                policyStats.map {
                    PolicyDailyStat(
                        modelVersion = it.modelVersion,
                        predictionCount = it.predictionCount,
                        speakShare = it.speakShare,
                        falseInterruptionRate = it.falseInterruptionRate,
                        missedInterventionRate = it.missedInterventionRate,
                    )
                },
            errorCount = errorCount,
            dataGapCount = dataGapCount,
        )
}

/** shadow 일일 리포트 헤더(날짜당 1행). 집계 수치만 — 원문/개별 사용자 비저장. */
@Entity
@Table(name = "nexa_shadow_daily_report")
class NexaShadowDailyReportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "report_date") var reportDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "error_count") var errorCount: Int = 0,
    @Column(name = "data_gap_count") var dataGapCount: Int = 0,
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "report_id")
    var policyStats: MutableList<NexaShadowDailyPolicyStatEntity> = mutableListOf(),
) {
    override fun toString(): String = "NexaShadowDailyReportEntity(reportDate=$reportDate, policies=${policyStats.size})"
}

/** 정책별 하루 집계 줄(리포트당 N행). */
@Entity
@Table(name = "nexa_shadow_daily_policy_stat")
class NexaShadowDailyPolicyStatEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "model_version") var modelVersion: String = "",
    @Column(name = "prediction_count") var predictionCount: Long = 0,
    @Column(name = "speak_share") var speakShare: Double? = null,
    @Column(name = "false_interruption_rate") var falseInterruptionRate: Double? = null,
    @Column(name = "missed_intervention_rate") var missedInterventionRate: Double? = null,
) {
    override fun toString(): String = "NexaShadowDailyPolicyStatEntity(modelVersion=$modelVersion, count=$predictionCount)"
}

interface NexaShadowDailyReportRepository : JpaRepository<NexaShadowDailyReportEntity, Long> {
    fun findByReportDate(reportDate: LocalDate): NexaShadowDailyReportEntity?
}
