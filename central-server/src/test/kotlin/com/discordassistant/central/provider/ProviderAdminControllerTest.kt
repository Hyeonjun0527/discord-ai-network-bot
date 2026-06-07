package com.discordassistant.central.provider

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.globalpromptset.adapter.outbound.persistence.GlobalPromptSetRepository
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.guild.application.GuildChannelPolicy
import com.discordassistant.central.platform.discord.BotChannelInfo
import com.discordassistant.central.platform.discord.BotGuildInfo
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.adapter.inbound.web.AdminActionRequest
import com.discordassistant.central.provider.adapter.inbound.web.AdminChannelToggleRequest
import com.discordassistant.central.provider.adapter.inbound.web.AdminChannelsRequest
import com.discordassistant.central.provider.adapter.inbound.web.AdminPolicyRequest
import com.discordassistant.central.provider.adapter.inbound.web.AdminPromptSetRequest
import com.discordassistant.central.provider.adapter.inbound.web.ProviderAdminController
import com.discordassistant.central.provider.application.DurableTokenService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderRosterInfo
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.provider.domain.model.ProviderState
import com.discordassistant.central.shared.NexaIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 데스크톱 앱 관리 채널 — durable 토큰 신원 + JDA 관리자 판정 2단 게이트.
 * 권한 상승 불가, 기존 서비스 재사용, 로스터 보강(이름·모델·오늘)·자동 승인 토글·전역 프롬프트셋 관리 검증.
 *
 * 전역 프롬프트셋은 실 repository(at-rest 암호화 포함)를 거치므로 @DataJpaTest 로 슬라이스를 띄운다.
 * 나머지 협력자(token/registration/bot/roster)는 in-memory fake 로 둔다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProviderAdminControllerTest
    @Autowired
    constructor(
        private val promptSets: GlobalPromptSetRepository,
    ) {
        private class Ctx(
            val ctrl: ProviderAdminController,
            val reg: ProviderRegistrationService,
            val dtoken: String,
            val rosterState: MutableMap<String, Boolean>,
            val channelState: MutableList<Long>,
        )

        // 봇 텍스트 채널 고정 목록(채널 토글 테스트용). 다른 테스트는 botChannels 를 읽지 않는다.
        private val botChannelList =
            listOf(
                BotChannelInfo(10L, "general"),
                BotChannelInfo(20L, "ai-chat"),
                BotChannelInfo(30L, "공지"),
            )

        private fun fakeBot(admin: Boolean) =
            object : BotGuildLister {
                override fun botGuildIds() = emptySet<Long>()

                override fun botGuilds() = emptyList<BotGuildInfo>()

                override fun botChannels(guildId: Long) = botChannelList

                override fun isGuildAdmin(
                    guildId: Long,
                    userId: Long,
                ) = admin

                override fun memberName(
                    guildId: Long,
                    userId: Long,
                ) = "user_$userId"
            }

        private fun setup(admin: Boolean): Ctx {
            val clock = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC)
            val durable = DurableTokenService("admin-test-secret-key", 86_400, clock)
            val tokens = TokenService(ttlSeconds = 600, durable = durable)
            val reg = ProviderRegistrationService(tokens, AuditLog())
            val state = mutableMapOf("auto" to false)
            val roster =
                object : ProviderRosterInfo {
                    override fun modelsByProvider(guildId: Long) = mapOf(88L to 2)

                    override fun todayByProvider(guildId: Long) = mapOf(88L to 5L)

                    override fun isAutoApprove(guildId: Long) = state["auto"]!!

                    override fun setAutoApprove(
                        guildId: Long,
                        value: Boolean,
                        adminId: Long,
                    ) {
                        state["auto"] = value
                    }
                }
            // 채널 허용 목록 in-memory fake — 빈 목록 = 전체 허용 의미를 PolicyService 와 동일하게 모사.
            val channelState = mutableListOf<Long>()
            val guildChannels =
                object : GuildChannelPolicy {
                    override fun allowedChannelIds(guildId: Long) = channelState.toList()

                    override fun replaceAllowedChannels(
                        guildId: Long,
                        channelIds: Collection<Long>,
                        adminId: Long,
                    ) {
                        channelState.clear()
                        channelState.addAll(channelIds.distinct())
                    }

                    override fun allowAllChannels(
                        guildId: Long,
                        adminId: Long,
                    ) {
                        channelState.clear()
                    }
                }
            val ctrl =
                ProviderAdminController(tokens, reg, fakeBot(admin), roster, GlobalPromptSetService(promptSets, clock), guildChannels)
            val dtoken = durable.issueDurable(7L, 100L)!!
            return Ctx(ctrl, reg, dtoken, state, channelState)
        }

        @Test
        fun `관리자는 승인 대기 Provider 를 승인한다`() {
            val c = setup(admin = true)
            c.reg.requestJoin(99L, 100L, autoApprove = false)
            val res = c.ctrl.approve(AdminActionRequest(c.dtoken, 100L, 99L))
            assertTrue(res.ok)
            assertEquals(ProviderState.APPROVED, c.reg.stateOf(99L, 100L))
        }

        @Test
        fun `비관리자는 승인이 거부되고 상태가 불변이다`() {
            val c = setup(admin = false)
            c.reg.requestJoin(99L, 100L, autoApprove = false)
            val res = c.ctrl.approve(AdminActionRequest(c.dtoken, 100L, 99L))
            assertFalse(res.ok)
            assertEquals(ProviderState.PENDING, c.reg.stateOf(99L, 100L))
        }

        @Test
        fun `durable 이 아닌 토큰은 거부된다`() {
            val c = setup(admin = true)
            c.reg.requestJoin(99L, 100L, autoApprove = false)
            val res = c.ctrl.approve(AdminActionRequest("ABCDE-FGHIJ-KLMNP", 100L, 99L))
            assertFalse(res.ok)
            assertEquals(ProviderState.PENDING, c.reg.stateOf(99L, 100L))
        }

        @Test
        fun `manage 는 이름·모델·오늘·정책을 채운다`() {
            val c = setup(admin = true)
            c.reg.requestJoin(99L, 100L, autoApprove = false) // PENDING
            c.reg.requestJoin(88L, 100L, autoApprove = true) // APPROVED
            val res = c.ctrl.manage(AdminActionRequest(c.dtoken, 100L))
            assertTrue(res.ok)
            assertFalse(res.policy!!.autoApprove)
            assertTrue(res.pending.any { it.providerId == 99L && it.name == "user_99" })
            val r88 = res.roster.first { it.providerId == 88L }
            assertEquals("user_88", r88.name)
            assertEquals(2, r88.models)
            assertEquals(5L, r88.today)
        }

        @Test
        fun `setPolicy 는 자동 승인을 토글한다(관리자만)`() {
            val c = setup(admin = true)
            assertTrue(c.ctrl.setPolicy(AdminPolicyRequest(c.dtoken, 100L, autoApprove = true)).ok)
            assertEquals(true, c.rosterState["auto"])

            val denied = setup(admin = false)
            assertFalse(denied.ctrl.setPolicy(AdminPolicyRequest(denied.dtoken, 100L, autoApprove = true)).ok)
            assertEquals(false, denied.rosterState["auto"]) // 비관리자는 변경 못 함
        }

        @Test
        fun `관리자는 승인된 Provider 를 제거한다`() {
            val c = setup(admin = true)
            c.reg.requestJoin(88L, 100L, autoApprove = true)
            val res = c.ctrl.remove(AdminActionRequest(c.dtoken, 100L, 88L))
            assertTrue(res.ok)
            assertEquals(ProviderState.REMOVED, c.reg.stateOf(88L, 100L))
        }

        @Test
        fun `관리자는 전역 프롬프트셋을 조회·추가·기본지정·삭제한다(앱 관리채널)`() {
            val c = setup(admin = true)
            // 초기: 니아만(기본)
            val initial = c.ctrl.promptSets(AdminPromptSetRequest(c.dtoken, 100L))
            assertTrue(initial.ok)
            assertEquals(1, initial.sets.size)
            assertTrue(initial.sets.single().builtin)
            assertTrue(initial.sets.single().isDefault)

            // 추가(추가만으로 기본 아님)
            val added = c.ctrl.addPromptSet(AdminPromptSetRequest(c.dtoken, 100L, name = "정중한 비서", content = "당신은 정중한 비서입니다."))
            assertTrue(added.ok)
            assertEquals(2, added.sets.size)
            assertTrue(added.sets.first { it.builtin }.isDefault)
            val userId = added.sets.first { !it.builtin }.id

            // 기본 지정
            val def = c.ctrl.setDefaultPromptSet(AdminPromptSetRequest(c.dtoken, 100L, id = userId))
            assertTrue(def.ok)
            assertTrue(def.sets.first { !it.builtin }.isDefault)
            assertFalse(def.sets.first { it.builtin }.isDefault)

            // 삭제 → 니아로 복귀
            val del = c.ctrl.deletePromptSet(AdminPromptSetRequest(c.dtoken, 100L, id = userId))
            assertTrue(del.ok)
            assertEquals(1, del.sets.size)
            assertTrue(del.sets.single { it.builtin }.isDefault)
        }

        @Test
        fun `비관리자는 전역 프롬프트셋 접근이 거부되고, builtin 은 전문 비공개에 중복·builtin삭제는 막힌다`() {
            val denied = setup(admin = false)
            assertFalse(denied.ctrl.promptSets(AdminPromptSetRequest(denied.dtoken, 100L)).ok)
            assertFalse(
                denied.ctrl.addPromptSet(AdminPromptSetRequest(denied.dtoken, 100L, name = "x", content = "y")).ok,
            )

            val c = setup(admin = true)
            // builtin(니아) 전문 비공개 — content 없음, preview 만.
            val nia =
                c.ctrl
                    .promptSets(AdminPromptSetRequest(c.dtoken, 100L))
                    .sets
                    .single { it.builtin }
            assertNull(nia.content)
            assertEquals(NexaIdentity.NIA_PREVIEW, nia.preview)

            // 중복 이름 추가 실패
            assertTrue(c.ctrl.addPromptSet(AdminPromptSetRequest(c.dtoken, 100L, name = "중복", content = "A")).ok)
            val dup = c.ctrl.addPromptSet(AdminPromptSetRequest(c.dtoken, 100L, name = "중복", content = "B"))
            assertFalse(dup.ok)
            assertEquals("같은 이름의 프롬프트셋이 이미 있어요", dup.message)

            // builtin 삭제 불가
            assertFalse(c.ctrl.deletePromptSet(AdminPromptSetRequest(c.dtoken, 100L, id = "nia")).ok)
        }

        @Test
        fun `관리자는 채널 AI 허용을 조회하고 빈목록=전체허용 의미로 토글한다`() {
            val c = setup(admin = true)
            // 초기: 허용 목록 비어 있음 → 모든 채널 허용. channelId 는 문자열(64bit 정밀도 보존).
            val all = c.ctrl.channels(AdminChannelsRequest(c.dtoken, 100L))
            assertTrue(all.ok)
            assertEquals(3, all.channels.size)
            assertTrue(all.channels.all { it.aiAllowed })
            assertEquals("20", all.channels.first { it.name == "ai-chat" }.channelId)

            // ai-chat(20) 끄기 → 그 채널만 빼고 전부 허용([10,30])
            val off = c.ctrl.toggleChannel(AdminChannelToggleRequest(c.dtoken, 100L, channelId = 20L, allow = false))
            assertTrue(off.ok)
            assertFalse(off.channels.first { it.channelId == "20" }.aiAllowed)
            assertTrue(off.channels.first { it.channelId == "10" }.aiAllowed)
            assertEquals(setOf(10L, 30L), c.channelState.toSet())

            // 다시 켜기 → 전체 허용 복귀(빈 목록)
            val on = c.ctrl.toggleChannel(AdminChannelToggleRequest(c.dtoken, 100L, channelId = 20L, allow = true))
            assertTrue(on.channels.all { it.aiAllowed })
            assertTrue(c.channelState.isEmpty())
        }

        @Test
        fun `비관리자는 채널 조회·토글이 거부되고 정책이 불변이다`() {
            val d = setup(admin = false)
            assertFalse(d.ctrl.channels(AdminChannelsRequest(d.dtoken, 100L)).ok)
            assertFalse(d.ctrl.toggleChannel(AdminChannelToggleRequest(d.dtoken, 100L, channelId = 20L, allow = false)).ok)
            assertTrue(d.channelState.isEmpty()) // 변경 없음
        }
    }
