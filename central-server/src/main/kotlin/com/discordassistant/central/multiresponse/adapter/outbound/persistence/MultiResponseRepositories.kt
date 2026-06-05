package com.discordassistant.central.multiresponse.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository

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
}

interface CandidateAnswerRepository : JpaRepository<CandidateAnswerEntity, Long> {
    fun findByRunId(runId: Long): List<CandidateAnswerEntity>

    fun findByRunIdAndId(
        runId: Long,
        id: Long,
    ): CandidateAnswerEntity?
}

interface SynthesisResultRepository : JpaRepository<SynthesisResultEntity, Long> {
    fun findByRunId(runId: Long): SynthesisResultEntity?
}
