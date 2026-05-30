package com.discordassistant.central.discord

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 명령 사용 통계/로그(차수 13 #190). 명령별 호출 수를 Micrometer 카운터
 * `discord_command_total{command=...}` 로 노출하고, 로컬 카운트도 보관(테스트/요약용).
 */
@Component
class CommandMetrics(private val meter: MeterRegistry) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun record(command: String) {
        meter.counter("discord_command_total", "command", command).increment()
        counts.computeIfAbsent(command) { AtomicLong(0) }.incrementAndGet()
    }

    fun count(command: String): Long = counts[command]?.get() ?: 0L

    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}
