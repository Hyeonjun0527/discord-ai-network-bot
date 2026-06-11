package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.application.NiaAffinityService
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * B1/B2 가드: best-effort 호감도 적립은 awardInteraction 의 REQUIRES_NEW 독립 트랜잭션에 의존한다.
 * - 구조: 실제 NiaAffinityService.awardInteraction 이 REQUIRES_NEW 로 선언됐는지(어노테이션) 검증.
 * - 기능: 같은 Spring 컨테이너(앱과 동일 프록시/트랜잭션 매니저)에서, REQUIRES_NEW 내부 트랜잭션의
 *   롤백이 커밋되는 외부(REQUIRED) 트랜잭션을 globally rollback-only 로 전염시키지 **않음**을 실증.
 *   (이전 가짜 테스트는 프록시 미경유 override + @DataJpaTest 단일 미커밋 트랜잭션이라 이 경로를 못 잡았다.)
 *
 * 주의: 클래스에 @Transactional 을 붙이지 않는다 — 외부 트랜잭션이 실제로 **커밋**돼야
 * rollback-only 전염 여부를 관측할 수 있기 때문이다. 대신 @AfterEach 로 삽입 행을 정리한다.
 */
@SpringBootTest
@Import(NiaAffinityIsolationTest.IsolationTxBeans::class)
class NiaAffinityIsolationTest
    @Autowired
    constructor(
        private val outer: OuterRequiredTxBean,
        private val usageLogs: UsageLogRepository,
    ) {
        @AfterEach
        fun cleanup() {
            usageLogs.findAll().filter { it.guildId in setOf(MARKER_GUILD) }.forEach { usageLogs.deleteById(it.id) }
        }

        @Test
        fun `awardInteraction 은 REQUIRES_NEW 로 선언되어 독립 트랜잭션에서 동작한다`() {
            val method =
                NiaAffinityService::class.java.getDeclaredMethod(
                    "awardInteraction",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                )
            val tx = method.getAnnotation(Transactional::class.java)
            requireNotNull(tx) { "awardInteraction 에 @Transactional 이 없습니다 — best-effort 격리 불가" }
            assertEquals(
                Propagation.REQUIRES_NEW,
                tx.propagation,
                "awardInteraction 은 REQUIRES_NEW 여야 호출자 트랜잭션으로 rollback-only 가 전염되지 않는다(B1)",
            )
        }

        @Test
        fun `REQUIRES_NEW 내부 트랜잭션 롤백은 커밋되는 외부 트랜잭션을 깨지 않는다`() {
            // 외부(REQUIRED)가 usage_log 1행을 쓰고, REQUIRES_NEW 내부가 예외로 롤백된 뒤
            // 외부는 정상 커밋돼야 한다(예외 미전파 + UnexpectedRollbackException 없음).
            outer.writeThenCallFailingInner(MARKER_GUILD)

            val survived = usageLogs.findAll().count { it.guildId == MARKER_GUILD }
            assertEquals(1, survived, "외부 트랜잭션이 커밋돼 usage_log 가 남아야 한다(rollback-only 비전염)")
        }

        @Test
        fun `대조군 — REQUIRES_NEW 가 아니면(REQUIRED 합류) 외부 커밋이 깨진다`() {
            // 같은 패턴이지만 내부가 REQUIRED(합류)면 rollback-only 가 외부로 전염되어
            // 외부 커밋 시점에 UnexpectedRollbackException 이 난다 — REQUIRES_NEW 가 필요한 이유.
            assertThrows(Exception::class.java) {
                outer.writeThenCallJoiningInner(MARKER_GUILD + 1)
            }
            assertEquals(0, usageLogs.findAll().count { it.guildId == MARKER_GUILD + 1 }, "전염으로 외부도 롤백돼야 한다")
        }

        @TestConfiguration
        class IsolationTxBeans {
            @Bean fun requiresNewFailingInner(usageLogs: UsageLogRepository) = RequiresNewFailingInner(usageLogs)

            @Bean fun joiningFailingInner() = JoiningFailingInner()

            @Bean
            fun outerRequiredTxBean(
                usageLogs: UsageLogRepository,
                requiresNew: RequiresNewFailingInner,
                joining: JoiningFailingInner,
            ) = OuterRequiredTxBean(usageLogs, requiresNew, joining)
        }

        companion object {
            const val MARKER_GUILD = 987_654_321L
        }
    }

/** 내부 REQUIRES_NEW 트랜잭션: 행 1개 쓰고 예외 — 독립 롤백되어 외부에 전염되지 않아야 함. */
@Component
open class RequiresNewFailingInner(
    private val usageLogs: UsageLogRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun writeAndFail(guildId: Long) {
        usageLogs.save(UsageLogEntity(guildId = guildId, userId = 1, requestId = "inner-rn", createdAt = Instant.EPOCH))
        throw IllegalStateException("inner boom (requires_new)")
    }
}

/** 내부 REQUIRED 트랜잭션(합류): 예외 시 공유 트랜잭션을 rollback-only 로 마킹 — 외부 커밋 파괴(대조군). */
@Component
open class JoiningFailingInner {
    @Transactional(propagation = Propagation.REQUIRED)
    open fun fail(): Unit = throw IllegalStateException("inner boom (required, joins outer)")
}

/** 외부 REQUIRED 트랜잭션: 행을 쓰고, 내부 실패를 runCatching 으로 삼킨 뒤 커밋을 시도한다. */
@Component
open class OuterRequiredTxBean(
    private val usageLogs: UsageLogRepository,
    private val requiresNew: RequiresNewFailingInner,
    private val joining: JoiningFailingInner,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    open fun writeThenCallFailingInner(guildId: Long) {
        usageLogs.save(UsageLogEntity(guildId = guildId, userId = 1, requestId = "outer-ok", createdAt = Instant.EPOCH))
        runCatching { requiresNew.writeAndFail(guildId) } // 독립 트랜잭션 → 외부 전염 없음
    }

    @Transactional(propagation = Propagation.REQUIRED)
    open fun writeThenCallJoiningInner(guildId: Long) {
        usageLogs.save(UsageLogEntity(guildId = guildId, userId = 1, requestId = "outer-bad", createdAt = Instant.EPOCH))
        runCatching { joining.fail() } // 합류 트랜잭션 → rollback-only 전염 → 외부 커밋 시 예외
    }
}
