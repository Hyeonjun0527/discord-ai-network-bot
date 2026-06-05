package com.discordassistant.central.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.platform.discord.SettingsWizardHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * 설정 마법사(SettingsWizardHandler) 적용 로직 단위테스트(test-first, JDA 목 없이).
 *
 * 검증 핵심(동작보존 불변식):
 *  - 버튼/드롭다운으로 쌓은 pending(언어/모델/허용채널/자동승인)이 [SettingsWizardHandler.applyPendingSettings] 에서
 *    그대로 `commands.saveGuildSettings(...)` 인자로 반영되는가(순서/값 동일).
 *  - 적용 후 pending 이 비워지는가(같은 키 재적용 시 변경사항 없음).
 *  - 변경사항이 없으면 saveGuildSettings 를 호출하지 않고 null 을 돌려 "저장할 변경사항 없음" 분기로 가는가.
 *
 * CommandService 는 `kotlin-spring`(all-open, @Service)으로 열려 있어 Mockito 로 목킹 가능(실 생성자 미호출).
 */
class SettingsWizardTest {
    // Kotlin non-null 파라미터에 Mockito any() 가 null 을 반환하지 않게 하는 캡처 헬퍼들.
    private fun anyCtx(): CommandContext = any(CommandContext::class.java) ?: ctx

    private fun anyStringOrNull(): String? = any()

    private fun anyListOrNull(): List<Long>? = any()

    private fun anyBoolOrNull(): Boolean? = any()

    private fun handler(commands: CommandService): SettingsWizardHandler =
        SettingsWizardHandler(commands, mock(ChannelAiProfileService::class.java))

    private val ctx =
        CommandContext(
            guildId = 100L,
            channelId = 200L,
            userId = 300L,
            roleIds = emptySet(),
            isAdmin = true,
        )

    @Test
    fun `pending 변경을 saveGuildSettings 인자로 그대로 적용한다`() {
        val commands = mock(CommandService::class.java)
        `when`(commands.saveGuildSettings(anyCtx(), anyStringOrNull(), anyStringOrNull(), anyListOrNull(), anyBoolOrNull()))
            .thenReturn(Reply("✅ saved"))
        val wizard = handler(commands)
        val key = wizard.settingsKey(ctx)

        // 버튼/드롭다운 분기와 동일한 경로로 pending 을 쌓는다.
        wizard.pendingSettings(key).language = "en"
        wizard.pendingSettings(key).defaultModel = "llama3"
        wizard.pendingSettings(key).allowedChannelIds = listOf(11L, 22L)
        wizard.pendingSettings(key).autoApprove = true

        val reply = wizard.applyPendingSettings(ctx)

        assertNotNull(reply)
        assertEquals("✅ saved", reply!!.content)
        // 적용 인자가 pending 과 1:1 일치(순서: language, defaultModel, allowedChannelIds, autoApprove).
        verify(commands).saveGuildSettings(ctx, "en", "llama3", listOf(11L, 22L), true)
    }

    @Test
    fun `적용 후 pending 을 비운다`() {
        val commands = mock(CommandService::class.java)
        `when`(commands.saveGuildSettings(anyCtx(), anyStringOrNull(), anyStringOrNull(), anyListOrNull(), anyBoolOrNull()))
            .thenReturn(Reply("✅ saved"))
        val wizard = handler(commands)
        val key = wizard.settingsKey(ctx)
        wizard.pendingSettings(key).language = "ko"

        assertNotNull(wizard.applyPendingSettings(ctx))

        // 같은 키로 다시 적용하면 변경사항이 없어 저장하지 않고 null.
        val second = wizard.applyPendingSettings(ctx)
        assertNull(second)
        // 첫 적용 1회뿐.
        verify(commands, times(1))
            .saveGuildSettings(anyCtx(), anyStringOrNull(), anyStringOrNull(), anyListOrNull(), anyBoolOrNull())
    }

    @Test
    fun `변경사항이 없으면 저장하지 않고 null`() {
        val commands = mock(CommandService::class.java)
        val wizard = handler(commands)

        val reply = wizard.applyPendingSettings(ctx)

        assertNull(reply)
        verify(commands, never())
            .saveGuildSettings(anyCtx(), anyStringOrNull(), anyStringOrNull(), anyListOrNull(), anyBoolOrNull())
    }

    @Test
    fun `부분 변경(자동승인만)도 그 값만 채워 적용한다`() {
        val commands = mock(CommandService::class.java)
        `when`(commands.saveGuildSettings(anyCtx(), anyStringOrNull(), anyStringOrNull(), anyListOrNull(), anyBoolOrNull()))
            .thenReturn(Reply("✅ saved"))
        val wizard = handler(commands)
        val key = wizard.settingsKey(ctx)
        wizard.pendingSettings(key).autoApprove = false

        val reply = wizard.applyPendingSettings(ctx)

        assertNotNull(reply)
        // 나머지는 null(미변경), autoApprove 만 false.
        verify(commands).saveGuildSettings(ctx, null, null, null, false)
    }

    @Test
    fun `settingsKey 는 guild_channel_user 조합으로 사용자별 독립적이다`() {
        val wizard = handler(mock(CommandService::class.java))
        val other = ctx.copy(userId = 999L)
        assertTrue(wizard.settingsKey(ctx) != wizard.settingsKey(other))
        assertEquals("100:200:300", wizard.settingsKey(ctx))
    }
}
