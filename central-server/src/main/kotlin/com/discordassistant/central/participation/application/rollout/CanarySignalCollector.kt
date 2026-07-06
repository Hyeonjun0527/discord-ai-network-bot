package com.discordassistant.central.participation.application.rollout

import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Canary 관측 신호 수집기(NEXA-P18-T023, application 레이어·스레드 안전).
 *
 * 자율 전송 실행 경로([com.discordassistant.central.actionruntime.adapter.inbound.scheduler.AutonomousSendScheduler])가
 * 실행 결과별로 이벤트를 여기에 기록하면, canary 자동 중단 모니터가 길드별 최근 1시간 창 스냅샷([snapshot])을 읽어
 * [CanaryAutoHaltService] 로 강등 여부를 판정한다. 원문/PII 비저장 — 길드 가명 키와 이벤트 발생 시각(epoch ms)만 담는다.
 *
 * **현재 공급되는 신호**: utterances(완료 전송)·staleSends(backpressure 취소)·privacyErrors(동의 철회 mid-burst).
 * complaints·modelMismatches 는 아직 공급원이 없어 항상 0 이다(사용자 피드백 수집·model version 검증 wiring 은 후속).
 *
 * 창 관리: 각 이벤트를 발생 시각 큐로 쌓고, 읽거나 기록할 때 1시간(기본)보다 오래된 항목을 prune 한다. 창이 비면 그
 * 길드 엔트리를 제거해 맵 크기를 유한하게 유지한다([snapshot]/[activeGuilds] 가 idle 길드를 자연 evict). 등록 길드가
 * [maxGuilds] 를 넘어서면 신규 길드 기록을 무시해(신호 미집계 = 안전하게 no-halt) 메모리 폭주를 막는다.
 */
@Component
class CanarySignalCollector(
    private val clock: Clock = Clock.systemUTC(),
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxGuilds: Int = DEFAULT_MAX_GUILDS,
) {
    private val windows = ConcurrentHashMap<String, GuildWindow>()

    /** 완료 전송(정상 발화) 1건을 기록한다 — 과다 발화(over_talk) 신호. */
    fun recordUtterance(guildPseudonym: String) = record(guildPseudonym) { it.utterances }

    /** backpressure staleness 로 취소된 전송 1건을 기록한다 — stale send 신호. */
    fun recordStaleSend(guildPseudonym: String) = record(guildPseudonym) { it.staleSends }

    /** 동의 철회로 mid-burst 취소된 전송 1건을 기록한다 — privacy error 신호(1건이라도 즉시 중단 사유). */
    fun recordPrivacyError(guildPseudonym: String) = record(guildPseudonym) { it.privacyErrors }

    /**
     * [guildPseudonym] 의 최근 1시간 창 신호 스냅샷. complaints·modelMismatches 는 공급원 미wiring 으로 0 이다.
     * 창에 이벤트가 없으면 모두 0 인 신호를 돌려준다.
     */
    fun snapshot(guildPseudonym: String): CanarySignals {
        val window = windows[guildPseudonym] ?: return CanarySignals()
        val now = clock.millis()
        return synchronized(window) {
            window.pruneOlderThan(now - windowMillis)
            val signals =
                CanarySignals(
                    utterancesPerHour = window.utterances.size,
                    staleSends = window.staleSends.size,
                    privacyErrors = window.privacyErrors.size,
                    // complaints·modelMismatches: 피드백/model-verify wiring 후속 — 현재 항상 0.
                )
            if (window.isEmpty()) windows.remove(guildPseudonym, window)
            signals
        }
    }

    /** 최근 1시간 창에 이벤트가 하나라도 있는 길드 가명 집합(모니터가 순회할 대상). prune 후 계산한다. */
    fun activeGuilds(): Set<String> {
        val cutoff = clock.millis() - windowMillis
        val active = mutableSetOf<String>()
        for ((guild, window) in windows) {
            synchronized(window) {
                window.pruneOlderThan(cutoff)
                if (window.isEmpty()) windows.remove(guild, window) else active.add(guild)
            }
        }
        return active
    }

    private inline fun record(
        guildPseudonym: String,
        select: (GuildWindow) -> ArrayDeque<Long>,
    ) {
        val now = clock.millis()
        // maxGuilds 초과 신규 길드는 무시(안전: 신호 미집계 → no-halt). 기존 길드는 계속 기록한다.
        val window =
            windows[guildPseudonym]
                ?: if (windows.size >= maxGuilds) return else windows.computeIfAbsent(guildPseudonym) { GuildWindow() }
        synchronized(window) {
            select(window).addLast(now)
            window.pruneOlderThan(now - windowMillis)
        }
    }

    /** 한 길드의 신호 유형별 발생 시각(epoch ms) 큐. 접근은 [CanarySignalCollector] 가 window 단위 synchronized 로 보호. */
    private class GuildWindow {
        val utterances = ArrayDeque<Long>()
        val staleSends = ArrayDeque<Long>()
        val privacyErrors = ArrayDeque<Long>()

        fun pruneOlderThan(cutoff: Long) {
            prune(utterances, cutoff)
            prune(staleSends, cutoff)
            prune(privacyErrors, cutoff)
        }

        fun isEmpty(): Boolean = utterances.isEmpty() && staleSends.isEmpty() && privacyErrors.isEmpty()

        private fun prune(
            queue: ArrayDeque<Long>,
            cutoff: Long,
        ) {
            while (queue.isNotEmpty() && queue.first() < cutoff) queue.removeFirst()
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS: Long = 60 * 60 * 1000L // 1시간.
        const val DEFAULT_MAX_GUILDS: Int = 10_000
    }
}
