package com.discordassistant.central.global.health

import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Provider Pool 헬스 인디케이터 (K-차수 16). Actuator `/actuator/health` 에 활성 연결 수를 노출한다.
 */
@Component("providerPool")
class PoolHealthIndicator(
    private val registry: ConnectionRegistry,
) : HealthIndicator {
    override fun health(): Health {
        val active = registry.activeCount()
        return Health
            .up()
            .withDetail("activeProviderConnections", active)
            .build()
    }
}
