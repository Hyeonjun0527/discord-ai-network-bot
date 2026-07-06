package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * multi-response 관측 행 보존 정리기. run/candidate/synthesis 는 관측 목적이라 무한히 쌓이면 테이블이
 * 무제한으로 커진다. 보존 기간([retentionDays])을 지난 run 을 자식(candidate/synthesis)까지 지운다.
 * 자식 먼저 → run 순으로 지워 FK(ON DELETE CASCADE 유무와 무관)를 안전하게 만족한다.
 */
@Service
class MultiResponseRetentionService(
    private val runs: MultiResponseRunRepository,
    private val candidates: CandidateAnswerRepository,
    private val syntheses: SynthesisResultRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * [retentionDays] 보다 오래된 run 과 그 자식 행을 삭제한다(최소 1일 보장). 반환: 삭제한 run 수.
     */
    @Transactional
    fun purgeExpired(
        retentionDays: Long,
        now: Instant = Instant.now(clock),
    ): Int {
        val cutoff = now.minus(Duration.ofDays(retentionDays.coerceAtLeast(1)))
        val expired = runs.findByStartedAtBefore(cutoff)
        if (expired.isEmpty()) return 0
        val runIds = expired.map { it.id }
        candidates.deleteByRunIdIn(runIds)
        syntheses.deleteByRunIdIn(runIds)
        runs.deleteAll(expired)
        return expired.size
    }
}
