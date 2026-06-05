package com.discordassistant.central.global.health

import com.discordassistant.central.relay.ConnectionRegistry
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Provider Pool 메트릭 (LAUNCH 차수 15). `/actuator/prometheus` 에 활성 연결 수 게이지를 노출한다.
 */
@Component
class PoolMetrics(
    registry: ConnectionRegistry,
    meter: MeterRegistry,
) {
    init {
        Gauge
            .builder("providerpool_active_connections") { registry.activeCount().toDouble() }
            .description("현재 풀에 연결된 프로바이더 수")
            .register(meter)
    }
}
