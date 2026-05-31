package com.discordassistant.central.provider

import com.discordassistant.central.persistence.ProviderScheduleEntity
import com.discordassistant.central.persistence.ProviderScheduleRepository
import com.discordassistant.central.relay.ConnectionRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset

/**
 * 프로바이더 가용 시간대 스케줄(차수 12 #159) + 스케줄 기반 자동 online/offline(#160).
 * 윈도우 밖이면 자동 pause, 윈도우 안이면 resume 한다(주기 실행). 스케줄 미설정 프로바이더는 영향 없음.
 */
@Service
class ProviderScheduleService(
    private val schedules: ProviderScheduleRepository,
    private val registry: ConnectionRegistry,
    private val protection: ProviderProtectionService,
) {
    private val log = LoggerFactory.getLogger(ProviderScheduleService::class.java)

    /** 스케줄 설정/갱신(upsert). from==to 면 24시간 가용. */
    fun setSchedule(
        providerId: Long,
        guildId: Long,
        fromHour: Int,
        toHour: Int,
    ) {
        require(fromHour in 0..23 && toHour in 0..23) { "시(hour)는 0..23" }
        val e =
            schedules.findByProviderIdAndGuildId(providerId, guildId)
                ?: ProviderScheduleEntity(providerId = providerId, guildId = guildId)
        e.fromHour = fromHour
        e.toHour = toHour
        schedules.save(e)
    }

    /** 현재 가용 여부. 스케줄 없으면 항상 가용(true). */
    fun isAvailableNow(
        providerId: Long,
        guildId: Long,
        clock: Clock = Clock.systemUTC(),
    ): Boolean {
        val s = schedules.findByProviderIdAndGuildId(providerId, guildId) ?: return true
        val hour = clock.instant().atZone(ZoneOffset.UTC).hour
        return AvailabilityWindow.isWithin(s.fromHour, s.toHour, hour)
    }

    /**
     * 연결된 세션을 스케줄에 맞춰 자동 pause/resume(#160). 반환: (paused, resumed) 카운트(테스트용).
     */
    fun enforce(clock: Clock = Clock.systemUTC()): Pair<Int, Int> {
        var paused = 0
        var resumed = 0
        for (session in registry.snapshotSessions()) {
            val gid = session.guildId ?: continue
            val sched = schedules.findByProviderIdAndGuildId(session.providerId, gid) ?: continue
            val hour = clock.instant().atZone(ZoneOffset.UTC).hour
            if (AvailabilityWindow.isWithin(sched.fromHour, sched.toHour, hour)) {
                if (protection.resume(session.providerId, gid)) resumed++
            } else {
                if (protection.pause(session.providerId, gid)) paused++
            }
        }
        if (paused + resumed > 0) log.info("스케줄 적용: pause={}, resume={}", paused, resumed)
        return paused to resumed
    }

    @Scheduled(fixedDelayString = "\${central.schedule.enforce-millis:60000}")
    fun scheduledEnforce() {
        enforce()
    }

    @Transactional
    fun deleteGuild(guildId: Long) {
        schedules.deleteByGuildId(guildId)
    }

    @Transactional
    fun deleteProviderGuild(
        providerId: Long,
        guildId: Long,
    ) {
        schedules.deleteByProviderIdAndGuildId(providerId, guildId)
    }
}
