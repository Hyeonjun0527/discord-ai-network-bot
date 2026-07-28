package com.discordassistant.central.onboarding

import com.discordassistant.central.onboarding.adapter.outbound.quota.RedisNiaWebDemoQuotaAdapter
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaDecision
import com.discordassistant.central.onboarding.application.NiaWebDemoQuotaLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration-docker")
@Testcontainers
class NiaWebDemoRedisQuotaIntegrationTest {
    @Test
    fun `동시 요청에서도 사용자와 전체 한도를 넘겨 승인하지 않는다`() {
        val connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT))
        connectionFactory.afterPropertiesSet()
        connectionFactory.start()
        try {
            val template = StringRedisTemplate(connectionFactory).also { it.afterPropertiesSet() }
            val quota = RedisNiaWebDemoQuotaAdapter(template)
            val limits =
                NiaWebDemoQuotaLimits(
                    perMinute = 50,
                    perUserWindow = 5,
                    globalWindow = 50,
                    windowSeconds = 86_400,
                )
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(12)
            val decisions =
                try {
                    val futures =
                        (1..12).map {
                            executor.submit<NiaWebDemoQuotaDecision> {
                                start.await()
                                quota.tryConsume("123456789012345678", limits)
                            }
                        }
                    start.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            assertThat(decisions.count { it is NiaWebDemoQuotaDecision.Allowed }).isEqualTo(5)
            assertThat(decisions.count { it == NiaWebDemoQuotaDecision.PerUserWindowExceeded }).isEqualTo(7)

            val globalLimits = limits.copy(perUserWindow = 10, globalWindow = 6)
            assertThat(quota.tryConsume("223456789012345678", globalLimits))
                .isInstanceOf(NiaWebDemoQuotaDecision.Allowed::class.java)
            assertThat(quota.tryConsume("323456789012345678", globalLimits))
                .isEqualTo(NiaWebDemoQuotaDecision.GlobalWindowExceeded)
        } finally {
            connectionFactory.destroy()
        }
    }

    private companion object {
        const val REDIS_PORT = 6379

        @Container
        @JvmField
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(REDIS_PORT)
    }
}
