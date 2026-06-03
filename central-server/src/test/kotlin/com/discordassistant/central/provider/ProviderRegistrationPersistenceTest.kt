package com.discordassistant.central.provider

import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.persistence.ProviderEntity
import com.discordassistant.central.persistence.ProviderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 프로바이더 등록 영속화: 쓰기 시 DB 반영 + 시작(load) 시 DB→캐시 복원(재시작에도 등록 유지). */
@SpringBootTest
@Transactional
class ProviderRegistrationPersistenceTest
    @Autowired
    constructor(
        val service: ProviderRegistrationService,
        val repo: ProviderRepository,
    ) {
        @Test
        fun `requestJoin 이 DB 에 영속된다`() {
            service.requestJoin(88_003L, 88_004L, autoApprove = true)
            assertNotNull(repo.findByProviderUserIdAndGuildId(88_003L, 88_004L)) // DB 에 기록됨
        }

        @Test
        fun `DB 의 등록을 load 로 캐시 복원한다(재시작 시뮬레이션)`() {
            repo.save(ProviderEntity(providerUserId = 88_001L, guildId = 88_002L, state = "APPROVED", createdAt = Instant.now()))
            service.load() // 재시작 가정: DB 에서 캐시 적재
            assertEquals(ProviderState.APPROVED, service.stateOf(88_001L, 88_002L))
        }
    }
