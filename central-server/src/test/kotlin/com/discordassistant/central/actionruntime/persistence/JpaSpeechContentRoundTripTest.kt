package com.discordassistant.central.actionruntime.persistence

import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaSpeechContentResolver
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaSpeechContentWriter
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.ScheduledActionContentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P13-T003/T017 — [JpaSpeechContentWriter] ↔ [JpaSpeechContentResolver] 왕복: 저장한 발화 본문을 참조로 다시
 * 읽고, 없는 참조는 null, 같은 참조 재저장은 멱등(덮어쓰기 안 함)임을 H2(Flyway V63)에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaSpeechContentRoundTripTest
    @Autowired
    constructor(
        private val contentRepo: ScheduledActionContentRepository,
    ) {
        private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        private val writer = JpaSpeechContentWriter(contentRepo, clock)
        private val resolver = JpaSpeechContentResolver(contentRepo)

        @Test
        fun `저장한 본문을 참조로 다시 읽는다(왕복)`() {
            writer.store("dec-1#0", "오늘 배포 축하해요!")

            assertThat(resolver.resolve("dec-1#0")).isEqualTo("오늘 배포 축하해요!")
        }

        @Test
        fun `없는 참조는 null 을 돌려준다(미생성 전송 안 함)`() {
            assertThat(resolver.resolve("dec-missing#0")).isNull()
        }

        @Test
        fun `같은 참조 재저장은 멱등이다(원문 덮어쓰기 금지 T003)`() {
            writer.store("dec-1#0", "첫 본문")
            writer.store("dec-1#0", "다른 본문") // 멱등 — 첫 본문 보존.

            assertThat(resolver.resolve("dec-1#0")).isEqualTo("첫 본문")
            assertThat(contentRepo.findAll()).hasSize(1)
        }
    }
