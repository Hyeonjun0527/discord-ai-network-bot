package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 명령 등록 ↔ 한국어화(CommandLoc) ↔ DM 허용목록 드리프트 가드.
 *
 * `CommandRegistrationDriftTest`(등록↔디스패치)를 보완한다:
 * - 등록된 모든 슬래시 명령은 `CommandLoc.TABLE` 에 한국어/설명 로컬라이즈 항목을 가져야 한다
 *   (빠뜨리면 일부 명령만 영문으로 보인다 — 한국어 표기 일관화 위반).
 * - `CommandLoc.TABLE` 에 등록되지 않은 stale 키가 없어야 한다.
 * - `DiscordBot.DM_COMMANDS` 의 모든 이름은 실제 등록된 명령이어야 한다(오타 가드).
 */
class CommandLocCoverageDriftTest {
    private fun read(rel: String): String {
        val roots =
            listOf(
                "src/main/kotlin/com/discordassistant/central/",
                "central-server/src/main/kotlin/com/discordassistant/central/",
            )
        return roots.map { File(it + rel) }.firstOrNull { it.exists() }?.readText()
            ?: error("$rel 를 찾지 못했습니다 (cwd=${File(".").absolutePath})")
    }

    private val catalog by lazy { read("platform/discord/SlashCommandCatalog.kt") }
    private val loc by lazy { read("platform/discord/CommandLoc.kt") }
    private val bot by lazy { read("platform/discord/DiscordBot.kt") }

    /** 카탈로그의 `.slash("name")` (컨텍스트 메뉴 `.message(...)` 제외). */
    private val registered: Set<String> by lazy {
        Regex("""\.slash\(\s*"([a-z0-9-]+)"""").findAll(catalog).map { it.groupValues[1] }.toSet()
    }

    /** CommandLoc.TABLE 의 `"name" to L(...)` 키. */
    private val localized: Set<String> by lazy {
        Regex(""""([a-z0-9-]+)"\s+to\s+L\(""").findAll(loc).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `등록된 모든 슬래시 명령은 CommandLoc 한국어화 항목을 가진다`() {
        assertTrue(registered.size >= 40, "등록 명령 파싱 오류 의심: ${registered.size}")
        assertEquals(emptySet<String>(), registered - localized, "등록됐으나 CommandLoc 한국어화 누락")
        assertEquals(emptySet<String>(), localized - registered, "CommandLoc 에 있으나 등록 없음(stale)")
    }

    @Test
    fun `DM 허용목록의 모든 이름은 등록된 명령이다`() {
        val dmBlock =
            bot.substringAfter("DM_COMMANDS =").substringAfter("setOf(").substringBefore(")")
        val dm = Regex(""""([a-z0-9-]+)"""").findAll(dmBlock).map { it.groupValues[1] }.toSet()
        assertTrue(dm.size >= 8, "DM_COMMANDS 파싱 오류 의심: ${dm.size}")
        assertEquals(emptySet<String>(), dm - registered, "DM 허용 목록에 등록되지 않은 명령(오타?)")
    }
}
