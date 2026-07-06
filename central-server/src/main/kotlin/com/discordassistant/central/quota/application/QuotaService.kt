package com.discordassistant.central.quota.application

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import com.discordassistant.central.routing.application.port.QuotaChecker
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 공정 사용 쿼터 (LAUNCH 차수 11/144). 오늘(UTC 자정 기준) 사용량이 역할 일일 한도를 넘으면 차단.
 * 한도 0 은 무제한.
 *
 * admission 은 usage_log 카운트로 판정하지만 그 행은 추론이 끝난 뒤에야 기록된다([UsageService.recordSuccess]).
 * 그 사이(admission→기록)에 같은 (guild,user) 로 동시 admission 이 몰리면 모두 **오래된 카운트**를 읽어
 * 한도를 초과 admit 하는 TOCTOU 초과가 생긴다. 이를 막기 위해 admission 통과 시 **인메모리 in-flight 예약**을
 * 하나 남기고, 검사에서 DB 카운트에 아직 반영 안 된 in-flight 수를 더해 판정한다.
 *
 * 예약은 완료/실패 호출을 별도로 배선하지 않고 [reservationTtlMillis] 후 자동 만료한다 — 추론이 끝나면
 * usage_log 행이 카운트에 반영되므로, TTL 은 admission→기록 창만 덮으면 된다(창을 벗어나면 예약이 사라져
 * 무한 증가하지 않고, 빈 항목은 제거한다). 예약은 카운트를 **더하기만** 하므로 판정은 항상 보수적이다
 * (한도를 넘겨 admit 하지 않음). 단일 요청은 자기 예약 이전의 카운트로 판정되어 기존 동작을 유지한다.
 */
@Service
class QuotaService(
    private val usage: UsageLogRepository,
    private val policy: PolicyService,
    @param:Value("\${central.quota.reservation-ttl-millis:60000}") private val reservationTtlMillis: Long = 60_000,
    private val nowNanos: () -> Long = System::nanoTime,
) : QuotaChecker {
    // (guild,user) → 아직 usage_log 에 기록 전인 예약 만료시각(nanos) 목록.
    private val inFlight = ConcurrentHashMap<String, MutableList<Long>>()

    @Transactional(readOnly = true)
    override fun exceededQuota(
        guildId: Long,
        userId: Long,
        roleIds: Set<Long>,
    ): Boolean {
        val limit = policy.dailyLimit(guildId, roleIds)
        if (limit <= 0) return false // 무제한
        val since = Instant.now().truncatedTo(ChronoUnit.DAYS) // UTC 자정 = 일일 리셋
        val usedToday = usage.countByGuildIdAndUserIdAndCreatedAtAfter(guildId, userId, since)

        val now = nowNanos()
        pruneExpired(now)
        val key = "$guildId:$userId"
        val reservations = inFlight.computeIfAbsent(key) { mutableListOf() }
        return synchronized(reservations) {
            reservations.removeIf { expiry -> now >= expiry } // 만료 예약 정리
            if (usedToday + reservations.size >= limit) {
                if (reservations.isEmpty()) inFlight.remove(key, reservations)
                true
            } else {
                // admission 통과 — 이번 요청분 예약(자기 자신 포함)을 남겨 다음 동시 admission 이 초과하지 않게.
                reservations.add(now + reservationTtlMillis * 1_000_000)
                false
            }
        }
    }

    /** 만료 예약을 걷어내고 빈 (guild,user) 항목을 제거한다 — 인메모리 맵 무한 증가 방지(임계치 초과 시). */
    private fun pruneExpired(now: Long) {
        if (inFlight.size <= CLEANUP_THRESHOLD) return
        inFlight.entries.removeIf { (_, reservations) ->
            synchronized(reservations) {
                reservations.removeIf { expiry -> now >= expiry }
                reservations.isEmpty()
            }
        }
    }

    private companion object {
        private const val CLEANUP_THRESHOLD = 10_000
    }
}
