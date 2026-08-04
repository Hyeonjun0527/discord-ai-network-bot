package com.discordassistant.central.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.guild.adapter.outbound.persistence.GuildEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.GuildRepository
import com.discordassistant.central.participation.adapter.outbound.persistence.JpaNiaCatchUpStateStore
import com.discordassistant.central.participation.adapter.outbound.persistence.NiaCatchUpStateRepository
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.application.catchup.NiaCatchUpState
import com.discordassistant.central.participation.application.catchup.NiaJudgeCadenceMode
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderScheduleEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderScheduleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 Postgres(Testcontainers)에서 Flyway 마이그레이션(V1~현재)이 적용되는지 검증(차수 17 #261).
 * Docker 필요 → `integration-docker` 태그로 기본 빌드에서 제외. 실행: `./gradlew test -PdockerTests`.
 */
@Tag("integration-docker")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostgresFlywayIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    lateinit var guilds: GuildRepository

    @Autowired
    lateinit var schedules: ProviderScheduleRepository

    @Autowired
    lateinit var catchUpStates: NiaCatchUpStateRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun configureFieldCrypto() {
        FieldCrypto.configure("postgres-catch-up-test-key")
    }

    @AfterEach
    fun clearFieldCrypto() {
        FieldCrypto.configure(null)
    }

    @Test
    fun `Flyway 적용 후 엔티티 저장·조회(V2 길드기본·V3 스케줄)`() {
        // V2 컬럼(default_model/language)
        guilds.save(GuildEntity(id = 1, defaultModel = "llama3", language = "en"))
        assertEquals("llama3", guilds.findById(1).get().defaultModel)
        // V3 테이블(provider_schedule)
        schedules.save(ProviderScheduleEntity(providerId = 7, guildId = 1, fromHour = 22, toHour = 6))
        assertEquals(22, schedules.findByProviderIdAndGuildId(7, 1)!!.fromHour)
    }

    @Test
    fun `Postgres SKIP LOCKED는 CATCH_UP due 상태를 한 worker만 claim한다`() {
        val now = Instant.parse("2026-08-02T00:00:00Z")
        val scope = NiaCatchUpScope(guildId = 701, channelId = 702)
        val store = JpaNiaCatchUpStateStore(catchUpStates, Clock.fixed(now, ZoneOffset.UTC))
        inNewTransaction {
            store.save(
                NiaCatchUpState(
                    scope = scope,
                    mode = NiaJudgeCadenceMode.CATCH_UP,
                    latestMessage =
                        NiaCatchUpMessage(
                            scope = scope,
                            messageId = 703,
                            userId = 704,
                            replyToMessageId = null,
                            occurredAt = now,
                            mentioned = false,
                            replyToNia = false,
                        ),
                    nextCatchUpAt = now,
                ),
            )
        }

        val firstClaimed = CountDownLatch(1)
        val releaseFirstWorker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstWorker =
                executor.submit<List<*>> {
                    inNewTransaction {
                        val claims = store.claimDue(now, leaseOwner = "worker-a", leaseExpiresAt = now.plusSeconds(30), limit = 1)
                        firstClaimed.countDown()
                        check(releaseFirstWorker.await(5, TimeUnit.SECONDS)) { "첫 worker lease를 제때 해제하지 못했다" }
                        claims
                    }
                }
            check(firstClaimed.await(5, TimeUnit.SECONDS)) { "첫 worker가 due row를 claim하지 못했다" }

            val secondClaims =
                inNewTransaction {
                    store.claimDue(now, leaseOwner = "worker-b", leaseExpiresAt = now.plusSeconds(30), limit = 1)
                }

            assertThat(secondClaims).isEmpty()
            releaseFirstWorker.countDown()
            assertThat(firstWorker.get(5, TimeUnit.SECONDS)).hasSize(1)
        } finally {
            releaseFirstWorker.countDown()
            executor.shutdownNow()
        }
    }

    private fun <T> inNewTransaction(block: () -> T): T {
        val transaction =
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }
        return checkNotNull(transaction.execute { block() })
    }
}
