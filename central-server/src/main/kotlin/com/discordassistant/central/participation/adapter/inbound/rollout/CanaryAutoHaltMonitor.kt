package com.discordassistant.central.participation.adapter.inbound.rollout

import com.discordassistant.central.participation.application.rollout.CanaryAutoHaltService
import com.discordassistant.central.participation.application.rollout.CanaryLimits
import com.discordassistant.central.participation.application.rollout.CanarySignalCollector
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Canary 자동 중단 모니터 스케줄러(NEXA-P18-T023, participation 인바운드 어댑터·SAFETY NET).
 *
 * 자율 전송이 오작동(과다 발화·stale send·privacy error)하면 스스로 강등하도록, 주기적으로
 * [CanarySignalCollector.activeGuilds] 를 순회하며 길드별 최근 1시간 스냅샷을 [CanaryAutoHaltService] 에 넘겨
 * 강등 여부를 판정·집행한다. 실제 전송을 켜는 자율 전송 파이프라인과 **같은 단일 flag**
 * `central.nexa.autonomous-send.enabled` 뒤에 있고 기본 부재(=OFF)라, 운영자가 자율 전송을 켜기 전에는 이 빈이
 * 생성되지 않는다(tick 없음, 기존 흐름 무영향). 자율 전송이 켜져 collector 가 채워질 때만 의미가 있으므로 게이트를 공유한다.
 *
 * **graceful**: 길드별 판정을 runCatching 으로 감싸 한 길드 실패가 다른 길드·다음 tick 을 막지 않는다.
 */
@Component
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class CanaryAutoHaltMonitor(
    private val canaryAutoHaltService: CanaryAutoHaltService,
    private val signalCollector: CanarySignalCollector,
    @Value("\${central.nexa.canary-halt.max-utterances-per-hour:30}") private val maxUtterancesPerHour: Int,
    @Value("\${central.nexa.canary-halt.max-complaints:3}") private val maxComplaints: Int,
    @Value("\${central.nexa.canary-halt.max-stale-sends:5}") private val maxStaleSends: Int,
) {
    private val log = LoggerFactory.getLogger(CanaryAutoHaltMonitor::class.java)

    private val limits: CanaryLimits
        get() =
            CanaryLimits(
                maxUtterancesPerHour = maxUtterancesPerHour,
                maxComplaints = maxComplaints,
                maxStaleSends = maxStaleSends,
            )

    /**
     * 한 tick: 활성 길드마다 최근 1시간 신호로 자동 중단을 평가·집행한다. 위반이 없으면 no-op(멱등). 길드별 실패는
     * 흡수한다(다른 길드·다음 tick 영향 없음).
     */
    @Scheduled(fixedDelayString = "\${central.nexa.canary-halt.interval-ms:60000}")
    fun tick() {
        val currentLimits = limits
        for (guild in signalCollector.activeGuilds()) {
            runCatching {
                canaryAutoHaltService.handle(guild, signalCollector.snapshot(guild), currentLimits)
            }.onFailure { e ->
                log.warn("NEXA canary 자동 중단 평가 실패(guild={}) — 이 길드만 건너뜀: {}", guild, e.message)
            }
        }
    }
}
