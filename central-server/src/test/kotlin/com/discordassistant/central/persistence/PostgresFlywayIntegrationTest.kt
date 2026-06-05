package com.discordassistant.central.persistence

import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderScheduleEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 실제 Postgres(Testcontainers)에서 Flyway 마이그레이션(V1~V3)이 적용되는지 검증(차수 17 #261).
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

    @Test
    fun `Flyway 적용 후 엔티티 저장·조회(V2 길드기본·V3 스케줄)`() {
        // V2 컬럼(default_model/language)
        guilds.save(GuildEntity(id = 1, defaultModel = "llama3", language = "en"))
        assertEquals("llama3", guilds.findById(1).get().defaultModel)
        // V3 테이블(provider_schedule)
        schedules.save(ProviderScheduleEntity(providerId = 7, guildId = 1, fromHour = 22, toHour = 6))
        assertEquals(22, schedules.findByProviderIdAndGuildId(7, 1)!!.fromHour)
    }
}
