package com.discordassistant.central.multiresponse.adapter.outbound.persistence

import com.discordassistant.central.multiresponse.domain.model.CandidateStatus
import com.discordassistant.central.multiresponse.domain.model.MultiResponseRunStatus
import com.discordassistant.central.multiresponse.domain.model.SynthesisStatus
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** multiresponse 도메인 JPA(adapter/out): 정책/run/후보/합성. 전이 가드는 status enum. */

@Entity
@Table(name = "multi_response_policy")
class MultiResponsePolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var channelAiId: Long? = null,
    var mode: String = "single",
    var maxCandidates: Int = 1,
    var requireDistinctModels: Boolean = false,
    var providerDailyLimit: Int = 0,
    var timeoutSeconds: Int = 120,
    var synthesisEnabled: Boolean = false,
    var disabledReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "multi_response_run")
class MultiResponseRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var requestId: String = "",
    var policyId: Long? = null,
    @Convert(converter = MultiResponseRunStatusConverter::class)
    var status: MultiResponseRunStatus = MultiResponseRunStatus.CREATED,
    var candidateCount: Int = 0,
    var selectedCandidateId: Long? = null,
    var ragContextStatus: String? = null,
    var ragContextSourceIds: String? = null,
    var ragContextChars: Int = 0,
    var startedAt: Instant = Instant.EPOCH,
    var finishedAt: Instant? = null,
    var failureReason: String? = null,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([MultiResponseRunStatus] ALLOWED 맵 기준). */
    fun transitionTo(next: MultiResponseRunStatus) {
        require(status.canTransitionTo(next)) { "illegal multi-response run status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "candidate_answer")
class CandidateAnswerEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var runId: Long = 0,
    var providerUserId: Long? = null,
    var modelName: String? = null,
    var answerRef: String? = null,
    @Convert(converter = CandidateStatusConverter::class)
    var status: CandidateStatus = CandidateStatus.PENDING,
    var latencyMs: Int? = null,
    var safetyFlags: String? = null,
    var qualityScore: Int? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "synthesis_result")
class SynthesisResultEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var runId: Long = 0,
    var answerRef: String? = null,
    @Convert(converter = SynthesisStatusConverter::class)
    var status: SynthesisStatus = SynthesisStatus.PENDING,
    var selectedCandidateIds: String? = null,
    var strategy: String = "best_by_heuristic",
    var qualitySummary: String? = null,
    var safetySummary: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)
