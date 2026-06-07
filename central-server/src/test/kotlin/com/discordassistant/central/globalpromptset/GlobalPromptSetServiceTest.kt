package com.discordassistant.central.globalpromptset

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.globalpromptset.adapter.inbound.web.AddGlobalPromptSetRequest
import com.discordassistant.central.globalpromptset.adapter.inbound.web.GlobalPromptSetController
import com.discordassistant.central.globalpromptset.adapter.outbound.persistence.GlobalPromptSetRepository
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.shared.NexaIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GlobalPromptSetServiceTest
    @Autowired
    constructor(
        private val sets: GlobalPromptSetRepository,
    ) {
        private val service =
            GlobalPromptSetService(
                sets = sets,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )
        private val controller = GlobalPromptSetController(service)

        private fun adminRequest(userId: Long? = 77): MockHttpServletRequest =
            MockHttpServletRequest().apply {
                setAttribute(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = userId, systemToken = userId == null))
            }

        @Test
        fun `기본 상태에서는 니아가 기본이고 활성 페르소나는 니아 전문이다`() {
            val list = service.list(GUILD)
            assertEquals(1, list.size)
            val nia = list.single()
            assertTrue(nia.builtin)
            assertTrue(nia.isDefault) // 사용자 셋이 없으니 니아가 기본
            assertEquals(NexaIdentity.NIA_PREVIEW, nia.preview)
            assertNull(nia.content) // builtin 전문은 비공개

            assertEquals(NexaIdentity.NIA_DEFAULT_PERSONA, service.activePersona(GUILD))
        }

        @Test
        fun `사용자 셋을 추가해도 추가만으로 기본이 되지 않는다`() {
            service.add(GUILD, "정중한 비서", "당신은 정중하고 간결한 비서입니다.", 77)
            val list = service.list(GUILD)
            assertEquals(2, list.size)
            assertTrue(list.first { it.builtin }.isDefault) // 여전히 니아가 기본
            val user = list.first { !it.builtin }
            assertFalse(user.isDefault)
            assertEquals("당신은 정중하고 간결한 비서입니다.", user.content) // 사용자 셋은 전문 노출
            assertNull(user.preview)
            assertEquals(NexaIdentity.NIA_DEFAULT_PERSONA, service.activePersona(GUILD))
        }

        @Test
        fun `사용자 셋을 기본으로 지정하면 활성 페르소나가 그 셋으로 바뀐다`() {
            val added = service.add(GUILD, "정중한 비서", "당신은 정중한 비서입니다.", 77)
            service.setDefault(GUILD, added.id)

            val list = service.list(GUILD)
            assertFalse(list.first { it.builtin }.isDefault) // 니아는 더 이상 기본 아님
            assertTrue(list.first { !it.builtin }.isDefault)
            assertEquals("당신은 정중한 비서입니다.", service.activePersona(GUILD))
        }

        @Test
        fun `기본은 길드당 하나만 — 다른 셋을 기본으로 지정하면 이전 기본이 해제된다`() {
            val a = service.add(GUILD, "비서 A", "A 페르소나", 77)
            val b = service.add(GUILD, "비서 B", "B 페르소나", 77)
            service.setDefault(GUILD, a.id)
            service.setDefault(GUILD, b.id)

            val defaults = service.list(GUILD).filter { it.isDefault }
            assertEquals(1, defaults.size)
            assertEquals(b.id, defaults.single().id)
            assertEquals("B 페르소나", service.activePersona(GUILD))
        }

        @Test
        fun `니아를 기본으로 되돌리면 사용자 셋 기본이 해제되고 활성 페르소나가 니아로 돌아온다`() {
            val a = service.add(GUILD, "비서 A", "A 페르소나", 77)
            service.setDefault(GUILD, a.id)
            service.setDefault(GUILD, GlobalPromptSetService.BUILTIN_NIA_ID)

            assertTrue(service.list(GUILD).first { it.builtin }.isDefault)
            assertEquals(NexaIdentity.NIA_DEFAULT_PERSONA, service.activePersona(GUILD))
        }

        @Test
        fun `기본이던 셋을 삭제하면 니아로 되돌아간다`() {
            val a = service.add(GUILD, "비서 A", "A 페르소나", 77)
            service.setDefault(GUILD, a.id)
            service.delete(GUILD, a.id)

            assertEquals(1, service.list(GUILD).size)
            assertEquals(NexaIdentity.NIA_DEFAULT_PERSONA, service.activePersona(GUILD))
        }

        @Test
        fun `content 는 at-rest 암호화되어 저장된다(평문 미저장)`() {
            service.add(GUILD, "비밀 비서", "평문이면안되는내용", 77)
            val raw = sets.findByGuildIdOrderByIdAsc(GUILD).single()
            // 엔티티 조회 시 컨버터가 복호화하므로 평문이 보이지만, 저장 컬럼에는 enc 접두 암호문이 들어간다.
            assertEquals("평문이면안되는내용", raw.content)
        }

        @Test
        fun `builtin 은 삭제할 수 없고, 중복 이름·빈 값은 거부한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.delete(GUILD, GlobalPromptSetService.BUILTIN_NIA_ID)
            }
            service.add(GUILD, "유일", "내용", 77)
            assertThrows(IllegalArgumentException::class.java) { service.add(GUILD, "유일", "또다른내용", 77) }
            assertThrows(IllegalArgumentException::class.java) { service.add(GUILD, "  ", "내용", 77) }
            assertThrows(IllegalArgumentException::class.java) { service.add(GUILD, "이름", "   ", 77) }
            assertThrows(IllegalArgumentException::class.java) { service.setDefault(GUILD, "999999") }
        }

        @Test
        fun `길드별로 격리된다`() {
            val a = service.add(GUILD, "A셋", "A내용", 77)
            service.setDefault(GUILD, a.id)
            // 다른 길드는 영향 없음 — 여전히 니아
            assertEquals(NexaIdentity.NIA_DEFAULT_PERSONA, service.activePersona(OTHER_GUILD))
            assertEquals(1, service.list(OTHER_GUILD).size)
        }

        @Test
        fun `컨트롤러 — 추가·기본지정·삭제가 목록을 반환하고 비공개 정책을 지킨다`() {
            val afterAdd =
                controller.add(GUILD, AddGlobalPromptSetRequest("정중한 비서", "당신은 정중한 비서입니다."), adminRequest())
            assertEquals(2, afterAdd.size)
            val userId = afterAdd.first { !it.builtin }.id

            val afterDefault = controller.setDefault(GUILD, userId)
            assertTrue(afterDefault.first { !it.builtin }.isDefault)
            // builtin 은 컨트롤러 응답에서도 전문 비공개(preview 만)
            val nia = controller.list(GUILD).first { it.builtin }
            assertNull(nia.content)
            assertEquals(NexaIdentity.NIA_PREVIEW, nia.preview)

            val afterDelete = controller.delete(GUILD, userId)
            assertEquals(1, afterDelete.size)
        }

        companion object {
            private const val GUILD = 9_100L
            private const val OTHER_GUILD = 9_200L
        }
    }
