package com.discordassistant.central.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

/**
 * NEXA-P15-T020 — NEXA Flyway 마이그레이션 통합 검증(H2, 비-Docker).
 *
 * P02~P15 NEXA 마이그레이션(V50~V71)이 운영 baseline(V49) 위에 **순서대로·additive 로** 적용돼 깨끗한 스키마가
 * 만들어지는지 검증한다. H2(PostgreSQL 모드) + Flyway 로 V1~현재 전체를 적용한 뒤:
 *  - flyway_schema_history 에 V50~V66 가 success=true 로 모두 기록됐는지(checksum/constraint 통과 = 적용 성공),
 *  - NEXA 핵심 테이블/컬럼이 존재하는지(consent·event store·scheduled action·participation flag·온보딩 동의 4축)
 * 를 확인한다.
 *
 * **acceptance(T020) — 새 설치와 기존 업그레이드 모두 checksum/constraint 가 통과한다**: 이 테스트는 빈 DB 에
 * 전체 마이그레이션을 적용(=새 설치)하고 모든 NEXA 버전이 success 임을 본다. 실제 Postgres 검증은
 * [PostgresFlywayIntegrationTest](Docker 태그)가 보완한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NexaFlywayMigrationIntegrationTest {
    @PersistenceContext
    private lateinit var em: EntityManager

    @Test
    fun `NEXA 마이그레이션 V50부터 V71까지 모두 success 로 적용된다`() {
        @Suppress("UNCHECKED_CAST")
        val rows =
            em
                .createNativeQuery(
                    // H2(PostgreSQL 모드)는 unquoted 식별자를 대문자화한다 — Flyway 가 소문자로 만든 테이블·컬럼을 quote.
                    """SELECT "version", "success" FROM "flyway_schema_history" WHERE "version" IS NOT NULL""",
                ).resultList as List<Array<Any?>>

        val successByVersion =
            rows.associate { row ->
                (row[0] as Any).toString() to toBool(row[1])
            }

        // V50~V71 의 NEXA 마이그레이션이 모두 적용·성공이어야 한다(누락·실패 0).
        (50..71).forEach { v ->
            assertThat(successByVersion).containsKey(v.toString())
            assertThat(successByVersion[v.toString()]).`as`("V$v success").isTrue()
        }
    }

    @Test
    fun `NEXA 핵심 테이블·컬럼이 마이그레이션 후 존재한다`() {
        // P02 consent / P03 event store / P13 scheduled action / P15 participation flag.
        assertThat(tableRowCount("nexa_guild_consent")).isZero() // 존재(빈 테이블) — 없으면 예외.
        assertThat(tableRowCount("nexa_channel_scope")).isZero()
        assertThat(tableRowCount("nexa_scheduled_action")).isZero()
        assertThat(tableRowCount("nexa_participation_channel_flag")).isZero()
        assertThat(tableRowCount("nexa_raw_context_message")).isZero()

        // T014 온보딩 동의 4축 컬럼이 기존 테이블에 additive 로 추가됐다(셀렉트가 깨지지 않으면 컬럼 존재).
        em
            .createNativeQuery(
                "SELECT nexa_observe_scope, nexa_external_glm_allowed, nexa_live_send_allowed, nexa_learning_opt_in " +
                    "FROM guild_onboarding_consent WHERE 1=0",
            ).resultList
    }

    private fun tableRowCount(table: String): Long = (em.createNativeQuery("SELECT COUNT(*) FROM $table").singleResult as Number).toLong()

    private fun toBool(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString().equals("true", ignoreCase = true)
        }
}
