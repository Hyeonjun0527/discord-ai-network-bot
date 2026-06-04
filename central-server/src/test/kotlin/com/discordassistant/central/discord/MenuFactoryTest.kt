package com.discordassistant.central.discord

import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 온보딩/설정 패널 컴포넌트 빌더(차수 13 UX) 검증. */
class MenuFactoryTest {
    @Test
    fun `메인 버튼 — 일반은 4개, 관리자는 설정 추가 5개`() {
        val user = MenuFactory.mainButtons(isAdmin = false)
        assertEquals(4, user.size)
        assertFalse(user.any { it.id == MenuFactory.SETTINGS })
        val admin = MenuFactory.mainButtons(isAdmin = true)
        assertEquals(5, admin.size)
        assertTrue(admin.any { it.id == MenuFactory.SETTINGS })
        // 핵심 버튼 ID 존재
        assertTrue(user.any { it.id == MenuFactory.ASK })
        assertTrue(user.any { it.id == MenuFactory.PROVIDER })
        assertTrue(user.any { it.label == "${MenuSymbols.PROVIDER} 함께 도와주기" })
        assertTrue(user.any { it.label == "${MenuSymbols.STATUS} 내 상태" })
        assertFalse(user.any { it.label == "AI 일꾼 되기" })
        assertTrue(admin.any { it.label == "${MenuSymbols.SETTINGS} 설정" })
        assertEquals(ButtonStyle.PRIMARY, user.first { it.id == MenuFactory.ASK }.style)
        assertEquals(ButtonStyle.SECONDARY, admin.first { it.id == MenuFactory.SETTINGS }.style)
    }

    @Test
    fun `OS 선택 버튼은 GUI 앱 배포 대상(mac·Windows)만 유지한다`() {
        // Linux 는 GUI 데스크톱 앱이 없어 버튼/가이드를 노출하지 않는다.
        val buttons = MenuFactory.osButtons()
        assertEquals(2, buttons.size)
        assertEquals("macOS", buttons[0].label)
        assertEquals("🍎", buttons[0].emoji?.name)
        assertEquals("Windows", buttons[1].label)
        assertEquals("🪟", buttons[1].emoji?.name)
        assertFalse(buttons.any { it.label == "Linux" })
    }

    @Test
    fun `언어 드롭다운 — ko en 옵션`() {
        val sel = MenuFactory.languageSelect("ko")
        assertEquals(MenuFactory.LANG, sel.id)
        assertEquals(setOf("ko", "en"), sel.options.map { it.value }.toSet())
        assertTrue(sel.options.first { it.value == "ko" }.isDefault)
    }

    @Test
    fun `모델 드롭다운 — 자동 + 풀 모델, 25개 한도`() {
        val sel = MenuFactory.modelSelect(listOf("llama3", "mistral", "llama3"), current = "llama3")
        assertEquals("__auto__", sel.options.first().value) // 자동이 맨 앞
        assertTrue(sel.options.any { it.value == "llama3" })
        assertTrue(sel.options.first { it.value == "llama3" }.isDefault)
        assertTrue(sel.options.size <= 25)
        // 중복 제거
        assertEquals(
            sel.options.size,
            sel.options
                .map { it.value }
                .toSet()
                .size,
        )
    }

    @Test
    fun `자동 승인 드롭다운 — 현재 상태를 기본 선택으로 보여준다`() {
        val enabled = MenuFactory.autoApproveSelect(true)
        assertEquals(MenuFactory.AUTO_APPROVE_SELECT, enabled.id)
        assertTrue(enabled.options.first { it.value == "true" }.isDefault)
        assertFalse(enabled.options.first { it.value == "false" }.isDefault)

        val disabled = MenuFactory.autoApproveSelect(false)
        assertTrue(disabled.options.first { it.value == "false" }.isDefault)
    }

    @Test
    fun `채널 드롭다운 — 기존 허용 채널을 기본 선택으로 보여준다`() {
        val sel = MenuFactory.channelSelect(listOf(1111L, 2222L))
        assertEquals(MenuFactory.CHANNEL, sel.id)
        assertEquals(0, sel.minValues)
        assertEquals(25, sel.maxValues)
        assertEquals(2, sel.defaultValues.size)
        assertEquals(setOf(1111L, 2222L), sel.defaultValues.map { it.idLong }.toSet())
        assertEquals(
            setOf(ChannelType.TEXT, ChannelType.NEWS, ChannelType.FORUM, ChannelType.MEDIA),
            sel.channelTypes,
        )
        assertTrue(sel.placeholder?.contains("2개") == true)
        assertTrue(sel.placeholder?.contains("한 번에") == true)

        val many = MenuFactory.channelSelect((1L..30L).toList())
        assertEquals(25, many.defaultValues.size)
        assertTrue(many.placeholder?.contains("30개") == true)
    }

    @Test
    fun `설정 액션 버튼 — 모든 설정은 저장 버튼 한 번으로 적용한다`() {
        val buttons = MenuFactory.settingsActionButtons()
        assertEquals(4, buttons.size)
        assertEquals(MenuFactory.SAVE_SETTINGS, buttons.first().id)
        assertTrue(buttons.any { it.id == MenuFactory.CHANNEL_ALL && it.label?.contains("대기") == true })
        assertTrue(buttons.any { it.id == MenuFactory.CHANNEL_BULK && it.label?.contains("붙여넣기") == true })
        assertTrue(buttons.any { it.id == MenuFactory.SAVE_SETTINGS && it.label?.contains("저장") == true })
        assertTrue(buttons.any { it.id == MenuFactory.CANCEL_SETTINGS && it.label?.contains("취소") == true })
        assertEquals(ButtonStyle.SUCCESS, buttons.first { it.id == MenuFactory.SAVE_SETTINGS }.style)
    }

    @Test
    fun `채널 일괄 입력 — 멘션과 ID를 중복 제거하고 전체 허용을 지원한다`() {
        assertEquals(
            listOf(123456789012345678L, 222222222222222222L),
            MenuFactory.parseChannelIdsBulk("<#123456789012345678> 222222222222222222 <#123456789012345678>"),
        )
        assertEquals(emptyList<Long>(), MenuFactory.parseChannelIdsBulk("전체"))
        assertEquals(emptyList<Long>(), MenuFactory.parseChannelIdsBulk("all"))
        assertEquals(
            (1L..30L).map { 100000000000000000L + it }.take(25),
            MenuFactory.parseChannelIdsBulk((1L..30L).joinToString(" ") { "${100000000000000000L + it}" }),
        )
    }

    @Test
    fun `설정 안내 텍스트 — 상태별 친절 설명`() {
        // 프로바이더 0 → 자동선택만 설명, 채널 0 → 모든 채널 허용 상태
        val empty = MenuFactory.settingsText(autoApprove = false, poolModels = emptyList(), allowedChannelCount = 0)
        assertTrue(empty.contains("자동 선택"))
        assertTrue(empty.contains("프로바이더가 없어") || empty.contains("연결된 프로바이더가 없"))
        assertTrue(empty.contains("모든 채널 허용"))
        assertTrue(empty.contains("LLM 사용 허용 채널"))
        assertTrue(empty.contains("여러 채널을 체크"))
        assertTrue(empty.contains("저장") || empty.contains("한 번"))
        assertTrue(empty.contains("설정 한 번에 저장"))
        assertTrue(empty.contains("꺼짐"))
        // 모델 있고 채널 제한 + 자동승인 켜짐
        val full = MenuFactory.settingsText(autoApprove = true, poolModels = listOf("llama3"), allowedChannelCount = 2)
        assertTrue(full.contains("1종") || full.contains("모델"))
        assertTrue(full.contains("2 개") || full.contains("2개"))
        assertTrue(full.contains("한 번에 교체"))
        assertTrue(full.contains("켜짐"))
    }

    @Test
    fun `슬림 도움말 — 핵심만, 관리자만 설정 언급`() {
        val user = MenuFactory.slimHelp(isAdmin = false)
        assertTrue(user.contains("/질문"))
        assertTrue(user.contains("함께 도와주기"))
        assertTrue(user.contains("내 상태"))
        assertTrue(user.contains("/메뉴"))
        assertFalse(user.contains(MenuSymbols.SETTINGS))
        assertTrue(MenuFactory.slimHelp(isAdmin = true).contains(MenuSymbols.SETTINGS))
    }
}
