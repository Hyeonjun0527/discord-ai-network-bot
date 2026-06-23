package com.discordassistant.central.platform.discord.admin

import com.discordassistant.central.routing.application.CloudToolCall
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 액션 카탈로그(SSOT): 위험도 분류·스키마 생성·tool_call→plan 파싱 검증. */
class AdminToolCatalogTest {
    private val mapper = ObjectMapper()

    @Test
    fun `위험 액션은 CONFIRM, 안전 액션은 SAFE 로 분류된다`() {
        assertEquals(AdminActionRisk.CONFIRM, AdminActionType.BAN_MEMBER.risk)
        assertEquals(AdminActionRisk.CONFIRM, AdminActionType.KICK_MEMBER.risk)
        assertEquals(AdminActionRisk.CONFIRM, AdminActionType.TIMEOUT_MEMBER.risk)
        assertEquals(AdminActionRisk.CONFIRM, AdminActionType.DELETE_CHANNEL.risk)
        assertEquals(AdminActionRisk.CONFIRM, AdminActionType.SET_CHANNEL_PERMISSION.risk)
        assertEquals(AdminActionRisk.SAFE, AdminActionType.CREATE_TEXT_CHANNEL.risk)
        assertEquals(AdminActionRisk.SAFE, AdminActionType.SET_SLOWMODE.risk)
        assertEquals(AdminActionRisk.SAFE, AdminActionType.UNBAN_MEMBER.risk)
    }

    @Test
    fun `toolsJson 은 모든 액션을 OpenAI function 스키마로 담는다`() {
        val root = mapper.readTree(AdminToolCatalog.toolsJson)
        assertTrue(root.isArray)
        assertEquals(AdminActionType.entries.size, root.size())
        val names = root.map { it.get("function").get("name").asText() }.toSet()
        assertEquals(AdminActionType.entries.map { it.toolName }.toSet(), names)
        // 각 항목은 type=function + parameters.type=object 형태.
        root.forEach {
            assertEquals("function", it.get("type").asText())
            assertEquals(
                "object",
                it
                    .get("function")
                    .get("parameters")
                    .get("type")
                    .asText(),
            )
        }
    }

    @Test
    fun `tool_call 을 실행 의도(plan)로 파싱한다`() {
        val call = CloudToolCall("ban_member", """{"userId":"42","reason":"스팸"}""")
        val plan = AdminToolCatalog.toPlan(call)!!
        assertEquals(AdminActionType.BAN_MEMBER, plan.type)
        assertEquals("42", plan.arg("userId"))
        assertEquals("스팸", plan.arg("reason"))
    }

    @Test
    fun `모르는 함수 이름은 plan 으로 매핑되지 않는다`() {
        assertNull(AdminToolCatalog.toPlan(CloudToolCall("nuke_server", "{}")))
    }

    @Test
    fun `깨진 arguments JSON 은 빈 인자로 graceful 폴백한다`() {
        val plan = AdminToolCatalog.toPlan(CloudToolCall("create_category", "not json"))!!
        assertEquals(AdminActionType.CREATE_CATEGORY, plan.type)
        assertNull(plan.arg("name"))
    }
}
