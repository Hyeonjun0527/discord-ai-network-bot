package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.ImaginePostConfirmService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/** /그림 게시 확인 게이트 설정 — 기본 ON, OFF 저장·재 ON(차수: 본인 확인 게이트). DB+캐시 패턴 검증. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ImaginePostConfirmService::class)
class ImaginePostConfirmServiceTest
    @Autowired
    constructor(
        val service: ImaginePostConfirmService,
    ) {
        @Test
        fun `행이 없으면 기본 ON`() {
            assertTrue(service.isEnabled(12345)) // 한 번도 끄지 않은 유저 = 기본 ON
        }

        @Test
        fun `OFF 로 끄면 false, 다시 켜면 true`() {
            service.setEnabled(12345, enabled = false)
            assertFalse(service.isEnabled(12345)) // 끔 → OFF

            service.setEnabled(12345, enabled = true)
            assertTrue(service.isEnabled(12345)) // 다시 켬 → ON(행 제거)
        }

        @Test
        fun `이미 ON 인 유저를 다시 ON 으로 설정해도 안전(no-op)`() {
            service.setEnabled(777, enabled = true) // 행이 없는데 켜기 → 예외 없이 no-op
            assertTrue(service.isEnabled(777))
        }

        @Test
        fun `유저별로 독립적이다`() {
            service.setEnabled(1, enabled = false)
            assertFalse(service.isEnabled(1))
            assertTrue(service.isEnabled(2)) // 다른 유저는 영향 없음
        }
    }
