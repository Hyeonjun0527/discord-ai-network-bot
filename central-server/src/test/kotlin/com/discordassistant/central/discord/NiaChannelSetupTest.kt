package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.NiaChannelSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "니아 채널 자동 만들기" 순수 로직(권한 분기·멱등·이름·가이드 문구) 단위 테스트.
 * JDA 채널 생성/핀은 [NiaChannelSetup] 밖(핸들러)이라 여기선 결정 로직만 검증한다(저장소 테스트 스타일: MockK 미사용).
 */
class NiaChannelSetupTest {
    @Test
    fun `관리자가 아니면 NOT_ADMIN`() {
        assertEquals(
            NiaChannelSetup.PermissionDecision.NOT_ADMIN,
            NiaChannelSetup.decide(callerIsAdmin = false, botCanManageChannels = true),
        )
        // 봇 권한과 무관하게 관리자 아님이 먼저 걸린다.
        assertEquals(
            NiaChannelSetup.PermissionDecision.NOT_ADMIN,
            NiaChannelSetup.decide(callerIsAdmin = false, botCanManageChannels = false),
        )
    }

    @Test
    fun `관리자지만 봇 권한 없으면 BOT_MISSING_PERMISSION`() {
        assertEquals(
            NiaChannelSetup.PermissionDecision.BOT_MISSING_PERMISSION,
            NiaChannelSetup.decide(callerIsAdmin = true, botCanManageChannels = false),
        )
    }

    @Test
    fun `관리자 + 봇 권한 있으면 ALLOWED`() {
        assertEquals(
            NiaChannelSetup.PermissionDecision.ALLOWED,
            NiaChannelSetup.decide(callerIsAdmin = true, botCanManageChannels = true),
        )
    }

    @Test
    fun `기능 카테고리가 이미 있으면 멱등 - 대소문자·공백 무시`() {
        val ko = NiaChannelSetup.featureCategoryName("ko")
        assertTrue(NiaChannelSetup.alreadySetUp(listOf("잡담", ko), "ko"))
        // 디스코드가 이름을 다듬어도(공백/대소문자) 같은 카테고리로 인식.
        assertTrue(NiaChannelSetup.alreadySetUp(listOf("  ${ko.uppercase()}  "), "ko"))
    }

    @Test
    fun `기능 카테고리 멱등 판정은 현재 언어가 달라도 지원 locale 이름을 모두 인식한다`() {
        assertTrue(NiaChannelSetup.alreadySetUp(listOf(NiaChannelSetup.featureCategoryName("en")), "ko"))
        assertTrue(NiaChannelSetup.alreadySetUp(listOf(NiaChannelSetup.featureCategoryName("ja")), "en"))
    }

    @Test
    fun `기능 카테고리가 없으면 새로 만든다`() {
        assertFalse(NiaChannelSetup.alreadySetUp(listOf("잡담", "공지", "음성"), "ko"))
        assertFalse(NiaChannelSetup.alreadySetUp(emptyList(), "ko"))
    }

    @Test
    fun `채널 이름은 언어별로 의미를 유지한다`() {
        // 채팅/그림/음성 이름이 비어있지 않고 서로 다르다(중복 생성 방지·식별).
        listOf("ko", "en", "ja").forEach { lang ->
            val chat = NiaChannelSetup.chatChannelName(lang)
            val image = NiaChannelSetup.imageChannelName(lang)
            val member = NiaChannelSetup.memberChannelName(lang)
            val voice = NiaChannelSetup.voiceChannelName(lang)
            assertTrue(chat.isNotBlank() && image.isNotBlank() && member.isNotBlank() && voice.isNotBlank(), lang)
            assertEquals(4, setOf(chat, image, member, voice).size, "이름 중복: $lang")
        }
    }

    @Test
    fun `ai채팅 가이드는 자동응답 메커니즘과 예시 질문을 담는다`() {
        val ko = NiaChannelSetup.chatGuide("ko")
        assertTrue(ko.contains("멘션 없이"), ko) // 멘션 없이 자동응답하는 채널임을 안내
        assertTrue(ko.contains("`.`"), ko) // `.` 으로 시작하면 답하지 않는다는 안내(카미봇 컨벤션)
        // 예시 질문이 여러 개(불릿) 있어야 가이드 가치가 있다.
        assertTrue(ko.count { it == '•' } >= 5, "예시 질문 5개 이상이어야: $ko")
    }

    @Test
    fun `ai그림 가이드는 그림 명령과 프롬프트 팁을 담는다`() {
        val ko = NiaChannelSetup.imageGuide("ko")
        assertTrue(ko.contains("그림") || ko.contains("imagine"), ko) // /그림 사용 안내
        assertTrue(ko.contains("스타일"), ko) // 좋은 프롬프트 팁(스타일 등)
    }

    @Test
    fun `니아수다 가이드는 항상 답변이 아니라 사람처럼 참여함을 담는다`() {
        val ko = NiaChannelSetup.memberGuide("ko")
        assertTrue(ko.contains("사람처럼"), ko)
        assertTrue(ko.contains("모든 말에 답") || ko.contains("모든 말"), ko)
        assertTrue(ko.contains("조용히"), ko)
    }

    @Test
    fun `en·ja 가이드는 한국어 UI 문구를 누수하지 않는다`() {
        // 영어/일본어 클라이언트가 한글 가이드를 보면 안 된다(i18n 완전성).
        val hangul = Regex("[가-힣]")
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.chatGuide("en")), NiaChannelSetup.chatGuide("en"))
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.imageGuide("en")), NiaChannelSetup.imageGuide("en"))
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.memberGuide("en")), NiaChannelSetup.memberGuide("en"))
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.chatGuide("ja")), NiaChannelSetup.chatGuide("ja"))
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.imageGuide("ja")), NiaChannelSetup.imageGuide("ja"))
        assertFalse(hangul.containsMatchIn(NiaChannelSetup.memberGuide("ja")), NiaChannelSetup.memberGuide("ja"))
    }
}
