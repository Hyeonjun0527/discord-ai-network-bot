package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 명령 등록 ↔ 디스패치 드리프트 가드(차수 13 #193). DiscordBot.kt 소스에서
 * `Commands.slash("X")` 로 등록된 명령과 `"X" ->` 디스패치 분기를 대조한다.
 * 한쪽에만 있으면(핸들러 없는 등록 / 등록 없는 핸들러) 실패시켜 드리프트를 차단한다.
 */
class CommandRegistrationDriftTest {
    private val source: String by lazy {
        val candidates = listOf(
            "src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt",
            "central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt",
        )
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?.readText() ?: error("DiscordBot.kt 를 찾지 못했습니다 (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `등록된 모든 슬래시 명령은 디스패치 분기를 가진다`() {
        val registered = Regex("""Commands\.slash\("([a-z0-9-]+)"""").findAll(source)
            .map { it.groupValues[1] }.toSet()
        // dispatch(...) when 절의 "name" -> 분기 추출. commands. 직접호출 또는 블록형({) 모두 인정.
        val dispatched = Regex(""""([a-z0-9-]+)"\s*->\s*(commands\.|\{)""").findAll(source)
            .map { it.groupValues[1] }.toSet()

        // 인터랙티브 명령은 dispatch() when 이 아니라 컴포넌트/모달 흐름에서 처리(#147/180/189).
        val interactiveHandled = setOf("llm-settings", "ask-long")

        assertTrue(registered.size >= 20, "등록 명령이 너무 적음(파싱 오류 의심): ${registered.size}")
        val registeredNoHandler = registered - dispatched - interactiveHandled
        val handlerNoRegister = dispatched - registered
        assertEquals(emptySet<String>(), registeredNoHandler, "등록되었으나 디스패치 없음")
        assertEquals(emptySet<String>(), handlerNoRegister, "디스패치하나 등록 없음")
    }
}
