package com.discordassistant.central.multiresponse.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** multiresponse Spring Data JPA 리포지토리(adapter/out). */

interface MultiResponsePolicyRepository : JpaRepository<MultiResponsePolicyEntity, Long> {
    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long?,
    ): MultiResponsePolicyEntity?

    fun findByGuildIdAndChannelIdIsNull(guildId: Long): MultiResponsePolicyEntity?
}

interface MultiResponseRunRepository : JpaRepository<MultiResponseRunEntity, Long> {
    fun findByRequestId(requestId: String): MultiResponseRunEntity?

    fun findTop20ByGuildIdOrderByStartedAtDesc(guildId: Long): List<MultiResponseRunEntity>

    /** 보존 기간을 지난(관측 시작이 [cutoff] 이전) run 을 정리 대상으로 조회한다. */
    fun findByStartedAtBefore(cutoff: Instant): List<MultiResponseRunEntity>
}

interface CandidateAnswerRepository : JpaRepository<CandidateAnswerEntity, Long> {
    fun findByRunId(runId: Long): List<CandidateAnswerEntity>

    fun findByRunIdAndId(
        runId: Long,
        id: Long,
    ): CandidateAnswerEntity?

    fun deleteByRunIdIn(runIds: Collection<Long>)
}

interface SynthesisResultRepository : JpaRepository<SynthesisResultEntity, Long> {
    fun findByRunId(runId: Long): SynthesisResultEntity?

    fun deleteByRunIdIn(runIds: Collection<Long>)
}
