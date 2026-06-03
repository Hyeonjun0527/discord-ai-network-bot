package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * CommandService 핵심 로직 광범위 커버(차수 18 커버리지 보강). JDA 와 분리된 순수 비즈니스 로직이라
 * 실제 컨텍스트에서 입력→Reply 를 직접 검증한다. registration 은 인메모리라 테스트마다 고유 ID 사용.
 */
@SpringBootTest
@Transactional
class CommandServiceCoverageTest
    @Autowired
    constructor(
        val commands: CommandService,
    ) {
        private var seq = 70_000L

        private fun next() = seq++

        private fun user(
            guildId: Long = next(),
            admin: Boolean = false,
        ) = CommandContext(guildId = guildId, channelId = next(), userId = next(), roleIds = setOf(1L), isAdmin = admin)

        // ── 일반 유저 조회 명령 ──────────────────────────────────────────
        @Test
        fun `catalog — 빈 풀 안내`() {
            assertTrue(commands.catalog(user()).content.contains("온라인 프로바이더가 없습니다"))
        }

        @Test
        fun `contributions — 누적 기여가 없으면 안내`() {
            assertTrue(commands.contributions(user()).content.contains("아직 누적 기여가 없습니다"))
        }

        @Test
        fun `myUsage — 오늘 사용량 표시`() {
            assertTrue(commands.myUsage(user()).content.contains("오늘 사용량:"))
        }

        @Test
        fun `models — 최대 수준·풀 크기 표시`() {
            assertTrue(commands.models(user()).content.contains("사용 가능한 최대 모델 수준"))
        }

        // ── 프로바이더 셀프서비스 ────────────────────────────────────────
        @Test
        fun `provider pause·resume·leave — 미연결이면 에이전트 없음 안내`() {
            val c = user()
            assertTrue(commands.providerPause(c).content.contains("연결된 에이전트가 없습니다"))
            assertTrue(commands.providerResume(c).content.contains("연결된 에이전트가 없습니다"))
            assertTrue(commands.providerLeave(c).content.contains("연결된 에이전트가 없습니다"))
        }

        @Test
        fun `providerStatus — 미연결이면 오프라인`() {
            assertTrue(commands.providerStatus(user()).content.contains("오프라인"))
        }

        @Test
        fun `providerModels — 제공 모델 설정 확인`() {
            assertTrue(commands.providerModels(user(), listOf("llama3", "mistral")).content.contains("제공 모델 설정"))
        }

        @Test
        fun `providerLimit — 한도 설정 확인`() {
            assertTrue(commands.providerLimit(user(), "llama3", daily = 100, concurrency = 2, seconds = 60).content.contains("한도"))
        }

        @Test
        fun `providerScope — 허용 범위 설정 확인`() {
            assertTrue(commands.providerScope(user(), "llama3", "everyone").content.contains("허용 범위"))
        }

        @Test
        fun `providerSchedule — 유효 시간은 설정, 범위 밖은 경고`() {
            assertTrue(commands.providerSchedule(user(), 22, 6).content.contains("가용 시간대"))
            assertTrue(commands.providerSchedule(user(), 25, 6).content.contains("0~23"))
        }

        @Test
        fun `providerJoin — 수동 승인 길드는 대기 안내`() {
            val applicant = user()
            commands.setAutoApprove(user(guildId = applicant.guildId, admin = true), enabled = false) // 기본 자동 → 수동
            assertTrue(commands.providerJoin(applicant).content.contains("승인을 기다려"))
        }

        // ── 관리자 명령(정상 경로) ───────────────────────────────────────
        @Test
        fun `setGuildDefaults — 관리자는 기본값 설정`() {
            val admin = user(admin = true)
            assertTrue(commands.setGuildDefaults(admin, "llama3", "en").content.contains("길드 기본값"))
        }

        @Test
        fun `welcome — 설정 전 안내, 설정 후 표시`() {
            val admin = user(admin = true)
            assertTrue(commands.welcome(admin).content.contains("아직 환영"))
            assertTrue(commands.setWelcome(admin, "어서오세요").content.contains("환영"))
            assertTrue(commands.welcome(admin).content.contains("어서오세요"))
        }

        @Test
        fun `denyChannel — 관리자는 채널 금지`() {
            assertTrue(commands.denyChannel(user(admin = true), 9999).content.contains("🚫"))
        }

        @Test
        fun `setRolePolicy — 관리자는 역할 정책 설정`() {
            val r = commands.setRolePolicy(user(admin = true), roleId = 5L, maxBurden = ModelBurden.STANDARD, dailyLimit = 50)
            assertTrue(r.content.contains("역할"))
        }

        @Test
        fun `block·unblock — 관리자는 차단·해제`() {
            val admin = user(admin = true)
            val target = next()
            assertTrue(commands.blockUser(admin, target).content.contains("차단"))
            assertTrue(commands.unblockUser(admin, target).content.contains("해제"))
        }

        @Test
        fun `providers — 관리자는 대기·온라인 목록`() {
            assertTrue(commands.providers(user(admin = true)).content.contains("승인 대기"))
        }

        @Test
        fun `approveProvider — 대기 없으면 안내, 신청 후엔 온보딩`() {
            val admin = user(admin = true)
            commands.setAutoApprove(admin, enabled = false) // 기본 자동 → 수동(승인 대기열 생기게)
            assertTrue(commands.approveProvider(admin, 8_888_001L).content.contains("승인할 대기 중"))
            // 같은 길드에서 고유 유저가 신청 → 승인하면 온보딩(에페메랄) 반환.
            val applicant =
                CommandContext(guildId = admin.guildId, channelId = 1, userId = 8_888_002L, roleIds = setOf(1L), isAdmin = false)
            commands.providerJoin(applicant)
            val approved = commands.approveProvider(admin, applicant.userId)
            assertTrue(approved.ephemeral)
            assertFalse(approved.content.contains("승인할 대기 중"))
        }

        @Test
        fun `removeProvider — 없는 프로바이더는 못 찾음`() {
            assertTrue(commands.removeProvider(user(admin = true), 7_777_001L).content.contains("찾을 수 없"))
        }

        @Test
        fun `allowAllChannels — 관리자는 채널 제한 해제`() {
            val admin = user(admin = true)
            assertTrue(commands.allowAllChannels(admin).content.contains("모든 채널"))
            assertTrue(commands.allowedChannelIds(admin).isEmpty())
        }

        // ── 관리자 가드(비관리자 차단) ───────────────────────────────────
        @Test
        fun `관리자 전용 명령은 비관리자에게 거부된다`() {
            val u = user(admin = false)
            val denied =
                listOf(
                    commands.fairness(u),
                    commands.setGuildDefaults(u, "x", "en"),
                    commands.denyChannel(u, 1),
                    commands.setRolePolicy(u, 1, ModelBurden.STANDARD, 1),
                    commands.blockUser(u, 2),
                    commands.unblockUser(u, 2),
                    commands.approveProvider(u, 3),
                    commands.removeProvider(u, 4),
                    commands.providers(u),
                    commands.allowAllChannels(u),
                    commands.setWelcome(u, "x"),
                    commands.setChannelAiProfile(u, "냥시스턴트", null, false),
                    commands.toggleAutoApprove(u),
                )
            assertTrue(denied.all { it.content.contains("⛔") }, "모든 관리자 명령은 비관리자에게 ⛔: ${denied.map { it.content.take(8) }}")
        }
    }
