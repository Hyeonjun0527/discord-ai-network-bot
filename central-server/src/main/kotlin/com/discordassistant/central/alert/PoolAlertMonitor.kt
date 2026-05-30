package com.discordassistant.central.alert

import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 풀 헬스 알림 모니터(차수 15 #220). 임계 전환(edge-trigger)에서만 알림을 보내 스팸을 막는다.
 * - 활성 프로바이더 0명 진입 → CRITICAL, 복구 → INFO.
 * - 경고 임계 이하로 진입 → WARN(한 번), 회복 시 해제.
 */
@Component
class PoolAlertMonitor(
    private val registry: ConnectionRegistry,
    private val notifier: Notifier,
    @param:Value("\${central.alert.low-pool-threshold:1}") private val lowThreshold: Int,
) {
    private var wasEmpty = false
    private var wasLow = false

    /** 활성 수를 평가하고 전환 시에만 알림. 반환: 발송한 알림 수(테스트용). */
    fun evaluate(active: Int = registry.activeCount()): Int {
        var fired = 0
        if (active == 0) {
            if (!wasEmpty) {
                notifier.notify(Severity.CRITICAL, "Provider Pool 전체 오프라인", "활성 프로바이더가 0명입니다.")
                fired++
            }
            wasEmpty = true
            wasLow = true
            return fired
        }
        // active > 0
        if (wasEmpty) {
            notifier.notify(Severity.INFO, "Provider Pool 복구", "활성 프로바이더 ${active}명으로 복구되었습니다.")
            fired++
            wasEmpty = false
        }
        if (active < lowThreshold && !wasLow) {
            notifier.notify(Severity.WARN, "Provider Pool 용량 부족", "활성 프로바이더 ${active}명(임계 ${lowThreshold}).")
            fired++
            wasLow = true
        } else if (active >= lowThreshold && wasLow) {
            wasLow = false
        }
        return fired
    }

    @Scheduled(fixedDelayString = "\${central.alert.check-millis:30000}")
    fun scheduledCheck() {
        evaluate()
    }
}
